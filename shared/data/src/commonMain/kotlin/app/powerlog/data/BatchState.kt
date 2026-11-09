package app.powerlog.data

import app.powerlog.application.store.Commit
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.RideTiming

internal val OPEN_PHASES: Set<RidePhase> =
    setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)

internal class RideState<R>(
    val rideId: RideId,
    val stored: R,
    val created: Boolean,
    var phase: RidePhase,
    var interrupted: Boolean,
    var checkpoint: RideTiming,
    var end: RideTiming?,
    var health: RideHealth,
    var dataRevision: Long,
    var deletedUtcUs: Long?,
) {
    val deletedAtStart: Boolean = !created && deletedUtcUs != null
    var factsMoved: Boolean = false
    var rowChanged: Boolean = false
}

internal class BatchState<R>(batch: WriteBatch, private val load: (RideId) -> RideState<R>?) {
    private val ends: Map<RideId, RideTiming> =
        batch.mutations.filterIsInstance<Mutation.EndRide>().associate { it.rideId to it.end }
    private val loaded = HashMap<RideId, RideState<R>?>()
    private val named = LinkedHashMap<RideId, RideState<R>>()
    private val historyRides = LinkedHashSet<RideId>()
    var historyMoved: Boolean = false
        private set

    val rides: Collection<RideState<R>>
        get() = named.values

    fun stored(rideId: RideId): RideState<R>? = loaded.getOrPut(rideId) { load(rideId) }

    fun closedRideAnswer(rideId: RideId): Commit? {
        val ride = stored(rideId)
        return when {
            ride == null || ride.deletedAtStart -> Commit.RideGone(rideId)
            ride.phase in OPEN_PHASES -> Commit.RideOpen(rideId)
            else -> null
        }
    }

    // null: the ride is absent or was deleted when the batch started, and the batch answers RideGone
    fun live(rideId: RideId): RideState<R>? =
        stored(rideId)?.takeUnless { it.deletedAtStart }?.also { named[rideId] = it }

    fun created(ride: RideState<R>) {
        loaded[ride.rideId] = ride
        named[ride.rideId] = ride
        ride.factsMoved = true
        history(ride)
    }

    fun factsInserted(ride: RideState<R>) {
        ride.factsMoved = true
    }

    fun setPhase(ride: RideState<R>, phase: RidePhase) {
        if (ride.phase == phase) return
        ride.phase = phase
        if (phase == RidePhase.interrupted) ride.interrupted = true
        timing(ride)
        history(ride)
    }

    fun checkpoint(ride: RideState<R>, timing: RideTiming) {
        val end = ends[ride.rideId]
        val target = end ?: timing
        if (end == null && timing.elapsedUs < ride.checkpoint.elapsedUs) return
        if (ride.checkpoint == target) return
        ride.checkpoint = target
        timing(ride)
    }

    fun endRide(ride: RideState<R>, end: RideTiming) {
        if (ride.end == end) return
        ride.end = end
        timing(ride)
        history(ride)
    }

    fun saveHealth(ride: RideState<R>, health: RideHealth) {
        if (ride.health == health) return
        ride.health = health
        ride.rowChanged = true
        history(ride)
    }

    fun markDeleted(rideId: RideId, atUtcUs: Long) {
        val ride = stored(rideId) ?: return
        named[rideId] = ride
        if (ride.deletedUtcUs != null) return
        ride.deletedUtcUs = atUtcUs
        ride.rowChanged = true
        history(ride)
    }

    fun publishChanged(ride: RideState<R>) {
        history(ride)
    }

    // Raises each moved ride's data_revision; returns the rows to write.
    fun settle(): List<RideState<R>> {
        for (ride in named.values) if (ride.factsMoved && !ride.created) ride.dataRevision++
        return named.values.filter { !it.created && (it.factsMoved || it.rowChanged) || it.created && it.rowChanged }
    }

    fun committed(historyRevision: Long): Commit.Committed =
        Commit.Committed(named.mapValues { it.value.dataRevision }, historyRevision)

    // null: the commit changed nothing that moves a revision
    fun notice(historyRevision: Long, otherTab: Boolean = false): CommitNotice? {
        val moved = named.values.filter { it.factsMoved }.associate { it.rideId to it.dataRevision }
        if (moved.isEmpty() && !historyMoved) return null
        return CommitNotice(moved, historyRevision, historyRides.toSet(), otherTab)
    }

    private fun timing(ride: RideState<R>) {
        ride.factsMoved = true
        ride.rowChanged = true
    }

    private fun history(ride: RideState<R>) {
        historyMoved = true
        historyRides += ride.rideId
    }
}
