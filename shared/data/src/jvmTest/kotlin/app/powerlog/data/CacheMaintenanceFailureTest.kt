package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Step
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.ok
import app.powerlog.domain.core.time.ClockReading
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CacheMaintenanceFailureTest {
    private class RecordedLog : DiagnosticLogRepository {
        val lines = mutableListOf<LogLine>()

        override suspend fun append(line: LogLine) {
            synchronized(lines) { lines += line }
        }

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
            StoreResult.Ok(LogChunk(ByteArray(0), null))
    }

    @Test
    fun aCacheThatFailsReclaimAndCheckpointIsReportedOnceAndTheArchiveGoesOn() = runTest {
        val directory = Files.createTempDirectory("power-log-store").toFile()
        val executors = JvmSqlPlatform.executors()
        val log = RecordedLog()
        val stores = JvmStores.files(directory, executors.store, log, { ClockReading(0, 0) })
        try {
            stores.stores.host.open().ok()
            stores.cache.job { it.execute("PRAGMA query_only = 1") }.ok()
            assertEquals(Step.done, stores.stores.maintenance.reclaimStep().ok())
            stores.stores.maintenance.checkpoint(truncate = true).ok()
            val line = log.lines.single()
            assertEquals("storageFailed", line.event)
            assertEquals(LogValue.Text("cache"), line.fields.single { it.key == "stage" }.value)
        } finally {
            stores.stores.host.close()
            executors.close()
            directory.deleteRecursively()
        }
    }
}
