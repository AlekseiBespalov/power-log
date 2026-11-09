package app.powerlog.application.diagnostics

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

public class DiagnosticLog(private val store: DiagnosticLogRepository, private val maintenance: CoroutineDispatcher) :
    DiagnosticLogRepository {
    private val written = mutableSetOf<Pair<String, String>>()

    override suspend fun append(line: LogLine) {
        withContext(maintenance) {
            if (line.event == "storageFailed") {
                val stage = (line.fields.firstOrNull { it.key == "stage" }?.value as? LogValue.Text)?.value
                val kind = (line.fields.firstOrNull { it.key == "kind" }?.value as? LogValue.Text)?.value
                if (stage in onceStages && !written.add(kind.orEmpty() to stage.orEmpty())) return@withContext
            }
            store.append(line)
        }
    }

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = store.read(after, maxBytes)

    private companion object {
        val onceStages = setOf("read", "launch", "cache")
    }
}
