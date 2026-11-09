package app.powerlog.application.api

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@OptIn(ExperimentalAtomicApi::class)
internal class Keys {
    private val running = AtomicReference<Map<String, Job>>(emptyMap())

    suspend fun <T> run(key: String, cancelled: T, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        while (true) {
            val current = running.load()
            if (running.compareAndSet(current, current + (key to work))) {
                current[key]?.cancel()
                break
            }
        }
        try {
            work.await()
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) throw exception
            cancelled
        } finally {
            while (true) {
                val current = running.load()
                if (current[key] !== work || running.compareAndSet(current, current - key)) break
            }
        }
    }

    fun cancel(key: String) {
        running.load()[key]?.cancel()
    }
}
