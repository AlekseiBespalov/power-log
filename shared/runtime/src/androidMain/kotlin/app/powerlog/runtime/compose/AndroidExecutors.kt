package app.powerlog.runtime.compose

import android.os.Handler
import android.os.HandlerThread
import app.powerlog.application.Executors
import app.powerlog.data.StoreExecutors
import java.util.concurrent.Executors as JavaExecutors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal object AndroidExecutors {
    fun application(threads: AndroidThreads): Executors =
        Executors(
            ride = looper(threads.ride),
            health = looper(threads.health),
            maintenance = looper(threads.maintenance),
            reads = pool("PowerLogRead", 4),
        )

    fun stores(): StoreExecutors =
        StoreExecutors(
            archiveWriter = single("PowerLogArchive"),
            archiveReads = pool("PowerLogArchiveRead", 4),
            cacheWriter = single("PowerLogCache"),
            cacheReads = pool("PowerLogCacheRead", 2),
            log = single("PowerLogLog"),
        )

    fun pool(name: String, threads: Int): CoroutineDispatcher {
        val next = AtomicInteger(0)
        val factory = ThreadFactory { work -> Thread(work, "$name-${next.incrementAndGet()}") }
        return JavaExecutors.newScheduledThreadPool(threads, factory).asCoroutineDispatcher()
    }

    fun single(name: String): CoroutineDispatcher =
        JavaExecutors.newSingleThreadScheduledExecutor { work -> Thread(work, name) }.asCoroutineDispatcher()

    private fun looper(thread: HandlerThread): CoroutineDispatcher =
        Handler(thread.looper).asCoroutineDispatcher(thread.name)
}
