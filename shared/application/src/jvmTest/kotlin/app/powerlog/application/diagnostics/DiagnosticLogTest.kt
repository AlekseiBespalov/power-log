package app.powerlog.application.diagnostics

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DiagnosticLogTest {
    @Test
    fun aRepeatedReadLaunchOrCacheStorageFailedIsDroppedAndEveryCommitOneIsKept() = runTest {
        val written = mutableListOf<LogLine>()
        val store =
            object : DiagnosticLogRepository {
                override suspend fun append(line: LogLine) {
                    written += line
                }

                override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
            }
        val log = DiagnosticLog(store, StandardTestDispatcher(testScheduler))
        for (stage in listOf("read", "read", "launch", "cache", "cache", "capture", "capture", "command")) log.append(
            failed("io", stage)
        )
        log.append(failed("constraint", "read"))
        assertEquals(
            listOf("read", "launch", "cache", "capture", "capture", "command", "read"),
            written.map { (it.fields.single { field -> field.key == "stage" }.value as LogValue.Text).value },
        )
    }

    private fun failed(kind: String, stage: String) =
        LogLine(
            LogLevel.error,
            0,
            0,
            "storageFailed",
            listOf(LogField("kind", LogValue.Text(kind)), LogField("stage", LogValue.Text(stage))),
        )
}
