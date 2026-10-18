package app.powerlog.bridge

import android.content.Intent
import android.net.Uri
import app.powerlog.core.RideActions
import app.powerlog.core.presentation.*

internal object RideNotificationAdapter {
    fun project(
        snapshot: RideSnapshot,
        sequence: Long,
        live: LivePresentationState,
        clock: PresentationClock,
    ): ActivityPresentation =
        ActivityPresentations.project(
            ActivityInput(
                requireNotNull(snapshot.id),
                sequence,
                requireNotNull(snapshot.controlToken),
                ActivityPhases.resolve(
                    ActivityPhaseInput(
                        snapshot.phase,
                        false,
                        false,
                        null,
                        null,
                        false,
                        snapshot.pendingAction == "stop",
                        snapshot.phase == "finishing",
                    )
                ),
                snapshot.pendingAction,
                snapshot.timerSeconds,
            ),
            live,
            clock,
        )

    fun external(snapshot: RideSnapshot, request: ExternalControlRequest, confirmed: Boolean): ExternalControlDecision =
        RideActions.external(
            ExternalControlContext(
                snapshot.id,
                requireNotNull(snapshot.controlToken),
                snapshot.phase,
                snapshot.pendingAction,
            ),
            request,
            confirmed,
        )

    fun request(intent: Intent): ExternalControlRequest? = runCatching {
        ExternalControlRequest(
            requireNotNull(intent.getStringExtra("ride")),
            requireNotNull(intent.getStringExtra("controlToken")),
            requireNotNull(intent.getStringExtra("expectedPhase")),
            requireNotNull(intent.getStringExtra("action")),
        )
    }
        .getOrNull()

    fun command(intent: Intent, control: ActivityControl): Intent =
        intent
            .putExtra("ride", control.rideId)
            .putExtra("controlToken", control.controlToken)
            .putExtra("expectedPhase", control.expectedPhase)
            .putExtra("action", control.action)
            .setData(
                Uri.Builder()
                    .scheme("power-log")
                    .authority("")
                    .path("/")
                    .appendQueryParameter(
                        "notification",
                        "${control.rideId}/${control.controlToken}/${control.expectedPhase}/${control.action}",
                    )
                    .build()
            )
}

internal fun ExternalControlRequest.toWireMap(): Payload =
    mapOf("rideId" to rideId, "controlToken" to controlToken, "expectedPhase" to expectedPhase, "action" to action)
