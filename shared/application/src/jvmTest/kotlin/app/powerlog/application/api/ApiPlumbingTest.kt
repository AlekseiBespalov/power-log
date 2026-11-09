package app.powerlog.application.api

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ApiPlumbingTest {
    private fun TestScope.clock() =
        object : Clock {
            override fun monoUs(): Long = testScheduler.currentTime * 1_000

            override fun utcUs(): Long = monoUs()

            override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
        }

    @Test
    fun aCallWaitsForTheGateThenAnswersStorageBusy() = runTest {
        val gate = LaunchGate()
        assertEquals(FailureCode.storageBusy, gate.failure()?.code)
        assertEquals(30_000L, testScheduler.currentTime)
        val waiting = async { gate.failure() }
        runCurrent()
        gate.open()
        assertNull(waiting.await())
    }

    @Test
    fun aRefusedStoreAnswersStorageRefusedWithItsDetail() = runTest {
        val gate = LaunchGate()
        gate.refuse(RefusalDetail.unreadable)
        gate.refuse(RefusalDetail.unavailable)
        val failure = gate.failure()
        assertEquals(FailureCode.storageRefused, failure?.code)
        assertEquals(RefusalDetail.unreadable, failure?.detail)
    }

    @Test
    fun aNewerRequestWithTheKeyOrCancelCancelsTheOlder() = runTest {
        val keys = Keys()
        val held = CompletableDeferred<String>()
        val older = async { keys.run("k", "cancelled") { held.await() } }
        runCurrent()
        val newer = async { keys.run("k", "cancelled") { "newer" } }
        assertEquals("cancelled", older.await())
        assertEquals("newer", newer.await())
        val another = async { keys.run("other", "cancelled") { held.await() } }
        runCurrent()
        keys.cancel("other")
        assertEquals("cancelled", another.await())
    }

    @Test
    fun aStreamAnswersUrgentChangesAtOnceAndOthersAtItsInterval() = runTest {
        val stream = Published("a", 1_000_000, clock())
        val first = stream.next(null) as StreamAnswer.Value
        assertEquals("a", first.value)
        val urgent = async { stream.next(first.revision) }
        runCurrent()
        stream.publish("b", urgent = true)
        val second = urgent.await() as StreamAnswer.Value
        assertEquals("b", second.value)
        assertEquals(0L, testScheduler.currentTime)
        stream.publish("c", urgent = false)
        stream.publish("d", urgent = false)
        val capped = stream.next(second.revision) as StreamAnswer.Value
        assertEquals("d", capped.value)
        assertEquals(1_000L, testScheduler.currentTime)
        stream.publish("d", urgent = true)
        assertEquals(capped.revision, (stream.next(null) as StreamAnswer.Value).revision)
    }
}
