package app.powerlog.runtime.diagnostics

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.data.LogLines

internal class OsLogMirror(
    private val log: DiagnosticLogRepository,
    private val everyLine: Boolean,
    private val write: (level: LogLevel, line: String) -> Unit,
) : DiagnosticLogRepository {
    override suspend fun append(line: LogLine) {
        if (everyLine || line.level != LogLevel.info) write(line.level, LogLines.encode(line))
        log.append(line)
    }

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = log.read(after, maxBytes)
}
