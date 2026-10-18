package app.powerlog.bridge

import expo.modules.kotlin.Promise
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SummaryReadsTest {
    private class ResultPromise : Promise {
        val settled = CountDownLatch(1)
        val resolutions = AtomicInteger()
        @Volatile var code: String? = null

        override fun resolve(value: Any?) {
            resolutions.incrementAndGet()
            settled.countDown()
        }

        override fun reject(code: String?, message: String?, cause: Throwable?) {
            this.code = code
            settled.countDown()
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

    private fun drain(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                continue
            }
        }
    }

    @Test
    fun detailAndCatalogShareTwoSlotsAndPressureDefers() {
        val reads = SummaryReads()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val promises = listOf(ResultPromise(), ResultPromise())
        try {
            promises.forEachIndexed { index, promise ->
                reads.submit(if (index == 0) "detail" else "catalog", "job-$index", promise) {
                    started.countDown()
                    drain(release)
                    index
                }
            }
            await(started)
            val rejected = ResultPromise()
            reads.submit("catalog", "overflow", rejected) { 7 }
            await(rejected.settled)
            assertEquals("ERR_READ_DEFERRED", rejected.code)
            release.countDown()
            promises.forEach { await(it.settled) }
            assertEquals(listOf(1, 1), promises.map { it.resolutions.get() })
        } finally {
            release.countDown()
            reads.close()
        }
    }

    @Test
    fun cancellationKeepsItsSlotUntilWorkDrainsAndSuppressesTheResult() {
        val reads = SummaryReads()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(2)
        val promises = listOf(ResultPromise(), ResultPromise())
        try {
            promises.forEachIndexed { index, promise ->
                reads.submit("owner", "job-$index", promise) {
                    started.countDown()
                    drain(release)
                    drained.countDown()
                    index
                }
            }
            await(started)
            reads.cancel("job-0")
            await(promises[0].settled)
            assertEquals("ERR_READ_CANCELLED", promises[0].code)
            val rejected = ResultPromise()
            reads.submit("owner", "new", rejected) { 7 }
            await(rejected.settled)
            assertEquals("ERR_READ_DEFERRED", rejected.code)
            release.countDown()
            await(drained)
            await(promises[1].settled)
            assertEquals(0, promises[0].resolutions.get())
            assertEquals(1, promises[1].resolutions.get())
        } finally {
            release.countDown()
            reads.close()
        }
    }

    @Test
    fun destroyingOneBridgeCancelsOnlyItsOwnJobs() {
        val reads = SummaryReads()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val cancelled = ResultPromise()
        val retained = ResultPromise()
        try {
            for ((owner, promise) in listOf("destroyed" to cancelled, "retained" to retained)) {
                reads.submit(owner, owner, promise) {
                    started.countDown()
                    drain(release)
                    owner
                }
            }
            await(started)
            reads.cancelOwner("destroyed")
            await(cancelled.settled)
            assertEquals("ERR_READ_CANCELLED", cancelled.code)
            assertEquals(1L, retained.settled.count)
            release.countDown()
            await(retained.settled)
            assertEquals(0, cancelled.resolutions.get())
            assertEquals(1, retained.resolutions.get())
        } finally {
            release.countDown()
            reads.close()
        }
    }

    @Test
    fun malformedOrDuplicateCancellationIdsDoNotDispatch() {
        val reads = SummaryReads()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            reads.submit("owner", "same", ResultPromise()) {
                started.countDown()
                drain(release)
            }
            await(started)
            for (id in listOf("same", "", "a".repeat(129))) {
                val rejected = ResultPromise()
                reads.submit("owner", id, rejected) { 7 }
                await(rejected.settled)
                assertEquals("ERR_INVALID_RECORD", rejected.code)
            }
        } finally {
            release.countDown()
            reads.close()
        }
    }
}
