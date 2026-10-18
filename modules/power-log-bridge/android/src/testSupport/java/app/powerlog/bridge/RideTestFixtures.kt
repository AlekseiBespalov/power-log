package app.powerlog.bridge

import app.powerlog.core.lifecycle.*
import app.powerlog.core.presentation.FrozenRideOptions
import app.powerlog.core.presentation.RideAction
import app.powerlog.core.protocol.OwnerSnapshot
import app.powerlog.core.protocol.OwnerTiming
import app.powerlog.core.protocol.Role
import app.powerlog.core.protocol.Timestamps
import java.util.UUID
import org.junit.Assert.*

internal fun RideStore.prepareLocalOwner(id: String) = transaction {
    val record = metadata(id)
    val timing = timing(id).let { OwnerTiming(Timestamps.parse(it.timestamp), it.elapsed, it.timer) }
    val phase = LifecyclePhase.valueOf(record.str("phase"))
    val state =
        LifecycleState(
            1,
            LifecycleRide(
                LifecycleKey(id, Role.phone, 1),
                FrozenRideOptions(
                    record.flag("indoor"),
                    false,
                    record.flag("saveToHealth"),
                    record.flag("recordGPS"),
                    (record["sampleHz"] as Number).toInt(),
                ),
                Timestamps.parse(record.str("startedAt")),
                phase,
                timing,
                "55555555-5555-4555-8555-555555555555",
                if (phase in setOf(LifecyclePhase.running, LifecyclePhase.paused)) elapsedUs(timing.elapsedSeconds)
                else null,
                if (phase == LifecyclePhase.running) elapsedUs(timing.elapsedSeconds) else null,
                OwnerSnapshot(
                    Role.phone,
                    1,
                    timing.timestamp,
                    phase.name,
                    if (record.flag("saveToHealth")) "pending" else "notRequested",
                    healthWorkoutID = null,
                    stopCutoff =
                        if (phase in setOf(LifecyclePhase.completed, LifecyclePhase.finishing)) timing.timestamp
                        else null,
                    timing = timing,
                    healthReason = null,
                ),
                ownerRecovery = LifecycleOwnerRecovery.local,
            ),
        )
    saveLifecycle(state)
}

internal fun RideStore.fixtureCheckpoint(id: String, timing: RideTiming) {
    val before = lifecycleState()
    val owner = requireNotNull(before.ride)
    require(owner.key.rideId == id)
    val at = OwnerTiming(Timestamps.parse(timing.timestamp), timing.elapsed, timing.timer)
    commitLifecycle(
        before,
        checkpointLifecycle(
            before,
            at,
            LifecycleClock(
                at.timestamp,
                owner.epoch,
                (owner.elapsedOriginUs ?: 0) + elapsedUs(timing.elapsed - owner.timing.elapsedSeconds),
            ),
        ),
    )
}

internal fun RideStore.fixtureOwnerCommand(id: String, timing: RideTiming, action: RideAction) {
    if (select("SELECT 1 FROM lifecycle_states WHERE ride=?", arrayOf(id)) { !it.moveToFirst() }) prepareLocalOwner(id)
    transaction { saveLifecycle(lifecycleState(id)) }
    var before = lifecycleState()
    if (action != RideAction.stop) {
        fixtureCheckpoint(id, timing)
        before = lifecycleState()
    }
    val current = before.ride!!
    val clock =
        LifecycleClock(
            Timestamps.parse(timing.timestamp),
            current.epoch,
            (current.elapsedOriginUs ?: 0) + elapsedUs(timing.elapsed - current.timing.elapsedSeconds),
        )
    val transition =
        requestLifecycle(
            before,
            LifecycleRequest(
                before.ride!!.key,
                UUID.randomUUID().toString(),
                action,
                clock,
                surface = app.powerlog.core.presentation.RideActionSurface.ownerCommand,
                ownerTiming =
                    if (action == RideAction.stop) OwnerTiming(clock.timestamp, timing.elapsed, timing.timer) else null,
            ),
        )
    val result = commitLifecycle(before, transition)
    result.effects.forEach { effect ->
        if (effect.intent.effect == LifecycleEffectKind.stopOwner) finishCollection(id)
        val state = lifecycleState()
        commitLifecycle(
            state,
            receiptLifecycle(
                state,
                LifecycleReceipt(effect.intent.key, effect.intent.commandId, effect.intent.effect, clock),
            ),
        )
    }
}

internal fun RideStore.transition(id: String, timing: RideTiming, action: String) =
    fixtureOwnerCommand(id, timing, RideAction.valueOf(action))

internal fun RideStore.seal(id: String, timing: RideTiming, interrupted: Boolean = false) {
    if (interrupted) fixtureOriginalEnd(id, timing, true)
    else if (metadata(id)["endedAt"] == null) fixtureOwnerCommand(id, timing, RideAction.stop) else finishCollection(id)
}

internal fun RideStore.fixtureOriginalEnd(id: String, timing: RideTiming, interrupted: Boolean = false) = transaction {
    lifecycle(id, timing, "stop", interrupted)
    update(id, "completed", timing, mapOf("endedAt" to timing.timestamp, "interrupted" to interrupted))
    finishCollection(id)
    prepareLocalOwner(id)
}

internal fun RideStore.savedHealthEvidence(id: String) {
    val components =
        listOf(
            app.powerlog.core.health.ComponentResult(
                app.powerlog.core.health.Component.workout,
                revision(id),
                true,
                app.powerlog.core.health.ComponentState.saved,
                null,
                listOf("generated-provider-parent"),
            )
        )
    healthJob(id)?.let {
        putHealthJob(it.copy(status = "saved", phase = "done", workoutId = "generated-provider-parent"))
    }
    healthStatus(id, HealthExportResult("saved", components = components))
}
