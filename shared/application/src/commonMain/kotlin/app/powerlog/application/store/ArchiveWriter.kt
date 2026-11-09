package app.powerlog.application.store

import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.RideTiming
import kotlinx.coroutines.flow.Flow

public interface ArchiveWriter {
    public suspend fun commit(batch: WriteBatch): StoreResult<Commit>

    public val commits: Flow<CommitNotice>

    // only the notices that moved history_revision
    public val historyCommits: Flow<CommitNotice>
}

public data class WriteBatch(
    public val mutations: List<Mutation>,
    // the batch applies only while this ride is stored, undeleted, and completed or failed
    public val closedRide: RideId? = null,
)

public sealed interface Mutation {
    public data class CreateRide(public val ride: Ride, public val health: RideHealth) : Mutation

    public data class SetPhase(public val rideId: RideId, public val phase: RidePhase) : Mutation

    public data class Checkpoint(public val rideId: RideId, public val timing: RideTiming) : Mutation

    public data class EndRide(public val rideId: RideId, public val end: RideTiming) : Mutation

    public data class MarkRideDeleted(public val rideId: RideId, public val atUtcUs: Long) : Mutation

    public data class OpenControllerSession(public val session: ControllerSession) : Mutation

    public data class AppendTelemetry(public val rideId: RideId, public val rows: TelemetryRows) : Mutation

    public data class AppendLocations(public val rideId: RideId, public val fixes: List<LocationFix>) : Mutation

    public data class AppendEvents(public val rideId: RideId, public val events: List<RideEvent>) : Mutation

    public data class AppendHealthSamples(public val rideId: RideId, public val samples: List<HealthSample>) : Mutation

    public data class SaveHealthOutcome(public val rideId: RideId, public val health: RideHealth) : Mutation

    public data class PutHealthWork(public val items: List<HealthWork>) : Mutation

    // task null: every task
    public data class RemoveHealthWork(public val rideId: RideId, public val task: HealthTask?) : Mutation

    public data class RememberController(public val bikeId: BikeId, public val identity: ControllerIdentity) : Mutation

    // bikeId null: forget the bike
    public data class SelectBike(public val bikeId: BikeId?) : Mutation
}

public sealed interface Commit {
    public data class Committed(public val revisions: Map<RideId, Long>, public val historyRevision: Long) : Commit

    // a ride the batch names is absent or deleted
    public data class RideGone(public val rideId: RideId) : Commit

    // closedRide is preparing, running, paused or interrupted
    public data class RideOpen(public val rideId: RideId) : Commit
}

public data class CommitNotice(
    public val revisions: Map<RideId, Long>,
    public val historyRevision: Long,
    // rides whose History row or Health section changed
    public val historyRides: Set<RideId>,
    public val otherTab: Boolean,
)
