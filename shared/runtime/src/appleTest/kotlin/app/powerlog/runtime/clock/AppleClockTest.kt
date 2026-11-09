package app.powerlog.runtime.clock

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock as SystemClock
import kotlin.time.ExperimentalTime

class AppleClockTest {
    private val clock = AppleClock()

    @Test
    fun theMonotonicClockNeverDecreases() {
        var previous = clock.monoUs()
        repeat(10_000) {
            val now = clock.monoUs()
            assertTrue(now >= previous)
            previous = now
        }
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun aReadingTakesBothClocksBackToBack() {
        val monoBefore = clock.monoUs()
        val utcBefore = SystemClock.System.now().toEpochMilliseconds() * 1_000
        val reading = clock.read()
        val monoAfter = clock.monoUs()
        val utcAfter = SystemClock.System.now().toEpochMilliseconds() * 1_000 + 1_000
        assertTrue(reading.monoUs in monoBefore..monoAfter)
        assertTrue(reading.utcUs in utcBefore..utcAfter)
    }
}
