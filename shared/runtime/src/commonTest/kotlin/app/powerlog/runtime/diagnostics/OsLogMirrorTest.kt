package app.powerlog.runtime.diagnostics

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.data.LogLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class OsLogMirrorTest {
    private class Kept : DiagnosticLogRepository {
        val lines = mutableListOf<LogLine>()
        val chunk: StoreResult<LogChunk> = StoreResult.Ok(LogChunk(ByteArray(0), null))

        override suspend fun append(line: LogLine) {
            lines += line
        }

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = chunk
    }

    private val lines = LogLevel.entries.map { LogLine(it, 1, 2, "launch", emptyList()) }

    private suspend fun mirrored(everyLine: Boolean): Pair<Kept, List<String>> {
        val kept = Kept()
        val written = mutableListOf<String>()
        val mirror = OsLogMirror(kept, everyLine) { level, line -> written += "$level $line" }
        lines.forEach { mirror.append(it) }
        assertSame(kept.chunk, mirror.read(0, 4_096))
        return kept to written
    }

    @Test
    fun releaseBuildsMirrorWarningsAndErrorsAndKeepEveryLine() = runTest {
        val (kept, written) = mirrored(everyLine = false)
        assertEquals(lines, kept.lines)
        assertEquals(lines.drop(1).map { "${it.level} ${LogLines.encode(it)}" }, written)
    }

    @Test
    fun developmentBuildsMirrorEveryLine() = runTest {
        val (kept, written) = mirrored(everyLine = true)
        assertEquals(lines, kept.lines)
        assertEquals(lines.map { "${it.level} ${LogLines.encode(it)}" }, written)
    }
}
