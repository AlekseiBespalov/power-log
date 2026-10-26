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

    private fun input(operation: MonitorOperation, totals: Boolean = true, current: Boolean = false) =
        MonitorInput(operation, MonitorTarget.Live, 0, energyTotals = totals, energyCurrent = current)

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
                        input(if (index < 2) MonitorOperation.Plot else MonitorOperation.Stats),
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
                    reads.submit("current", operation.name, input(operation), promise) { 7 }
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

    @Test
    fun currentEnergyReadsRunWhileWholeRideEnergyReadsOccupyTheirLane() {
        MonitorReads().use { reads ->
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val held = (0..1).map { ResultPromise() }
            try {
                held.forEachIndexed { index, promise ->
                    reads.submit("source", "totals-$index", input(MonitorOperation.EnergyPerDistance), promise) {
                        started.countDown()
                        release.await()
                        "totals"
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val current = ResultPromise()
                reads.submit(
                    "source",
                    "current",
                    input(MonitorOperation.EnergyPerDistance, totals = false, current = true),
                    current,
                ) {
                    7
                }
                assertTrue(current.settled.await(1, TimeUnit.SECONDS))
                assertEquals(7, current.value)
                for ((name, request) in
                    listOf(
                        "totals" to input(MonitorOperation.EnergyPerDistance),
                        "current-with-totals" to input(MonitorOperation.EnergyPerDistance, current = true),
                        "cursor" to input(MonitorOperation.EnergyPerDistance, totals = false),
                    )) {
                    val deferred = ResultPromise()
                    reads.submit("source", name, request, deferred) { 8 }
                    assertTrue(deferred.settled.await(1, TimeUnit.SECONDS))
                    assertEquals(name, "ERR_READ_DEFERRED", deferred.code)
                }
            } finally {
                release.countDown()
            }
            held.forEach {
                assertTrue(it.settled.await(5, TimeUnit.SECONDS))
                assertEquals("totals", it.value)
            }
        }
    }
}
