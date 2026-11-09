package app.powerlog.application.fakes

import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeClockTest {
    private val clock = FakeClock(monoUs = 10, utcUs = 1_000)

    @Test
    fun theMonotonicClockNeverDecreasesWhileTheWallClockMayJump() {
        clock.advance(5)
        clock.setUtc(500)
        clock.advanceTo(40)

        assertEquals(40, clock.monoUs())
        assertEquals(525, clock.utcUs())
        assertFailsWith<IllegalArgumentException> { clock.advanceTo(39) }
        assertEquals(40, clock.monoUs())
    }

    @Test
    fun readGivesBothClocksBackToBack() {
        clock.advance(1)

        assertEquals(ClockReading(monoUs = clock.monoUs(), utcUs = clock.utcUs()), clock.read())
    }
}
