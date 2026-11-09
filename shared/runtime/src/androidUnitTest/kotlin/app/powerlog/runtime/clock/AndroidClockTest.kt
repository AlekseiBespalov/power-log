package app.powerlog.runtime.clock

import android.os.SystemClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidClockTest {
    private val clock = AndroidClock()

    @Test
    fun theMonotonicClockIsElapsedRealtimeInMicrosecondsAndNeverDecreases() {
        val before = clock.monoUs()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_500))
        val after = clock.monoUs()
        assertEquals(SystemClock.elapsedRealtimeNanos() / 1_000, after)
        assertTrue(after - before >= 1_500_000)
    }

    @Test
    fun aReadingTakesBothClocksBackToBack() {
        val utcBefore = System.currentTimeMillis() * 1_000
        val reading = clock.read()
        val utcAfter = System.currentTimeMillis() * 1_000
        assertTrue(reading.utcUs in utcBefore..utcAfter)
        assertEquals(clock.monoUs(), reading.monoUs)
    }
}
