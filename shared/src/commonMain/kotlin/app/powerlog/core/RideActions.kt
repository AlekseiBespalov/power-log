package app.powerlog.core

import app.powerlog.core.presentation.*

object RideActions {
    fun rejection(action: String, phase: String?): String? = rejection(action, phase, RideActionSurface.ownerCommand)

    fun rejection(action: String, phase: String?, surface: RideActionSurface): String? =
        when (action) {
            "start" ->
                if (surface == RideActionSurface.recovery) RideFailure.actionUnavailable.name
                else RideStarts.rejection(phase, RideStartContext())
            "pause" -> if (phase == "running") null else RideFailure.actionUnavailable.name
            "resume" -> if (phase == "paused") null else RideFailure.actionUnavailable.name
            "lap" ->
                if (phase == "running" || surface == RideActionSurface.ownerCommand && phase == "paused") null
                else RideFailure.actionUnavailable.name
            "stop",
            "discard",
            "stopUnconfirmed" ->
                if (phase in setOf("preparing", "running", "paused", "interrupted", "finishing")) null
                else RideFailure.actionUnavailable.name
            else -> RideFailure.actionUnavailable.name
        }

    fun admit(
        state: ActionPresentationState,
        request: ActionRequest,
        phase: String,
        currentRideId: String?,
        foreignOwner: Boolean = false,
        nativePendingAction: String? = null,
        startContext: RideStartContext = RideStartContext(),
        surface: RideActionSurface = RideActionSurface.rider,
        adapterBusy: Boolean = false,
    ): ActionDecision {
        val activeRide = phase in setOf("running", "paused", "interrupted")
        val activeStop = activeRide && request.action in setOf(RideAction.stop, RideAction.stopUnconfirmed)
        val reason =
            when {
                nativePendingAction == "discard" &&
                    request.action in setOf(RideAction.stop, RideAction.stopUnconfirmed) ->
                    RideFailure.discardPending.name
                state.pending != null ||
                    adapterBusy ||
                    (nativePendingAction != null &&
                        !activeStop &&
                        request.action !in setOf(RideAction.stop, RideAction.stopUnconfirmed, RideAction.discard)) ->
                    RideFailure.actionPending.name
                foreignOwner -> RideFailure.ownerForeign.name
                request.action != RideAction.start && (currentRideId == null || request.rideId != currentRideId) ->
                    RideFailure.rideChanged.name
                request.action in setOf(RideAction.stop, RideAction.stopUnconfirmed, RideAction.discard) &&
                    activeRide &&
                    request.confirmation?.rideId != currentRideId -> RideFailure.finishConfirmationRequired.name
                request.action == RideAction.start -> RideStarts.rejection(phase, startContext)
                else ->
                    rejection(
                        request.action.name,
                        phase,
                        surface,
                    )
            }
        if (reason != null) return ActionDecision(state, false, reason, "none")
        return ActionDecision(
            ActionPresentationState(ActionFlight(request.commandId, request.rideId, request.action), null),
            true,
            null,
            "none",
        )
    }

    fun recording(phase: String): Boolean = phase in setOf("preparing", "running", "paused")

    fun recoveryStopOffered(phase: String, pendingAction: String?): Boolean =
        pendingAction != "discard" &&
            (phase in setOf("preparing", "interrupted", "finishing") ||
                pendingAction != null && phase !in setOf("running", "paused"))

    fun complete(state: ActionPresentationState, commandId: String, rideId: String?, error: String?): ActionDecision {
        val pending = state.pending
        if (pending == null || pending.commandId != commandId || pending.rideId != rideId)
            return ActionDecision(state, false, null, "none")
        val haptic =
            if (error != null) "warning"
            else
                when (pending.action) {
                    RideAction.start,
                    RideAction.stop -> "success"
                    RideAction.discard -> "warning"
                    RideAction.pause,
                    RideAction.resume -> "impact"
                    RideAction.lap -> "lightImpact"
                    RideAction.recover,
                    RideAction.stopUnconfirmed -> "none"
                }
        val failure = error?.let { Failures.present(it).name }
        return ActionDecision(ActionPresentationState(null, failure), true, failure, haptic)
    }

    fun external(
        current: ExternalControlContext,
        request: ExternalControlRequest,
        confirmed: Boolean,
    ): ExternalControlDecision {
        val action = request.action
        if (
            current.rideId != request.rideId ||
                current.controlToken != request.controlToken ||
                current.phase != request.expectedPhase ||
                current.pendingAction != null ||
                action !in setOf("pause", "resume", "finish") ||
                (action in setOf("pause", "resume") && rejection(action, current.phase) != null) ||
                (action == "finish" && current.phase !in setOf("running", "paused"))
        )
            return ExternalControlDecision("rejected", null, RideFailure.rideChanged.name)
        if (action == "finish" && !confirmed) return ExternalControlDecision("confirmationRequired", null, null)
        return ExternalControlDecision("admitted", if (action == "finish") "stop" else action, null)
    }
}
