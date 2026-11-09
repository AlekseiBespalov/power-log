package app.powerlog.application.port

import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPlan
import kotlinx.serialization.Serializable

public interface HealthProvider {
    public val kind: HealthProviderKind
    public val access: HealthAccess
    public val reader: HealthReader
    public val session: AppleWorkoutSession?
    public val writer: HealthConnectWriter?
}

public interface HealthAccess {
    public fun availability(): HealthAvailability

    public fun grants(reply: Reply<HealthGrants>)

    public fun request(scope: HealthScope, reply: Reply<Unit>)
}

public enum class HealthAvailability {
    available,
    updateRequired,
    unsupported,
}

public data class HealthScope(
    public val write: Set<HealthComponent>,
    public val read: Set<String>,
    public val route: Boolean,
    public val backgroundRead: Boolean,
)

public data class HealthGrants(
    public val write: Map<HealthComponent, PermissionState>,
    public val read: Map<String, PermissionState>,
    public val route: PermissionState,
    public val backgroundRead: PermissionState?,
    public val readableFromUtcUs: Long?,
)

public interface HealthReader {
    public fun read(
        metric: String,
        startUtcUs: Long,
        endUtcUs: Long,
        cursor: String?,
        reply: Reply<HealthOriginalPage>,
    )
}

public data class HealthOriginalPage(public val samples: List<HealthOriginal>, public val next: String?)

@Serializable
public data class HealthOriginal(
    public val sampleId: String,
    public val member: Int?,
    public val metric: String,
    public val value: Double?,
    public val startUtcUs: Long,
    public val endUtcUs: Long,
    public val dataOrigin: String,
    public val syncId: String?,
)

@Serializable public data class LiveHeartRate(public val bpm: Double, public val utcUs: Long)

public interface AppleWorkoutSession {
    public fun attach(sessionEvents: WorkoutSessionEvents)

    public fun begin(workout: HealthPlan.Workout, options: SessionOptions, reply: Reply<Unit>)

    public fun pause()

    public fun resume()

    public fun add(quantities: List<HealthPlan.Quantity>, reply: Reply<Unit>)

    public fun addLap(atUtcUs: Long, reply: Reply<Unit>)

    public fun finish(atUtcUs: Long, route: HealthPlan.Route?, reply: Reply<FinishedWorkout>)

    public fun discard(atUtcUs: Long)

    public fun recoverOrphan(reply: Reply<String?>)

    public fun savedWorkout(rideId: String, reply: Reply<String?>)
}

public data class SessionOptions(public val indoor: Boolean, public val route: Boolean)

public data class FinishedWorkout(public val workoutId: String, public val routeSaved: Boolean)

public enum class SessionState {
    notStarted,
    prepared,
    running,
    paused,
    stopped,
    ended,
}

public interface WorkoutSessionEvents {
    public fun stateChanged(state: SessionState, atUtcUs: Long)

    public fun failed(error: PortError)

    public fun heartRateReceived(heartRate: LiveHeartRate, at: ClockReading)

    public fun samplesCollected(samples: List<HealthOriginal>, at: ClockReading)
}

public interface HealthConnectWriter {
    public val packageName: String
    public val appLabel: String

    public fun insert(records: List<HealthConnectRecordPlan>, reply: Reply<List<String>>)
}
