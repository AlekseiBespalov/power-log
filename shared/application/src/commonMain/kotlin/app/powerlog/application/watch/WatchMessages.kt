package app.powerlog.application.watch

import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.health.HealthPlan
import app.powerlog.domain.health.IndexedRoutePoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface WatchMessage {
    @Serializable
    @SerialName("startRequest")
    public data class StartRequestMessage(public val requestId: String, public val options: WatchStartOptions) :
        WatchMessage

    @Serializable
    @SerialName("cancelStart")
    public data class CancelStartMessage(
        public val eventId: EventId?,
        public val requestId: String?,
        public val rideId: RideId?,
    ) : WatchMessage

    @Serializable
    @SerialName("command")
    public data class CommandMessage(
        public val rideId: RideId,
        public val action: RideAction,
        public val expiresUtcUs: Long,
    ) : WatchMessage

    @Serializable
    @SerialName("healthSamples")
    public data class HealthSamplesMessage(
        public val rideId: RideId,
        public val throughSequence: Long,
        public val samples: List<HealthOriginal>,
        public val heartRate: LiveHeartRate?,
    ) : WatchMessage

    @Serializable
    @SerialName("healthOutcome")
    public data class HealthOutcomeMessage(
        public val rideId: RideId,
        public val outcome: HealthOutcome,
        public val workoutId: String?,
        public val reason: HealthReason?,
    ) : WatchMessage

    @Serializable
    @SerialName("start")
    public data class StartMessage(
        public val eventId: EventId,
        public val requestId: String?,
        public val rideId: RideId,
        public val startedUtcUs: Long,
        public val expiresUtcUs: Long,
        public val options: WatchRideOptions,
        public val workout: WorkoutIds,
    ) : WatchMessage

    @Serializable
    @SerialName("ride")
    public data class RideMessage(
        public val rideId: RideId,
        public val stateRevision: Long,
        public val phase: WatchPhase,
        public val timer: RideTimer,
        public val lapsUtcUs: List<Long>,
        public val lapCount: Int,
        public val units: UnitSystem,
        public val display: WatchDisplay?,
        public val quantities: List<HealthPlan.Quantity>,
        public val routeStride: Int,
        public val route: List<IndexedRoutePoint>,
        public val endedUtcUs: Long?,
    ) : WatchMessage

    @Serializable
    @SerialName("ack")
    public data class AckMessage(public val error: FailureCode?, public val acknowledgedSequence: Long?) : WatchMessage
}

@Serializable
public enum class WatchPhase {
    preparing,
    running,
    paused,
    interrupted,
    completed,
    discarded,
}

@Serializable
public data class WatchStartOptions(
    public val indoor: Boolean,
    public val saveToHealth: Boolean,
    public val recordGps: Boolean?,
)

@Serializable
public data class WatchRideOptions(
    public val indoor: Boolean,
    public val saveToHealth: Boolean,
    public val recordGps: Boolean,
    public val distanceSource: DistanceSource,
)

@Serializable public data class WorkoutIds(public val syncId: String, public val syncVersion: Int)

@Serializable
public data class WatchDisplay(
    public val powerW: Double?,
    public val cadenceRpm: Double?,
    public val distanceM: Double?,
    public val freshUntilUtcUs: Long,
)
