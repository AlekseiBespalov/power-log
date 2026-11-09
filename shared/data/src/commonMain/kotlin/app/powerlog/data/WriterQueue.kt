package app.powerlog.data

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalAtomicApi::class)
internal class WriterQueue(private val capacity: Int = StorePolicy.queueJobs) {
    private val lock = Mutex()
    private val waiting = AtomicInt(0)

    // null: capacity jobs were already waiting, and this one was not queued
    suspend fun <T> run(job: suspend () -> T): T? {
        if (waiting.incrementAndFetch() > capacity) {
            waiting.decrementAndFetch()
            return null
        }
        var queued = true
        try {
            return lock.withLock {
                waiting.decrementAndFetch()
                queued = false
                job()
            }
        } finally {
            if (queued) waiting.decrementAndFetch()
        }
    }

    suspend fun <T> exclusive(job: suspend () -> T): T = lock.withLock { job() }
}
