@file:OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class, ExperimentalAtomicApi::class)

package app.powerlog.runtime.compose

import app.powerlog.runtime.compose.AppleQueueFactory.Priority
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.darwin.dispatch_queue_get_label

class QueueDispatcherTest {
    private fun label(): String? = dispatch_queue_get_label(null)?.toKString()

    private val hostQueues =
        AppleQueues(
            ride = AppleQueueFactory.serial("app.powerlog.ride", Priority.utility),
            health = AppleQueueFactory.serial("app.powerlog.health", Priority.utility),
            maintenance = AppleQueueFactory.serial("app.powerlog.maintenance", Priority.utility),
        )

    private val dispatchers: List<Pair<String, CoroutineDispatcher>> =
        AppleExecutors.application(hostQueues).let {
            listOf(
                "app.powerlog.ride" to it.ride,
                "app.powerlog.health" to it.health,
                "app.powerlog.maintenance" to it.maintenance,
                "app.powerlog.reads" to it.reads,
                "app.powerlog.reads" to it.scans,
            )
        } +
            AppleExecutors.stores().let {
                listOf(
                    "app.powerlog.archive" to it.archiveWriter,
                    "app.powerlog.archive.reads" to it.archiveReads,
                    "app.powerlog.cache" to it.cacheWriter,
                    "app.powerlog.cache.reads" to it.cacheReads,
                    "app.powerlog.log" to it.log,
                )
            }

    private suspend fun resumedAfterDelay(dispatcher: CoroutineDispatcher): String? =
        withContext(Dispatchers.Unconfined) {
            suspendCancellableCoroutine<Unit> { (dispatcher as Delay).scheduleResumeAfterDelay(5, it) }
            label()
        }

    private suspend fun timeoutFiredOn(dispatcher: CoroutineDispatcher): String? {
        val firedOn = CompletableDeferred<String?>()
        withContext(dispatcher) {
            try {
                withTimeout(5) {
                    suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { firedOn.complete(label()) } }
                }
            } catch (_: TimeoutCancellationException) {}
        }
        return firedOn.await()
    }

    @Test
    fun delayResumesOnTheExecutorsOwnQueue() = runBlocking {
        for ((queue, dispatcher) in dispatchers) assertEquals(queue, resumedAfterDelay(dispatcher))
    }

    @Test
    fun withTimeoutFiresOnTheExecutorsOwnQueue() = runBlocking {
        for ((queue, dispatcher) in dispatchers) assertEquals(queue, timeoutFiredOn(dispatcher))
    }

    @Test
    fun aDisposedTimeoutNeverRuns() = runBlocking {
        for ((_, dispatcher) in dispatchers) {
            val ran = AtomicBoolean(false)
            (dispatcher as Delay).invokeOnTimeout(20, Runnable { ran.store(true) }, EmptyCoroutineContext).dispose()
            delay(40)
            assertFalse(ran.load())
        }
    }
}
