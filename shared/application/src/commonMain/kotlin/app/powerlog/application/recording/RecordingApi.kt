package app.powerlog.application.recording

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.HealthView
import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import app.powerlog.domain.recording.RideAllowed
import app.powerlog.domain.recording.SourceState
import kotlinx.serialization.Serializable

public interface RecordingApi {
    public val state: ApiStream<Empty, RecordingView>
    public val permissions: ApiStream<Empty, PermissionsView>

    public suspend fun start(input: Empty): Outcome<Unit>

    public suspend fun control(input: ControlInput): Outcome<Unit>

    public suspend fun requestPermissions(input: Empty): Outcome<Unit>

    public suspend fun refreshPermissions(input: Empty): Outcome<Unit>
}

@Serializable
public data class RecordingView(
    public val phase: RidePhase?,
    public val ride: OpenRideView?,
    public val pending: RideAction?,
    public val timer: TimerView,
    public val allowed: RideAllowed,
    public val finishRequest: FinishRequestView?,
    public val sources: SourcesView,
    public val health: HealthView?,
    public val otherTab: Boolean,
    public val failure: ApiFailure?,
)

@Serializable public data class OpenRideView(public val rideId: RideId, public val options: RideOptions)

@Serializable
public data class TimerView(public val state: TimerState, public val baseUs: Long, public val originMonoUs: Long?)

@Serializable public data class FinishRequestView(public val requestId: String, public val rideId: RideId)

@Serializable
public data class SourcesView(
    public val controller: SourceState,
    public val heartRate: SourceState,
    public val gps: SourceState,
)

@Serializable
public data class PermissionsView(
    public val action: PermissionAction?,
    public val actionFor: PermissionNeed?,
    public val failure: ApiFailure?,
)

@Serializable public data class ControlInput(public val rideId: RideId, public val action: RideAction)
