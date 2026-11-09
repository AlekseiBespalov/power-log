package app.powerlog.application.health

import app.powerlog.application.PagePolicy
import app.powerlog.application.orThrow
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.health.HealthIds
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.HealthSamples
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HealthReadback(
    private val clock: Clock,
    private val provider: HealthProvider,
    private val rides: RideRepository,
    private val repository: HealthRepository,
    private val measurements: MeasurementRepository,
    private val writer: ArchiveWriter,
) {
    private val rateLimits = mutableMapOf<Pair<RideId, HealthMetric>, Int>()
    private val refusals = mutableMapOf<RideId, PortErrorKind>()
    private val locks = mutableMapOf<RideId, Mutex>()

    suspend fun dueNow(rideId: RideId, nowUtcUs: Long) =
        locked(rideId) {
            val waiting =
                readback(repository.work(rideId).orThrow()).filter {
                    it.state == ComponentState.pending && it.dueUtcUs == null
                }
            if (waiting.isNotEmpty())
                commit(listOf(Mutation.PutHealthWork(waiting.map { it.copy(dueUtcUs = nowUtcUs) })))
        }

    fun refusal(rideId: RideId): PortErrorKind? = refusals.remove(rideId)

    private suspend fun <T> locked(rideId: RideId, block: suspend () -> T): T {
        val lock = locks.getOrPut(rideId) { Mutex() }
        return try {
            lock.withLock { block() }
        } finally {
            if (!lock.isLocked && locks[rideId] === lock) locks.remove(rideId)
        }
    }

    private val android = provider.kind == HealthProviderKind.healthConnect

    suspend fun schedule(ride: Ride) =
        locked(ride.id) {
            val end = ride.end ?: return@locked
            if (end.utcUs <= ride.startedUtcUs) return@locked
            val grants = if (android) grants() else null
            val due = end.utcUs + HealthPolicy.readbackAfterFinishUs.first()
            val rows =
                HealthMetrics.readback(provider.kind).map { metric ->
                    val denied = grants != null && grants.read[metric.wire] != PermissionState.granted
                    HealthWork(
                        ride.id,
                        HealthWorkScope.Readback(metric),
                        if (denied) ComponentState.unavailable else ComponentState.pending,
                        0,
                        null,
                        if (denied) null else due,
                        if (denied) HealthReason.permission else null,
                    )
                }
            val fold = HealthViews.readbackPart(rows)
            val stored = repository.work(ride.id).orThrow().firstOrNull { it.scope == readbackPart }
            val part =
                when {
                    stored != null && fold != null -> stored.copy(state = fold.state, reason = fold.reason)
                    android && ride.options.healthWriter == null ->
                        HealthWork(
                            ride.id,
                            readbackPart,
                            fold?.state ?: ComponentState.pending,
                            0,
                            null,
                            null,
                            fold?.reason,
                        )
                    else -> null
                }
            commit(listOf(Mutation.PutHealthWork(rows + listOfNotNull(part))))
        }

    suspend fun reset(rideId: RideId, nowUtcUs: Long): List<HealthWork> =
        locked(rideId) {
            val rows = readback(repository.work(rideId).orThrow())
            if (rows.isEmpty()) return@locked rows
            val reset = rows.map {
                it.copy(state = ComponentState.pending, attempts = 0, cursor = null, dueUtcUs = nowUtcUs, reason = null)
            }
            rateLimits.keys.removeAll { it.first == rideId }
            commit(listOf(Mutation.PutHealthWork(reset)) + partChange(rideId, reset))
            reset
        }

    suspend fun hasRows(rideId: RideId): Boolean = readback(repository.work(rideId).orThrow()).isNotEmpty()

    suspend fun run(rideId: RideId, background: Boolean): Long? =
        locked(rideId) {
            val record = rides.ride(rideId).orThrow() ?: return@locked null
            val ride = record.ride
            if (ride.phase != RidePhase.completed || ride.end == null) return@locked null
            if (!android) settle(record)
            var rows = readback(repository.work(rideId).orThrow())
            val nowUtcUs = clock.utcUs()
            val due = rows.filter { it.state == ComponentState.pending && (it.dueUtcUs ?: Long.MAX_VALUE) <= nowUtcUs }
            if (due.isNotEmpty()) {
                val grants = if (android) grants() else null
                for (row in due) {
                    val next = pass(record, row, grants, background) ?: continue
                    rows = rows.map { if (it.scope == next.scope) next else it }
                }
            }
            rows.filter { it.state == ComponentState.pending }.mapNotNull { it.dueUtcUs }.minOrNull()
        }

    suspend fun settle(record: RideRecord) {
        val session = provider.session ?: return
        val ride = record.ride
        val end = ride.end ?: return
        if (ride.phase != RidePhase.completed || record.health.outcome != HealthOutcome.pending) return
        val found = (called<String?> { session.savedWorkout(ride.id.value, it) } as? Called.Ok)?.value
        val health = record.health
        when {
            found != null ->
                commit(
                    listOf(
                        Mutation.SaveHealthOutcome(
                            ride.id,
                            health.copy(outcome = HealthOutcome.saved, workoutId = found),
                        )
                    )
                )
            clock.utcUs() >= end.utcUs + HealthWorkPolicy.pendingOutcomeSettleUs -> {
                val unsaved =
                    record.publish
                        .filter { it.state != ComponentState.saved && it.scope != readbackPart }
                        .map { it.copy(state = ComponentState.failed, reason = HealthReason.ambiguous) }
                commit(
                    listOf(
                        Mutation.SaveHealthOutcome(
                            ride.id,
                            health.copy(outcome = HealthOutcome.unknown, reason = HealthReason.ambiguous),
                        )
                    ) + listOfNotNull(unsaved.takeIf { it.isNotEmpty() }?.let(Mutation::PutHealthWork))
                )
            }
        }
    }

    private suspend fun pass(
        record: RideRecord,
        row: HealthWork,
        grants: HealthGrants?,
        background: Boolean,
    ): HealthWork? {
        val ride = record.ride
        val end = requireNotNull(ride.end)
        val metric = (row.scope as HealthWorkScope.Readback).metric
        if (android) {
            grants ?: return null
            if (grants.read[metric.wire] != PermissionState.granted) return ended(record, row, HealthReason.permission)
            if (background && grants.backgroundRead != PermissionState.granted)
                return save(record, row.copy(dueUtcUs = null))
            val readable = grants.readableFromUtcUs
            if (readable != null && ride.startedUtcUs < readable)
                return save(record, row.copy(state = ComponentState.notRequested, dueUtcUs = null, reason = null))
        }
        val instants = HealthPolicy.readbackAfterFinishUs.map { end.utcUs + it }
        val timeline = ActiveTimeline(end.elapsedUs, measurements.events(ride.id).orThrow())
        var cursor = row.cursor
        while (true) {
            val read =
                called<HealthOriginalPage> {
                    provider.reader.read(metric.wire, ride.startedUtcUs, end.utcUs, cursor, it)
                }
            when (read) {
                is Called.Ok -> {
                    cursor = read.value.next
                    val samples =
                        read.value.samples
                            .filter { !HealthIds.isOwn(it.syncId) }
                            .mapNotNull { sample(ride, timeline, it) }
                    val next = if (cursor == null) complete(row, instants) else row.copy(cursor = cursor)
                    val chunks = samples.chunked(PagePolicy.rows)
                    for (chunk in chunks.dropLast(1)) commit(listOf(Mutation.AppendHealthSamples(ride.id, chunk)))
                    val last =
                        chunks.lastOrNull()?.let { listOf(Mutation.AppendHealthSamples(ride.id, it)) } ?: emptyList()
                    if (
                        !commit(last + listOf(Mutation.PutHealthWork(listOf(next))) + partChange(ride.id, record, next))
                    )
                        return null
                    if (cursor == null) return next
                }
                is Called.Failed -> {
                    val kept = row.copy(cursor = cursor)
                    return when (read.error.kind) {
                        PortErrorKind.busy -> null
                        PortErrorKind.rateLimited -> rateLimited(record, kept, instants)
                        PortErrorKind.denied,
                        PortErrorKind.locked,
                        PortErrorKind.unsupported -> {
                            if (!android) refusals[ride.id] = read.error.kind
                            if (read.error.kind == PortErrorKind.locked) failedPass(record, kept, instants)
                            else ended(record, kept, HealthReason.readbackUnavailable)
                        }
                        else -> failedPass(record, kept, instants)
                    }
                }
                Called.Abandoned -> return failedPass(record, row.copy(cursor = cursor), instants)
            }
        }
    }

    private fun complete(row: HealthWork, instants: List<Long>): HealthWork {
        val due = instants.firstOrNull { it > clock.utcUs() }
        return if (due == null) row.copy(state = ComponentState.saved, cursor = null, dueUtcUs = null, reason = null)
        else row.copy(cursor = null, dueUtcUs = due)
    }

    private suspend fun failedPass(record: RideRecord, row: HealthWork, instants: List<Long>): HealthWork? {
        val due = instants.firstOrNull { it > clock.utcUs() }
        val next = row.copy(attempts = row.attempts + 1, dueUtcUs = due)
        return if (due == null) ended(record, next, HealthReason.readbackUnavailable) else save(record, next)
    }

    private suspend fun rateLimited(record: RideRecord, row: HealthWork, instants: List<Long>): HealthWork? {
        val last = instants.last()
        if (clock.utcUs() >= last && (row.dueUtcUs ?: Long.MIN_VALUE) > last)
            return ended(record, row, HealthReason.readbackUnavailable)
        val key = record.ride.id to (row.scope as HealthWorkScope.Readback).metric
        val waits = HealthWorkPolicy.readRateLimitWaitsUs
        val count = rateLimits[key] ?: 0
        rateLimits[key] = count + 1
        return save(record, row.copy(dueUtcUs = clock.utcUs() + waits[minOf(count, waits.lastIndex)]))
    }

    private suspend fun ended(record: RideRecord, row: HealthWork, reason: HealthReason): HealthWork? =
        save(record, row.copy(state = ComponentState.unavailable, cursor = null, dueUtcUs = null, reason = reason))

    private suspend fun save(record: RideRecord, row: HealthWork): HealthWork? =
        if (commit(listOf(Mutation.PutHealthWork(listOf(row))) + partChange(record.ride.id, record, row))) row else null

    private suspend fun partChange(rideId: RideId, record: RideRecord, changed: HealthWork): List<Mutation> {
        val rows = readback(repository.work(rideId).orThrow()).map { if (it.scope == changed.scope) changed else it }
        return part(record.publish, rows)
    }

    private suspend fun partChange(rideId: RideId, rows: List<HealthWork>): List<Mutation> {
        val record = rides.ride(rideId).orThrow() ?: return emptyList()
        return part(record.publish, rows)
    }

    private fun part(publish: List<HealthWork>, rows: List<HealthWork>): List<Mutation> {
        val current = publish.firstOrNull { it.scope == readbackPart } ?: return emptyList()
        val fold = HealthViews.readbackPart(rows) ?: return emptyList()
        if (fold.state == current.state && fold.reason == current.reason) return emptyList()
        return listOf(Mutation.PutHealthWork(listOf(current.copy(state = fold.state, reason = fold.reason))))
    }

    suspend fun samples(ride: Ride, originals: List<HealthOriginal>): List<HealthSample> {
        val endUs = ride.end?.elapsedUs ?: ride.checkpoint.elapsedUs
        val timeline = ActiveTimeline(endUs, measurements.events(ride.id).orThrow())
        return originals.mapNotNull { sample(ride, timeline, it) }
    }

    private fun sample(ride: Ride, timeline: ActiveTimeline, original: HealthOriginal): HealthSample? {
        val metric = HealthMetric(original.metric)
        if (!HealthSamples.inRide(ride, metric, original.startUtcUs, original.endUtcUs)) return null
        val representation =
            when {
                original.member != null -> HealthRepresentation.rawSeries
                android && original.metric == "association" -> HealthRepresentation.workoutAssociation
                android && (original.metric == "activeEnergy" || original.metric == "cyclingDistance") ->
                    HealthRepresentation.intervalRecord
                else -> HealthRepresentation.rawQuantity
            }
        val place = HealthSamples.place(ride, timeline, original.startUtcUs, original.endUtcUs, representation)
        return HealthSample(
            rideId = ride.id,
            producer = if (ride.options.useWatch) Producer.watch else Producer.phone,
            sampleKey =
                HealthSamples.sampleKey(
                    original.sampleId,
                    original.member,
                    original.startUtcUs,
                    original.endUtcUs,
                    original.value,
                ),
            dataOrigin = original.dataOrigin,
            parentId = original.sampleId,
            member = original.member,
            metric = metric,
            representation = representation,
            value = original.value,
            sampleCount = 1,
            utcUs = place.utcUs,
            elapsedUs = place.elapsedUs,
            startUtcUs = original.startUtcUs,
            endUtcUs = original.endUtcUs,
            activeInterval = place.activeInterval,
        )
    }

    private suspend fun grants(): HealthGrants? = (called { provider.access.grants(it) } as? Called.Ok)?.value

    private suspend fun commit(mutations: List<Mutation>): Boolean =
        mutations.isEmpty() || writer.commit(WriteBatch(mutations)) is StoreResult.Ok

    private fun readback(rows: List<HealthWork>): List<HealthWork> = rows.filter {
        it.scope is HealthWorkScope.Readback
    }

    private companion object {
        val readbackPart = HealthWorkScope.Publish(HealthComponent.readback)
    }
}
