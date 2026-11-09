package app.powerlog.application.health

import app.powerlog.application.api.HealthView
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.RideRecord
import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ride.RidePhase

internal object HealthViews {
    fun of(record: RideRecord, provider: HealthProviderKind?, readback: List<HealthWork>?): HealthView? {
        provider ?: return null
        val ride = record.ride
        val health = record.health
        val shown =
            provider == HealthProviderKind.healthConnect && ride.phase != RidePhase.failed && ride.phase !in openPhases
        val components =
            when {
                !shown -> emptyList()
                ride.options.healthWriter == null -> listOfNotNull((readback?.let(::readbackPart) ?: stored(record)))
                record.publish.none(::ran) -> emptyList()
                else ->
                    listOf(ComponentResult(HealthComponent.workout, outcomeState(health.outcome), health.reason)) +
                        listOfNotNull((readback?.let(::readbackPart) ?: stored(record))) +
                        record.publish.mapNotNull { row ->
                            val component = (row.scope as? HealthWorkScope.Publish)?.component
                            if (component == null || component in parts) null
                            else ComponentResult(component, row.state, row.reason).takeIf(::others)
                        }
            }
        return HealthView(health.provider, ride.options.healthWriter, health.outcome, health.reason, components)
    }

    private fun stored(record: RideRecord): ComponentResult? =
        record.publish
            .firstOrNull { it.scope == HealthWorkScope.Publish(HealthComponent.readback) }
            ?.let { ComponentResult(HealthComponent.readback, it.state, it.reason) }

    fun readbackPart(rows: List<HealthWork>): ComponentResult? {
        if (rows.isEmpty()) return null
        val open = rows.any { it.state == ComponentState.pending || it.state == ComponentState.submitted }
        if (open) return ComponentResult(HealthComponent.readback, ComponentState.pending, null)
        if (rows.all { it.state == ComponentState.notRequested })
            return ComponentResult(HealthComponent.readback, ComponentState.notRequested, null)
        val reason =
            when {
                rows.any { it.reason == HealthReason.permission } -> HealthReason.permission
                rows.any { it.state == ComponentState.unavailable || it.state == ComponentState.failed } ->
                    HealthReason.readbackUnavailable
                else -> return ComponentResult(HealthComponent.readback, ComponentState.saved, null)
            }
        return ComponentResult(HealthComponent.readback, ComponentState.unavailable, reason)
    }

    private fun ran(row: HealthWork): Boolean =
        row.state != ComponentState.pending || row.attempts > 0 || row.cursor != null

    private fun others(result: ComponentResult): Boolean =
        result.state == ComponentState.saved ||
            result.state == ComponentState.failed ||
            result.state == ComponentState.unavailable && result.reason == HealthReason.permission

    private fun outcomeState(outcome: HealthOutcome): ComponentState =
        when (outcome) {
            HealthOutcome.saved -> ComponentState.saved
            HealthOutcome.pending -> ComponentState.pending
            HealthOutcome.unavailable -> ComponentState.unavailable
            HealthOutcome.notRequested,
            HealthOutcome.discarded -> ComponentState.notRequested
            HealthOutcome.failed,
            HealthOutcome.notSaved,
            HealthOutcome.unknown -> ComponentState.failed
        }

    private val parts = setOf(HealthComponent.workout, HealthComponent.readback)
    private val openPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)
}
