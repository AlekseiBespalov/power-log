package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.time.ClockReading
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal class CacheFailures(private val log: DiagnosticLogRepository, private val clock: () -> ClockReading) {
    private val reported = AtomicReference<Set<StorageError.Kind>>(emptySet())

    suspend fun report(error: StorageError) {
        while (true) {
            val kinds = reported.load()
            if (error.kind in kinds) return
            if (reported.compareAndSet(kinds, kinds + error.kind)) break
        }
        val now = clock()
        val fields = buildList {
            add(LogField("kind", LogValue.Text(error.kind.name)))
            add(LogField("stage", LogValue.Text("cache")))
            error.platform?.let { platform ->
                val code =
                    listOf(
                        LogField("domain", LogValue.Text(platform.domain)),
                        LogField("code", LogValue.Text(platform.code)),
                    )
                add(LogField("platform", LogValue.Fields(code)))
            }
        }
        log.append(LogLine(LogLevel.error, now.utcUs, now.monoUs, "storageFailed", fields))
    }

    suspend fun <T> decoded(decode: () -> T): T? =
        try {
            decode()
        } catch (_: Exception) {
            report(StorageError(StorageError.Kind.corrupt))
            null
        }
}
