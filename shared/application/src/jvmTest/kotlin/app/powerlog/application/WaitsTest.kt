package app.powerlog.application

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class WaitsTest {
    @Test
    fun aWaitEndsOnTheAppClocksInstantEvenWhenTimersFireEarly() = runTest {
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000 / 2

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        Deadlines(clock).until(10_000)
        assertEquals(10_000, clock.monoUs())
        assertEquals(20L, testScheduler.currentTime)
    }

    @Test
    fun anyWakeRechecksAPassedInstant() = runTest {
        var jumpedUs = 0L
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000 + jumpedUs

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        val deadlines = Deadlines(clock)
        val waiting = async { deadlines.until(60_000_000) }
        delay(1_000)
        jumpedUs = 60_000_000
        deadlines.poke()
        waiting.await()
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun anAnswerEndsTheWaitWithItsValue() = runTest {
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        val answer = CompletableDeferred<String>()
        val waiting = async { Deadlines(clock).await(answer, 5_000_000) }
        delay(100)
        answer.complete("ok")
        assertEquals("ok", waiting.await())
        assertEquals(100L, testScheduler.currentTime)
    }
}
