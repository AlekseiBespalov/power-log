package app.powerlog.core.presentation

import app.powerlog.core.protocol.StartWindow
import app.powerlog.core.protocol.Str
import app.powerlog.core.protocol.uuid

internal fun presentationId(value: String): String = uuid(Str(value))

enum class RideAction {
    start,
    pause,
    resume,
    lap,
    stop,
    stopUnconfirmed,
    discard,
    recover,
}

enum class RideActionSurface {
    rider,
    ownerCommand,
    recovery,
}

data class FinishConfirmation(val rideId: String)

data class ActionFlight(val commandId: String, val rideId: String?, val action: RideAction)

data class ActionRequest
@Throws(IllegalArgumentException::class)
constructor(
    val commandId: String,
    val rideId: String?,
    val action: RideAction,
    val confirmation: FinishConfirmation? = null,
) {
    init {
        require(commandId.isNotBlank())
    }
}

data class ActionPresentationState(val pending: ActionFlight? = null, val error: String? = null)

data class ActionDecision(
    val next: ActionPresentationState,
    val admitted: Boolean,
    val error: String?,
    val haptic: String,
)

class ExternalControlContext
@Throws(IllegalArgumentException::class)
constructor(rideId: String?, controlToken: String, val phase: String, val pendingAction: String?) {
    val rideId: String? = rideId?.let(::presentationId)
    val controlToken: String = presentationId(controlToken)
}

class ExternalControlRequest
@Throws(IllegalArgumentException::class)
constructor(rideId: String, controlToken: String, val expectedPhase: String, val action: String) {
    val rideId: String = presentationId(rideId)
    val controlToken: String = presentationId(controlToken)
}

data class ExternalControlDecision(val outcome: String, val command: String?, val error: String?)

enum class PresentationWait {
    start,
    recovery,
    handoff,
}

data class WaitPresentation(val outcome: String, val pending: Boolean, val error: String?)

object PresentationTimeouts {
    const val startUs = StartWindow.deadlineUs
    const val recoveryUs = 15_000_000L

    @Throws(IllegalArgumentException::class)
    fun deadline(kind: PresentationWait, beganAtUs: Long): Long {
        val limit = if (kind == PresentationWait.recovery) recoveryUs else startUs
        require(beganAtUs >= 0 && beganAtUs <= Long.MAX_VALUE - limit)
        return beganAtUs + limit
    }

    @Throws(IllegalArgumentException::class)
    fun evaluate(kind: PresentationWait, beganAtUs: Long, nowUs: Long): WaitPresentation {
        require(beganAtUs >= 0 && nowUs >= 0)
        val age = nowUs - beganAtUs
        val limit = if (kind == PresentationWait.recovery) recoveryUs else startUs
        if (age < 0) return WaitPresentation("unresolved", false, RideFailure.unknown.name)
        if (age < limit) return WaitPresentation("pending", true, null)
        return when (kind) {
            PresentationWait.start -> WaitPresentation("unconfirmed", false, RideFailure.startUnconfirmed.name)
            PresentationWait.recovery -> WaitPresentation("unresolved", false, RideFailure.ownerUnconfirmed.name)
            PresentationWait.handoff -> WaitPresentation("expired", false, null)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun beginHandoff(
        beganAtUs: Long?,
        nowUs: Long,
        phase: String,
        hasRide: Boolean,
        canStart: Boolean,
        hasIssue: Boolean,
    ): Long? {
        require(nowUs >= 0 && (beganAtUs == null || beganAtUs >= 0))
        if (beganAtUs != null && evaluate(PresentationWait.handoff, beganAtUs, nowUs).pending) return beganAtUs
        return if (phase == "ready" && !hasRide && canStart && !hasIssue) nowUs else null
    }
}
