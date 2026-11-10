package app.powerlog.domain.recording

import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncBeepsTest {
    private val second = 1_000_000L
    private val utc = 1_788_706_200_000_000L

    private fun press(offsetUs: Long) = ClockReading(50_000_000 + offsetUs, utc + offsetUs)

    @Test
    fun theStartBeepIsTheFirstWholeUtcSecondAtLeastThreeAndAHalfSecondsAway() {
        assertEquals(ClockReading(50_000_000 + 4 * second, utc + 4 * second), SyncBeeps.startAt(press(0)))
        assertEquals(ClockReading(50_000_000 + 4 * second, utc + 4 * second), SyncBeeps.startAt(press(500_000)))
        assertEquals(ClockReading(50_000_000 + 5 * second, utc + 5 * second), SyncBeeps.startAt(press(999_000)))
    }

    @Test
    fun resumeAndFinishBeepsAreTheFirstWholeRideSecondAtLeastOneSecondAway() {
        val origin = 7_123_456L
        val atTen = ClockReading(origin + 10 * second, utc)
        assertEquals(ClockReading(origin + 11 * second, utc + second), SyncBeeps.controlAt(atTen, origin))
        val later = ClockReading(origin + 10 * second + 1, utc)
        assertEquals(ClockReading(origin + 12 * second, utc + 2 * second - 1), SyncBeeps.controlAt(later, origin))
        val lateInSecond = ClockReading(origin + 10 * second + 999_999, utc)
        assertEquals(origin + 12 * second, SyncBeeps.controlAt(lateInSecond, origin).monoUs)
    }

    @Test
    fun onlyResumeFromPausedAndFinishFromRunningBeep() {
        assertTrue(SyncBeeps.cued(RidePhase.paused, RideAction.resume))
        assertTrue(SyncBeeps.cued(RidePhase.running, RideAction.finish))
        assertFalse(SyncBeeps.cued(RidePhase.paused, RideAction.finish))
        assertFalse(SyncBeeps.cued(RidePhase.interrupted, RideAction.finish))
        for (action in listOf(RideAction.pause, RideAction.lap, RideAction.discard)) {
            assertFalse(SyncBeeps.cued(RidePhase.running, action))
            assertFalse(SyncBeeps.cued(RidePhase.paused, action))
        }
    }

    @Test
    fun theFinishRequestLeadsTheBeepAndTheOthersMeetIt() {
        assertEquals(10 * second - 450_000, SyncBeeps.requestAt(CueAction.finish, 10 * second))
        assertEquals(10 * second, SyncBeeps.requestAt(CueAction.start, 10 * second))
        assertEquals(10 * second, SyncBeeps.requestAt(CueAction.resume, 10 * second))
    }

    @Test
    fun theStartCountsDownWithThreePipsBeforeTheSyncBeep() {
        val tones = SyncBeeps.startTones(20 * second)
        assertEquals(listOf(17 * second, 18 * second, 19 * second, 20 * second), tones.map { it.atMonoUs })
        assertEquals(listOf(4_800, 4_800, 4_800, 9_600), tones.map { it.samples.size })
        assertTrue(tones.all { it.sampleRateHz == 48_000 })
        assertSame(tones[0].samples, tones[2].samples)
        val sync = SyncBeeps.syncTones(20 * second).single()
        assertEquals(20 * second, sync.atMonoUs)
        assertSame(tones[3].samples, sync.samples)
    }

    @Test
    fun aToneStartsAndEndsSilentAndPeaksOneDecibelBelowFullScale() {
        val samples = SyncBeeps.pcm(2_000, 200_000)
        val level = 10.0.pow(-1.0 / 20).toFloat()
        assertEquals(0f, abs(samples.first()))
        assertEquals(0f, abs(samples.last()))
        assertTrue(abs(samples[6]) < 0.1f * level, "a crest inside the 1 ms attack is ramped: ${samples[6]}")
        assertTrue(abs(samples[54] - level) < 0.001f, "the first crest after the attack is full: ${samples[54]}")
        assertTrue(abs(samples.maxOf { abs(it) } - level) < 0.001f)
    }
}
