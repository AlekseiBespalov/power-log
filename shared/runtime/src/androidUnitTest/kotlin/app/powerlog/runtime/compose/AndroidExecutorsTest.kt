@file:OptIn(InternalCoroutinesApi::class)

package app.powerlog.runtime.compose

import android.os.HandlerThread
import android.os.Looper
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidExecutorsTest {
    private fun started(name: String) = HandlerThread(name).apply { start() }

    private val threads =
        AndroidThreads(started("PowerLogRide"), started("PowerLogHealth"), started("PowerLogMaintenance"))
    private val executors = AndroidExecutors.application(threads)
    private val loopers =
        listOf(
            "PowerLogRide" to executors.ride,
            "PowerLogHealth" to executors.health,
            "PowerLogMaintenance" to executors.maintenance,
        )
    private val stores = AndroidExecutors.stores()
    private val pools =
        listOf(
            "PowerLogRead-" to executors.reads,
            "PowerLogRead-" to executors.scans,
            "PowerLogArchive" to stores.archiveWriter,
            "PowerLogArchiveRead-" to stores.archiveReads,
            "PowerLogCache" to stores.cacheWriter,
            "PowerLogCacheRead-" to stores.cacheReads,
            "PowerLogLog" to stores.log,
        )

    // A HandlerThread's postDelayed waits on Robolectric's paused clock, so advance it until the work is done.
    private fun <T> awaitOnLooper(work: Deferred<T>): T = runBlocking {
        while (!work.isCompleted) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(5))
            Thread.sleep(1)
        }
        work.await()
    }

    private val scope = CoroutineScope(SupervisorJob())

    private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")

    private fun defaultExecutor(): Thread? =
        Thread.getAllStackTraces().keys.firstOrNull { it.name == "kotlinx.coroutines.DefaultExecutor" && it.isAlive }

    @BeforeTest
    fun letAnEarlierDefaultExecutorRetire() {
        defaultExecutor()?.join(10_000)
    }

    private fun resumedAfterDelay(dispatcher: CoroutineDispatcher): Deferred<String> =
        scope.async(Dispatchers.Unconfined) {
            suspendCancellableCoroutine<Unit> { (dispatcher as Delay).scheduleResumeAfterDelay(20, it) }
            threadName()
        }

    private fun timeoutFiredOn(dispatcher: CoroutineDispatcher): Deferred<String> {
        val firedOn = CompletableDeferred<String>()
        scope.launch(dispatcher) {
            try {
                withTimeout(20) {
                    suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { firedOn.complete(threadName()) } }
                }
            } catch (_: TimeoutCancellationException) {}
        }
        return firedOn
    }

    @Test
    fun delayResumesOnTheHandlerThreads() {
        for ((name, dispatcher) in loopers) assertEquals(name, awaitOnLooper(resumedAfterDelay(dispatcher)))
        assertNull(defaultExecutor())
    }

    @Test
    fun withTimeoutFiresOnTheHandlerThreads() {
        for ((name, dispatcher) in loopers) assertEquals(name, awaitOnLooper(timeoutFiredOn(dispatcher)))
        assertNull(defaultExecutor())
    }

    @Test
    fun delayResumesOnThePoolsThreads() = runBlocking {
        for ((prefix, dispatcher) in pools) {
            val resumedOn = resumedAfterDelay(dispatcher).await()
            assertTrue(resumedOn.startsWith(prefix), resumedOn)
        }
        assertNull(defaultExecutor())
    }

    @Test
    fun withTimeoutFiresOnThePoolsThreads() = runBlocking {
        for ((prefix, dispatcher) in pools) {
            val firedOn = timeoutFiredOn(dispatcher).await()
            assertTrue(firedOn.startsWith(prefix), firedOn)
        }
        assertNull(defaultExecutor())
    }

    @Test
    fun theArchiveWriterIsOneThread() = runBlocking {
        val names = (1..20).map { scope.async(stores.archiveWriter) { Thread.currentThread() }.await() }.toSet()
        assertEquals(1, names.size)
    }

    @Test
    fun noExecutorRunsOnTheMainThread() = runBlocking {
        val main = Looper.getMainLooper().thread
        for ((_, dispatcher) in pools) assertTrue(scope.async(dispatcher) { Thread.currentThread() }.await() != main)
        for ((_, dispatcher) in loopers) assertTrue(
            awaitOnLooper(scope.async(dispatcher) { Thread.currentThread() }) != main
        )
    }
}
