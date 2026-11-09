package app.powerlog.application.recording

import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.recording.RideCardState

public data class RideCardIntent(
    public val rideId: String,
    public val token: String,
    public val phase: RideCardState,
    public val action: RideAction,
)
