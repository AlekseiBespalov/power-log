package app.powerlog.kit

import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthTextsTest {
    private val apple = HealthProviderKind.appleHealth
    private val saved = RideHealth(apple, HealthOutcome.saved, null, "workout")

    private fun result(component: HealthComponent, state: ComponentState, reason: HealthReason? = null) =
        ComponentResult(component, state, reason)

    @Test
    fun everyOutcomeReadsAsTodayForEitherProvider() {
        for (provider in HealthProviderKind.entries) {
            val name = Texts.provider(provider)
            val labels =
                mapOf(
                    HealthOutcome.saved to "$name saved",
                    HealthOutcome.notRequested to "Saved only in Power Log",
                    HealthOutcome.pending to "$name save pending",
                    HealthOutcome.failed to "$name not saved",
                    HealthOutcome.notSaved to "$name not saved",
                    HealthOutcome.unavailable to "$name unavailable",
                    HealthOutcome.unknown to "$name save unconfirmed",
                    HealthOutcome.discarded to "$name discarded",
                )
            for ((outcome, label) in labels) {
                val health = RideHealth(provider, outcome, null, null)
                assertEquals(label, Texts.health(health))
                assertEquals(label, Texts.historyHealthLine(health, emptyList()))
            }
        }
        assertEquals("Apple Health save pending", Texts.health(RideHealth(null, HealthOutcome.pending, null, null)))
    }

    @Test
    fun anUnsavedOutcomeGivesItsReasonApartFromTheLabel() {
        val lost = RideHealth(apple, HealthOutcome.failed, HealthReason.builderLost, null)
        assertEquals("Apple Health not saved", Texts.health(lost))
        assertEquals(
            "The Apple Health workout session ended before it could be saved. Your ride is kept in Power Log.",
            Texts.healthReason(lost),
        )
        assertEquals(
            "Apple Health not saved · The Apple Health workout session ended before it could be saved. " +
                "Your ride is kept in Power Log.",
            Texts.historyHealthLine(lost, emptyList()),
        )
        assertEquals("Apple Health saved", Texts.health(saved.copy(reason = HealthReason.ambiguous)))
        assertNull(Texts.healthReason(saved.copy(reason = HealthReason.ambiguous)))
        assertNull(Texts.healthReason(RideHealth(apple, HealthOutcome.failed, null, null)))
        val clock = RideHealth(HealthProviderKind.healthConnect, HealthOutcome.unavailable, HealthReason.clock, null)
        assertEquals("Health Connect unavailable", Texts.health(clock))
        assertEquals(
            "Health can’t save this ride because its finish time isn’t after its start time. " +
                "Your ride is kept in Power Log.",
            Texts.healthReason(clock),
        )
    }

    @Test
    fun componentLinesNameEachUnsavedPartWithItsReason() {
        val results =
            listOf(
                result(HealthComponent.workout, ComponentState.saved),
                result(HealthComponent.route, ComponentState.notRequested),
                result(HealthComponent.riderPower, ComponentState.failed, HealthReason.transientFailure),
                result(HealthComponent.readback, ComponentState.submitted),
                result(HealthComponent.cyclingSpeed, ComponentState.unavailable, HealthReason.permission),
            )
        assertEquals(
            listOf(
                "Rider power not saved: Health Connect could not confirm this request. Your local ride is available.",
                "Health measurements pending",
                "Cycling speed unavailable: Allow Power Log to save workouts in Health Connect permissions.",
            ),
            Texts.healthComponents(results, HealthProviderKind.healthConnect),
        )
        assertEquals(emptyList(), Texts.healthComponents(results, null))
    }

    @Test
    fun countsSavedAndPendingComponentsWithoutCountingHealthOffItems() {
        val pending = RideHealth(apple, HealthOutcome.pending, null, null)
        val results =
            HealthComponent.entries.mapIndexed { index, component ->
                result(component, if (index < 4) ComponentState.saved else ComponentState.pending)
            }
        assertEquals("Apple Health save pending · 4 of 9 saved", Texts.historyHealthLine(pending, results))
        assertEquals(
            "Apple Health save pending · 4 of 9 saved",
            Texts.historyHealthLine(pending, results + result(HealthComponent.route, ComponentState.notRequested)),
        )
    }

    @Test
    fun keepsWorkoutSavingIndependentOfComponentSaving() {
        val results = listOf(result(HealthComponent.readback, ComponentState.pending))
        assertEquals("Apple Health saved · 0 of 1 saved · 1 item pending", Texts.historyHealthLine(saved, results))
        assertEquals("Apple Health saved", Texts.health(saved))
        assertEquals(listOf("Health measurements pending"), Texts.healthComponents(results, apple))
        val all =
            HealthComponent.entries.map { result(it, ComponentState.saved) } +
                result(HealthComponent.route, ComponentState.notRequested)
        assertEquals("Apple Health saved", Texts.historyHealthLine(saved, all))
    }

    @Test
    fun countsPermissionItemsIndependentlyOfTheirPublicationState() {
        for (provider in HealthProviderKind.entries) {
            val health = saved.copy(provider = provider)
            val name = Texts.provider(provider)
            val results =
                listOf(
                    result(HealthComponent.route, ComponentState.unavailable, HealthReason.permission),
                    result(HealthComponent.riderPower, ComponentState.failed, HealthReason.permission),
                    result(HealthComponent.readback, ComponentState.pending, HealthReason.permission),
                    result(HealthComponent.workout, ComponentState.saved, HealthReason.permission),
                    result(HealthComponent.laps, ComponentState.notRequested, HealthReason.permission),
                )
            assertEquals("$name saved · 3 items waiting for permission", Texts.historyHealthLine(health, results))
            assertEquals(
                "$name saved · 1 item waiting for permission: Allow Power Log to save workouts in $name permissions.",
                Texts.historyHealthLine(health, results.take(1)),
            )
        }
    }

    @Test
    fun countsFailedAndUnavailableItemsAndKeepsASingleReason() {
        val cases =
            listOf(
                Triple(
                    ComponentState.failed,
                    "not saved",
                    "Apple Health could not confirm this request. Your local ride is available.",
                ) to HealthReason.transientFailure,
                Triple(ComponentState.unavailable, "unavailable", "No usable reading was recorded for this.") to
                    HealthReason.noValidReading,
            )
        for ((case, reason) in cases) {
            val (state, label, explanation) = case
            val results =
                listOf(result(HealthComponent.route, state, reason), result(HealthComponent.laps, state, reason))
            assertEquals("Apple Health saved · 2 items $label", Texts.historyHealthLine(saved, results))
            assertEquals(listOf("Route $label: $explanation"), Texts.healthComponents(results.take(1), apple))
            assertEquals(
                "Apple Health saved · 1 item $label: $explanation",
                Texts.historyHealthLine(saved, results.take(1)),
            )
        }
    }

    @Test
    fun summarizesMixedProgressAndIssuesWithoutRepeatingComponentReasons() {
        val pending = RideHealth(apple, HealthOutcome.pending, null, null)
        assertEquals(
            "Apple Health save pending · 1 of 4 saved · 1 item waiting for permission · 1 item not saved",
            Texts.historyHealthLine(
                pending,
                listOf(
                    result(HealthComponent.workout, ComponentState.saved),
                    result(HealthComponent.route, ComponentState.pending),
                    result(HealthComponent.laps, ComponentState.failed, HealthReason.transientFailure),
                    result(HealthComponent.riderPower, ComponentState.unavailable, HealthReason.permission),
                ),
            ),
        )
        assertEquals(
            "Apple Health save pending · 1 of 3 saved · 1 item not saved: Apple Health could not confirm this " +
                "request. Your local ride is available.",
            Texts.historyHealthLine(
                pending,
                listOf(
                    result(HealthComponent.workout, ComponentState.saved),
                    result(HealthComponent.route, ComponentState.pending),
                    result(HealthComponent.riderPower, ComponentState.failed, HealthReason.transientFailure),
                ),
            ),
        )
        assertEquals(
            "Apple Health save pending · 0 of 1 saved · 1 item pending: Apple Health is locked. Unlock the device " +
                "to allow Health access.",
            Texts.historyHealthLine(
                pending,
                listOf(result(HealthComponent.route, ComponentState.pending, HealthReason.locked)),
            ),
        )
    }

    @Test
    fun keepsTheRideReasonWhenNoComponentIsOutstanding() {
        val ambiguous = RideHealth(apple, HealthOutcome.unknown, HealthReason.ambiguous, null)
        assertEquals(
            "Apple Health save unconfirmed · The matching Apple Health record could not be confirmed.",
            Texts.historyHealthLine(ambiguous, emptyList()),
        )
    }
}
