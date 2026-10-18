package app.powerlog.bridge

import expo.modules.kotlin.Promise
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MonitorReadsTest {
    private class ResultPromise : Promise {
        val settled = CountDownLatch(1)
        @Volatile var code: String? = null
        @Volatile var value: Any? = null

        override fun resolve(value: Any?) {
            this.value = value
            settled.countDown()
        }

        override fun reject(code: String?, message: String?, cause: Throwable?) {
            this.code = code
            settled.countDown()
        }
    }

    @Test
    fun latestAndInspectRunWhilePlotAndRangeSlotsAreOccupiedAndCancelledWorkIsInterrupted() {
        MonitorReads().use { reads ->
            val started = CountDownLatch(4)
            val release = CountDownLatch(1)
            val interrupted = CountDownLatch(4)
            val slow = (0..3).map { ResultPromise() }
            try {
                slow.forEachIndexed { index, promise ->
                    reads.submit(
                        "old-source",
                        "slow-$index",
                        if (index < 2) MonitorOperation.Plot else MonitorOperation.Stats,
                        promise,
                    ) {
                        started.countDown()
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                        "obsolete"
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                for (operation in listOf(MonitorOperation.Latest, MonitorOperation.Inspect)) {
                    val promise = ResultPromise()
                    reads.submit("current", operation.name, operation, promise) { 7 }
                    assertTrue(promise.settled.await(1, TimeUnit.SECONDS))
                    assertEquals(7, promise.value)
                }
                reads.cancelOwner("old-source")
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                slow.forEach {
                    assertEquals("ERR_READ_CANCELLED", it.code)
                    assertNull(it.value)
                }
            } finally {
                release.countDown()
            }
        }
    }
}
