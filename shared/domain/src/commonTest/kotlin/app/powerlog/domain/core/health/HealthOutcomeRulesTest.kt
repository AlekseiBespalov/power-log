package app.powerlog.domain.core.health

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthOutcomeRulesTest {
    private fun options(useWatch: Boolean, saveToHealth: Boolean) =
        RideOptions(
            SampleRate.hz2,
            false,
            useWatch,
            saveToHealth,
            true,
            DistanceSource.phoneGps,
            BatteryMethod.controller,
        )

    @Test
    fun aWatchWrittenRideIsPendingWithNoPublishRows() {
        val options = options(useWatch = true, saveToHealth = true)
        assertEquals(
            RideHealth(HealthProviderKind.appleHealth, HealthOutcome.pending, null, null),
            HealthOutcomeRules.initial(options, HealthProviderKind.appleHealth),
        )
        assertEquals(emptyList(), HealthOutcomeRules.initialComponents(options))
    }

    @Test
    fun aRideWithoutSaveToHealthIsNotRequested() {
        val options = options(useWatch = false, saveToHealth = false)
        assertEquals(
            RideHealth(null, HealthOutcome.notRequested, null, null),
            HealthOutcomeRules.initial(options, null),
        )
        assertEquals(emptyList(), HealthOutcomeRules.initialComponents(options))
    }

    @Test
    fun thePhoneWriterGetsOnePendingRowPerComponent() {
        val rows = HealthOutcomeRules.initialComponents(options(useWatch = false, saveToHealth = true))
        assertEquals(HealthComponent.entries.map { ComponentResult(it, ComponentState.pending, null) }, rows)
    }

    private fun health(outcome: HealthOutcome, reason: HealthReason? = null, workoutId: String? = null) =
        RideHealth(HealthProviderKind.appleHealth, outcome, reason, workoutId)

    @Test
    fun theNewerObservationWinsUntilAnOutcomeIsFinal() {
        val pending = health(HealthOutcome.pending)
        val saved = health(HealthOutcome.saved, workoutId = "w")
        assertEquals(saved, HealthOutcomeRules.next(pending, saved))
        assertEquals(pending, HealthOutcomeRules.next(saved, pending))
        assertNull(HealthOutcomeRules.next(saved, saved))
        val failed = health(HealthOutcome.failed, HealthReason.builderLost)
        assertEquals(failed, HealthOutcomeRules.next(pending, failed))
        assertEquals(
            health(HealthOutcome.unknown, HealthReason.ambiguous),
            HealthOutcomeRules.next(failed, health(HealthOutcome.unknown, HealthReason.ambiguous)),
        )
    }

    @Test
    fun unavailableIsStickyWithItsReason() {
        val unavailable = health(HealthOutcome.unavailable, HealthReason.clock)
        assertNull(HealthOutcomeRules.next(unavailable, health(HealthOutcome.saved)))
        assertNull(HealthOutcomeRules.next(unavailable, health(HealthOutcome.unavailable, HealthReason.permission)))
        assertNull(HealthOutcomeRules.next(unavailable, unavailable))
        val noted = unavailable.copy(workoutId = "w")
        assertEquals(noted, HealthOutcomeRules.next(unavailable, noted))
    }

    @Test
    fun notRequestedOnlyBecomesDiscardedAndDiscardedIsTerminal() {
        val off = RideHealth(null, HealthOutcome.notRequested, null, null)
        assertNull(HealthOutcomeRules.next(off, health(HealthOutcome.pending)))
        assertNull(HealthOutcomeRules.next(off, health(HealthOutcome.saved)))
        val discarded = health(HealthOutcome.discarded)
        assertEquals(discarded, HealthOutcomeRules.next(off, discarded))
        for (outcome in HealthOutcome.entries) assertNull(
            HealthOutcomeRules.next(discarded, health(outcome)),
            "$outcome",
        )
    }
}
