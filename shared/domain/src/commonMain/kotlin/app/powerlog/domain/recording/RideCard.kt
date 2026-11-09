package app.powerlog.domain.recording

import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import kotlinx.serialization.Serializable

public data class RideCardFacts(
    public val rideId: String,
    public val token: String,
    public val state: RideCardState,
    public val timer: RideTimer,
    public val powerW: Double?,
    public val heartRateBpm: Double?,
    public val bikeFreshUntilUtcUs: Long?,
    public val heartRateFreshUntilUtcUs: Long?,
    public val controls: List<RideAction>,
)

@Serializable
public enum class RideCardState {
    starting,
    running,
    paused,
    updating,
    interrupted,
}

@Serializable
public enum class PermissionNeed {
    health,
    location,
    locationRestricted,
    locationServices,
    preciseLocation,
}

@Serializable
public enum class PermissionAction {
    request,
    openAppSettings,
    healthHelp,
    check,
    unavailable,
}
