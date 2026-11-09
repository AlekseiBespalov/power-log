package app.powerlog.platform.ridecard

import android.content.Intent
import android.net.Uri
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.recording.RideCardState
import app.powerlog.kit.RideCardContent

object RideCardIntents {
    const val RIDE = "ride"
    const val TOKEN = "controlToken"
    const val PHASE = "expectedPhase"
    const val ACTION = "action"
    private const val SCHEME = "power-log"
    private const val CONTROL = "control"

    fun command(intent: Intent, control: RideCardContent.Control): Intent =
        intent
            .putExtra(RIDE, control.rideId)
            .putExtra(TOKEN, control.controlToken)
            .putExtra(PHASE, control.expectedPhase.name)
            .putExtra(ACTION, control.action.name)
            .setData(
                Uri.Builder()
                    .scheme(SCHEME)
                    .authority("")
                    .path("/")
                    .appendQueryParameter(
                        CONTROL,
                        "${control.rideId}/${control.controlToken}/${control.expectedPhase.name}/${control.action.name}",
                    )
                    .build()
            )

    fun read(intent: Intent): RideCardIntent? {
        val data = intent.data ?: return null
        if (data.scheme != SCHEME || data.getQueryParameter(CONTROL) == null) return null
        val rideId = intent.getStringExtra(RIDE) ?: return null
        val token = intent.getStringExtra(TOKEN) ?: return null
        val phase = RideCardState.entries.firstOrNull { it.name == intent.getStringExtra(PHASE) } ?: return null
        val action = RideAction.entries.firstOrNull { it.name == intent.getStringExtra(ACTION) } ?: return null
        return RideCardIntent(rideId, token, phase, action)
    }
}
