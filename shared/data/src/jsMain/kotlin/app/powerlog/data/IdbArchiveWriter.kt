package app.powerlog.data

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import kotlinx.coroutines.flow.Flow

internal class IdbArchiveWriter(private val archive: IdbArchive) : ArchiveWriter {
    override val commits: Flow<CommitNotice> = archive.notices.commits
    override val historyCommits: Flow<CommitNotice> = archive.notices.historyCommits

    override suspend fun commit(batch: WriteBatch): StoreResult<Commit> {
        val applied =
            archive.write<IdbApplied>(
                ARCHIVE_STORES,
                committed = { result -> result.notice?.let { archive.publish(it, result.historyMoved) } },
            ) {
                IdbBatch(this, batch).start()
            }
        return when (applied) {
            is StoreResult.Ok -> StoreResult.Ok(applied.value.commit)
            is StoreResult.Failed -> applied
        }
    }
}

internal class IdbApplied(val commit: Commit, val notice: CommitNotice?, val historyMoved: Boolean)

private const val NEGATIVE = Double.NEGATIVE_INFINITY
private const val POSITIVE = Double.POSITIVE_INFINITY

private class IdbBatch(private val job: IdbJob<IdbApplied>, private val batch: WriteBatch) {
    private val named: List<RideId> = (listOfNotNull(batch.closedRide) + batch.mutations.flatMap(::ridesOf)).distinct()
    private val eventRides: List<RideId> =
        batch.mutations.filterIsInstance<Mutation.AppendEvents>().map { it.rideId }.distinct()

    fun start() {
        val requests = named.map { job.store(IdbObjectStore.ride).get(it.value) }
        job.all(requests + job.store(IdbObjectStore.appState).get(IdbArchive.APP)) { results ->
            val records = named.zip(results).filter { it.second != null }.toMap()
            val app = results.last()
            lastSeqs { seqs -> apply(records, app, seqs) }
        }
    }

    private fun lastSeqs(done: (MutableMap<RideId, Long>) -> Unit) {
        val requests = eventRides.map {
            job.store(IdbObjectStore.rideEvent)
                .openCursor(
                    IDBKeyRange.bound(arrayOf(it.value, NEGATIVE), arrayOf(it.value, POSITIVE), false, false),
                    "prev",
                )
        }
        job.all(requests) { cursors ->
            done(
                eventRides
                    .zip(cursors)
                    .associate { (ride, cursor) -> ride to (if (cursor == null) 0L else long(cursor.value.seq)) }
                    .toMutableMap()
            )
        }
    }

    private fun apply(records: Map<RideId, dynamic>, app: dynamic, seqs: MutableMap<RideId, Long>) {
        val state =
            BatchState<dynamic>(batch) { ride ->
                val record = records[ride]
                if (record == null) null else rideState(record)
            }
        val history = long(app.historyRevision)
        fun gone(rideId: RideId) = job.undo(IdbApplied(Commit.RideGone(rideId), null, historyMoved = false))
        batch.closedRide?.let { closed ->
            state.closedRideAnswer(closed)?.let {
                return job.undo(IdbApplied(it, null, historyMoved = false))
            }
        }
        val sessions = mutableListOf<dynamic>()
        val events = mutableListOf<dynamic>()
        val telemetry = LinkedHashMap<RideId, MutableList<TelemetryRows>>()
        val locations = LinkedHashMap<RideId, MutableList<LocationFix>>()
        val known = mutableListOf<dynamic>()
        var selectionChanged = false
        for (mutation in batch.mutations) {
            when (mutation) {
                is Mutation.CreateRide -> {
                    val created = newRideRecord(mutation.ride)
                    val ride = mutation.ride
                    state.created(
                        RideState(
                            ride.id,
                            created,
                            created = true,
                            ride.phase,
                            ride.interrupted,
                            ride.checkpoint,
                            ride.end,
                            mutation.health,
                            dataRevision = 1,
                            deletedUtcUs = null,
                        )
                    )
                }
                is Mutation.SetPhase ->
                    state.setPhase(state.live(mutation.rideId) ?: return gone(mutation.rideId), mutation.phase)
                is Mutation.Checkpoint ->
                    state.checkpoint(state.live(mutation.rideId) ?: return gone(mutation.rideId), mutation.timing)
                is Mutation.EndRide ->
                    state.endRide(state.live(mutation.rideId) ?: return gone(mutation.rideId), mutation.end)
                is Mutation.MarkRideDeleted -> state.markDeleted(mutation.rideId, mutation.atUtcUs)
                is Mutation.SaveHealthOutcome ->
                    state.saveHealth(state.live(mutation.rideId) ?: return gone(mutation.rideId), mutation.health)
                is Mutation.OpenControllerSession -> {
                    val session = mutation.session
                    val ride = state.live(session.rideId) ?: return gone(session.rideId)
                    val identity = session.identity ?: return job.fail(StorageError(StorageError.Kind.constraint))
                    val row = record()
                    row.ride = session.rideId.value
                    row.sessionNo = session.sessionNo
                    row.decoder = identity.decoder.value
                    row.model = identity.model
                    row.firmware = identity.firmware
                    row.protocol = identity.protocol
                    sessions.add(row)
                    state.factsInserted(ride)
                }
                is Mutation.AppendTelemetry -> {
                    val ride = state.live(mutation.rideId) ?: return gone(mutation.rideId)
                    if (mutation.rows.rows > 0) {
                        telemetry.getOrPut(mutation.rideId) { mutableListOf() } += mutation.rows
                        state.factsInserted(ride)
                    }
                }
                is Mutation.AppendLocations -> {
                    val ride = state.live(mutation.rideId) ?: return gone(mutation.rideId)
                    if (mutation.fixes.isNotEmpty()) {
                        locations.getOrPut(mutation.rideId) { mutableListOf() } += mutation.fixes
                        state.factsInserted(ride)
                    }
                }
                is Mutation.AppendEvents -> {
                    val ride = state.live(mutation.rideId) ?: return gone(mutation.rideId)
                    for (event in mutation.events) {
                        val seq = seqs.getValue(mutation.rideId) + 1
                        seqs[mutation.rideId] = seq
                        val row = record()
                        row.ride = mutation.rideId.value
                        row.seq = seq.js()
                        row.eventId = event.id.value
                        row.kind = Vocabularies.eventKind.name(event.kind)
                        row.utcUs = event.utcUs.js()
                        row.elapsedUs = event.elapsedUs.js()
                        events.add(row)
                    }
                    if (mutation.events.isNotEmpty()) state.factsInserted(ride)
                }
                is Mutation.AppendHealthSamples -> {
                    state.live(mutation.rideId) ?: return gone(mutation.rideId)
                    if (mutation.samples.isNotEmpty()) return job.fail(StorageError(StorageError.Kind.constraint))
                }
                is Mutation.PutHealthWork -> {
                    for (work in mutation.items) state.live(work.rideId) ?: return gone(work.rideId)
                    if (mutation.items.isNotEmpty()) return job.fail(StorageError(StorageError.Kind.constraint))
                }
                is Mutation.RemoveHealthWork -> state.live(mutation.rideId) ?: return gone(mutation.rideId)
                is Mutation.RememberController -> {
                    val row = record()
                    row.bikeId = mutation.bikeId.value
                    row.decoder = mutation.identity.decoder.value
                    row.model = mutation.identity.model
                    row.firmware = mutation.identity.firmware
                    row.protocol = mutation.identity.protocol
                    known.add(row)
                }
                is Mutation.SelectBike -> {
                    app.selectedBike = mutation.bikeId?.value ?: undefined
                    selectionChanged = true
                }
            }
        }
        val changed = state.settle()
        for (ride in state.rides) {
            if (ride.created || ride in changed) ride.writeRecord()
            if (ride.created) job.store(IdbObjectStore.ride).add(ride.stored)
            else if (ride in changed) job.store(IdbObjectStore.ride).put(ride.stored)
        }
        val facts = job.store(IdbObjectStore.facts)
        for ((ride, parts) in telemetry) {
            val values = telemetryValues(parts)
            facts.add(factsRecord(ride, IdbFactKind.telemetry, revision(state, ride), parts.sumOf { it.rows }, values))
        }
        for ((ride, fixes) in locations) facts.add(
            factsRecord(ride, IdbFactKind.location, revision(state, ride), fixes.size, locationValues(fixes))
        )
        for (session in sessions) job.store(IdbObjectStore.controllerSession).add(session)
        for (event in events) job.store(IdbObjectStore.rideEvent).add(event)
        for (row in known) job.store(IdbObjectStore.knownController).put(row)
        val revision = if (state.historyMoved) history + 1 else history
        if (state.historyMoved || selectionChanged) {
            app.historyRevision = revision.js()
            job.store(IdbObjectStore.appState).put(app)
        }
        job.answer(IdbApplied(state.committed(revision), state.notice(revision), state.historyMoved))
    }

    private fun revision(state: BatchState<dynamic>, ride: RideId): Long =
        state.rides.first { it.rideId == ride }.dataRevision

    private fun ridesOf(mutation: Mutation): List<RideId> =
        when (mutation) {
            is Mutation.CreateRide -> listOf(mutation.ride.id)
            is Mutation.SetPhase -> listOf(mutation.rideId)
            is Mutation.Checkpoint -> listOf(mutation.rideId)
            is Mutation.EndRide -> listOf(mutation.rideId)
            is Mutation.MarkRideDeleted -> listOf(mutation.rideId)
            is Mutation.OpenControllerSession -> listOf(mutation.session.rideId)
            is Mutation.AppendTelemetry -> listOf(mutation.rideId)
            is Mutation.AppendLocations -> listOf(mutation.rideId)
            is Mutation.AppendEvents -> listOf(mutation.rideId)
            is Mutation.AppendHealthSamples -> listOf(mutation.rideId)
            is Mutation.SaveHealthOutcome -> listOf(mutation.rideId)
            is Mutation.PutHealthWork -> mutation.items.map { it.rideId }
            is Mutation.RemoveHealthWork -> listOf(mutation.rideId)
            is Mutation.RememberController,
            is Mutation.SelectBike -> emptyList()
        }
}
