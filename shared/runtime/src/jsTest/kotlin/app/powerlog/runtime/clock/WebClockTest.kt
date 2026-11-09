package app.powerlog.runtime.clock

import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertTrue

class WebClockTest {
    private val clock = WebClock()

    @Test
    fun theMonotonicClockIsPerformanceNowInMicrosecondsAndNeverDecreases() {
        var previous = clock.monoUs()
        repeat(1_000) {
            val next = clock.monoUs()
            assertTrue(next >= previous, "$next went back from $previous")
            previous = next
        }
        assertTrue(previous > 0)
    }

    @Test
    fun aReadingTakesBothClocksBackToBack() {
        val monoBefore = clock.monoUs()
        val utcBefore = Date.now().toLong() * 1_000
        val reading = clock.read()
        val utcAfter = Date.now().toLong() * 1_000
        assertTrue(reading.utcUs in utcBefore..utcAfter, "${reading.utcUs} outside $utcBefore..$utcAfter")
        assertTrue(reading.monoUs >= monoBefore)
        assertTrue(reading.monoUs - monoBefore < 1_000_000)
    }
}
