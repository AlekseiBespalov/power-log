package app.powerlog.kit

import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth

internal object HealthTexts {
    private const val PENDING = "pending"

    fun provider(provider: HealthProviderKind): String =
        when (provider) {
            HealthProviderKind.appleHealth -> "Apple Health"
            HealthProviderKind.healthConnect -> "Health Connect"
        }

    private fun named(provider: HealthProviderKind?): String = provider(provider ?: HealthProviderKind.appleHealth)

    private fun outcome(outcome: HealthOutcome): String =
        when (outcome) {
            HealthOutcome.pending -> "save pending"
            HealthOutcome.saved -> "saved"
            HealthOutcome.failed,
            HealthOutcome.notSaved -> "not saved"
            HealthOutcome.unavailable -> "unavailable"
            HealthOutcome.unknown -> "save unconfirmed"
            HealthOutcome.discarded -> "discarded"
            HealthOutcome.notRequested -> "not requested"
        }

    private fun component(component: HealthComponent): String =
        when (component) {
            HealthComponent.workout -> "Workout"
            HealthComponent.route -> "Route"
            HealthComponent.laps -> "Laps"
            HealthComponent.riderPower -> "Rider power"
            HealthComponent.cyclingCadence -> "Cycling cadence"
            HealthComponent.cyclingSpeed -> "Cycling speed"
            HealthComponent.cyclingDistance -> "Cycling distance"
            HealthComponent.association -> "Workout links"
            HealthComponent.readback -> "Health measurements"
        }

    private fun state(state: ComponentState): String =
        when (state) {
            ComponentState.pending,
            ComponentState.submitted -> PENDING
            ComponentState.saved -> "saved"
            ComponentState.notRequested -> "not requested"
            ComponentState.unavailable -> "unavailable"
            ComponentState.failed -> "not saved"
        }

    private fun reason(reason: HealthReason?, provider: String): String? =
        when (reason) {
            null -> null
            HealthReason.permission -> "Allow Power Log to save workouts in $provider permissions."
            HealthReason.locked -> "$provider is locked. Unlock the device to allow Health access."
            HealthReason.quota -> "$provider is temporarily limiting requests."
            HealthReason.invalid -> "This reading can’t be saved to $provider. It stays in Power Log."
            HealthReason.ambiguous -> "The matching $provider record could not be confirmed."
            HealthReason.builderLost ->
                "The $provider workout session ended before it could be saved. Your ride is kept in Power Log."
            HealthReason.readbackUnavailable ->
                "Power Log couldn’t read measurements from $provider. Your ride is kept in Power Log."
            HealthReason.noValidReading -> "No usable reading was recorded for this."
            HealthReason.transientFailure -> "$provider could not confirm this request. Your local ride is available."
            HealthReason.clock ->
                "Health can’t save this ride because its finish time isn’t after its start time. " +
                    "Your ride is kept in Power Log."
        }

    private fun label(health: RideHealth): String =
        if (health.outcome == HealthOutcome.notRequested) "Saved only in Power Log"
        else "${named(health.provider)} ${outcome(health.outcome)}"

    fun health(health: RideHealth): String = label(health)

    fun reason(health: RideHealth): String? =
        if (health.outcome == HealthOutcome.saved) null else reason(health.reason, named(health.provider))

    fun components(results: List<ComponentResult>, provider: HealthProviderKind?): List<String> {
        if (provider == null) return emptyList()
        val name = provider(provider)
        return results
            .filter { it.state != ComponentState.saved && it.state != ComponentState.notRequested }
            .map { result ->
                "${component(result.component)} ${state(result.state)}" +
                    (reason(result.reason, name)?.let { ": $it" } ?: "")
            }
    }

    private class Group(val results: List<ComponentResult>, val label: String)

    fun historyLine(health: RideHealth, results: List<ComponentResult>): String {
        val parts = mutableListOf(label(health))
        if (health.outcome != HealthOutcome.notRequested) {
            val provider = named(health.provider)
            val requested = results.filter { it.state != ComponentState.notRequested }
            val unsaved = requested.filter { it.state != ComponentState.saved }
            val pending = unsaved.filter { state(it.state) == PENDING && it.reason != HealthReason.permission }
            if (pending.isNotEmpty()) parts += "${requested.size - unsaved.size} of ${requested.size} saved"
            val groups =
                mutableListOf(
                    Group(unsaved.filter { it.reason == HealthReason.permission }, "waiting for permission"),
                    Group(
                        unsaved.filter { it.state == ComponentState.failed && it.reason != HealthReason.permission },
                        state(ComponentState.failed),
                    ),
                    Group(
                        unsaved.filter {
                            it.state == ComponentState.unavailable && it.reason != HealthReason.permission
                        },
                        state(ComponentState.unavailable),
                    ),
                )
            val issues = groups.sumOf { it.results.size }
            if (
                pending.isNotEmpty() &&
                    (health.outcome != HealthOutcome.pending || unsaved.size == 1 && pending[0].reason != null)
            )
                groups += Group(pending, PENDING)
            for (group in groups) {
                if (group.results.isEmpty()) continue
                val count = group.results.size
                val onlyIssue = group.label != PENDING && issues == 1
                val reason = if (onlyIssue || unsaved.size == 1) reason(group.results[0].reason, provider) else null
                parts +=
                    "$count ${if (count == 1) "item" else "items"} ${group.label}" + (reason?.let { ": $it" } ?: "")
            }
            val reason = reason(health.reason, provider)
            if (unsaved.isEmpty() && reason != null && health.outcome != HealthOutcome.saved) parts += reason
        }
        return parts.joinToString(" · ")
    }
}
