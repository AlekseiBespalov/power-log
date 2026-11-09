package app.powerlog.domain.recording

import app.powerlog.domain.core.ride.RidePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class SourcesTest {
    private fun state(
        kind: SourceKind,
        ageUs: Long?,
        accuracyM: Double? = null,
        reported: SourceState? = null,
        requested: Boolean = true,
        phase: RidePhase? = RidePhase.running,
    ) = Sources.state(kind, requested, phase, ageUs, accuracyM, reported)

    @Test
    fun theControllerAndHeartRateTurnStaleAtTheirHolds() {
        assertEquals(SourceState.off, state(SourceKind.controller, 1, requested = false))
        assertEquals(SourceState.waiting, state(SourceKind.controller, null))
        assertEquals(SourceState.paused, state(SourceKind.controller, 20_000_000, phase = RidePhase.paused))
        assertEquals(SourceState.receiving, state(SourceKind.controller, 5_999_999))
        assertEquals(SourceState.stale, state(SourceKind.controller, 6_000_000))
        assertEquals(SourceState.receiving, state(SourceKind.heartRate, 14_999_999))
        assertEquals(SourceState.stale, state(SourceKind.heartRate, 15_000_000))
        assertEquals(SourceState.receiving, state(SourceKind.controller, 1, phase = null))
    }

    @Test
    fun gpsFollowsItsAccuracyAndAge() {
        assertEquals(SourceState.unavailable, state(SourceKind.gps, 1_000_000, accuracyM = null))
        assertEquals(SourceState.unavailable, state(SourceKind.gps, 1_000_000, accuracyM = -1.0))
        assertEquals(SourceState.weak, state(SourceKind.gps, 1_000_000, accuracyM = 60.0))
        assertEquals(SourceState.receiving, state(SourceKind.gps, 1_000_000, accuracyM = 20.0))
        assertEquals(SourceState.receiving, state(SourceKind.gps, 1_000_000, accuracyM = 50.0))
        assertEquals(SourceState.stale, state(SourceKind.gps, 11_000_000, accuracyM = 20.0))
        assertEquals(SourceState.stale, state(SourceKind.gps, 10_000_000, accuracyM = 60.0))
        assertEquals(SourceState.unavailable, state(SourceKind.gps, 11_000_000, accuracyM = null))
    }

    @Test
    fun aReportedStateWinsUnlessItCanAge() {
        assertEquals(SourceState.denied, state(SourceKind.gps, 20_000_000, reported = SourceState.denied))
        assertEquals(SourceState.backfilled, state(SourceKind.heartRate, 20_000_000, reported = SourceState.backfilled))
        assertEquals(SourceState.waiting, state(SourceKind.gps, null, reported = SourceState.weak))
        assertEquals(SourceState.stale, state(SourceKind.gps, 10_000_000, reported = SourceState.weak))
    }
}

class FreshnessPolicyTest {
    @Test
    fun aReadingIsFreshFromItsAcquisitionUntilTheWindowEnds() {
        kotlin.test.assertTrue(FreshnessPolicy.fresh(10_000_000, 10_000_000, 6_000_000))
        kotlin.test.assertTrue(FreshnessPolicy.fresh(10_000_000, 15_999_999, 6_000_000))
        kotlin.test.assertFalse(FreshnessPolicy.fresh(10_000_000, 16_000_000, 6_000_000))
        kotlin.test.assertFalse(FreshnessPolicy.fresh(10_000_000, 9_999_999, 6_000_000))
        kotlin.test.assertFalse(FreshnessPolicy.fresh(null, 10_000_000, 6_000_000))
    }

    @Test
    fun theRideCardExpiresEachSourceItsHoldPlusTheRefreshAfterAcquisition() {
        val now = app.powerlog.domain.core.time.ClockReading(105_000_000, 1_788_706_205_000_000)
        assertEquals(
            1_788_706_221_000_000,
            FreshnessPolicy.rideCardFreshUntilUtcUs(SourceKind.controller, 100_000_000, now),
        )
        assertEquals(
            1_788_706_230_000_000,
            FreshnessPolicy.rideCardFreshUntilUtcUs(SourceKind.heartRate, 100_000_000, now),
        )
        assertEquals(1_788_706_225_000_000, FreshnessPolicy.rideCardFreshUntilUtcUs(SourceKind.gps, 100_000_000, now))
    }
}
