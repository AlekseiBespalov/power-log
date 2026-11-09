package app.powerlog.application.history

import app.powerlog.application.Executors
import app.powerlog.application.RideSummaries
import app.powerlog.application.StoreFailure
import app.powerlog.application.SummaryAnswer
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.ApiFailureException
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.api.RideInput
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.failure
import app.powerlog.application.api.readFailure
import app.powerlog.application.exports.ExportSessions
import app.powerlog.application.health.HealthViews
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideCursor
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.battery.SelectedConsumption
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FinishBoundary
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.RideRules
import app.powerlog.domain.health.PublicationRules
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAtomicApi::class)
internal class History(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val clock: Clock,
    private val rides: RideRepository,
    private val measurements: MeasurementRepository,
    private val healthWork: HealthRepository,
    private val writer: ArchiveWriter,
    private val exports: ExportSessions,
    private val health: HealthProvider?,
    private val summaries: RideSummaries,
    private val maintenance: Maintenance,
    private val forget: (RideId) -> Unit,
    private val refuse: (StoreResult.Failed) -> Unit,
) {
    private class Shown(val revision: Long, val view: RideDetailView)

    private class Flight(val revision: Long, val answer: Deferred<SummaryAnswer>)

    private class Detail(val view: RideDetailView, val flight: Flight?)

    private val shown = AtomicReference<Map<RideId, Shown>>(emptyMap())
    private val listed = CompletableDeferred<Unit>()
    private val list = Published(HistoryListView(0), 0, clock)

    fun watch() {
        scope.launch(executors.ride, CoroutineStart.UNDISPATCHED) {
            writer.historyCommits.collect { notice ->
                marker()
                if (!notice.otherTab && notice.historyRides.isNotEmpty())
                    scope.launch(executors.reads) { warm(notice.historyRides) }
            }
        }
        scope.launch(executors.ride) { marker() }
        maintenance.watch()
    }

    suspend fun list(after: Long?): StreamAnswer<HistoryListView> {
        listed.await()
        return list.next(after)
    }

    suspend fun page(input: HistoryPageInput): Outcome<HistoryPage> =
        withContext(executors.reads) {
            val after = input.after?.let(::cursor)
            when (val page = rides.page(after, HistoryPolicy.pageRows)) {
                is StoreResult.Ok -> {
                    val exporting = exports.exporting()
                    Outcome.Ok(HistoryPage(page.value.rows.map { row(it, exporting) }, page.value.next?.let(::text)))
                }
                is StoreResult.Failed -> Outcome.Failed(readFailed(page))
            }
        }

    suspend fun ride(input: RideInput, after: Long?): StreamAnswer<RideDetailView> =
        withContext(executors.reads) { next(input.rideId, after) }

    suspend fun delete(rideId: RideId): Outcome<Unit> =
        withContext(executors.ride) {
            val record =
                when (val read = rides.ride(rideId)) {
                    is StoreResult.Ok -> read.value ?: return@withContext Outcome.Ok(Unit)
                    is StoreResult.Failed -> return@withContext Outcome.Failed(readFailed(read))
                }
            val allowed = RideRules.deletable(record.ride, exports.exporting())
            if (!allowed.enabled) return@withContext Outcome.Failed(failure(requireNotNull(allowed.reason)))
            val batch =
                WriteBatch(
                    listOf(Mutation.RemoveHealthWork(rideId, null), Mutation.MarkRideDeleted(rideId, clock.utcUs())),
                    closedRide = rideId,
                )
            when (val commit = writer.commit(batch)) {
                is StoreResult.Failed -> Outcome.Failed(commitFailure(commit.error))
                is StoreResult.Ok ->
                    when (commit.value) {
                        is Commit.Committed -> {
                            forget(rideId)
                            Outcome.Ok(Unit)
                        }
                        is Commit.RideGone -> Outcome.Ok(Unit)
                        is Commit.RideOpen -> Outcome.Failed(failure(FailureCode.rideInProgress))
                    }
            }
        }

    private suspend fun warm(rideIds: Set<RideId>) {
        for (rideId in rideIds) {
            val record = (rides.ride(rideId) as? StoreResult.Ok)?.value ?: continue
            if (record.ride.phase == RidePhase.completed && summaries.cached(rideId, record.revision) == null)
                summaries.warm(record.ride, record.revision)
        }
    }

    private suspend fun next(rideId: RideId, after: Long?): StreamAnswer<RideDetailView> = coroutineScope {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val watching =
            launch(start = CoroutineStart.UNDISPATCHED) {
                writer.commits
                    .filter { rideId in it.revisions || rideId in it.historyRides }
                    .collect { changed.trySend(Unit) }
            }
        try {
            answer(rideId, after, changed)
        } finally {
            watching.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun answer(rideId: RideId, after: Long?, changed: Channel<Unit>): StreamAnswer<RideDetailView> {
        var flight: Flight? = null
        while (true) {
            val detail =
                try {
                    detail(rideId, flight) ?: return StreamAnswer.Failed(failure(FailureCode.rideDeleted))
                } catch (failed: ApiFailureException) {
                    return StreamAnswer.Failed(failed.failure)
                }
            flight = detail.flight
            val revision = remember(rideId, detail.view)
            if (after == null || revision > after) return StreamAnswer.Value(revision, detail.view)
            val computing = detail.flight?.answer?.takeUnless { it.isCompleted }
            if (computing == null) changed.receive()
            else
                select {
                    changed.onReceive {}
                    computing.onJoin {}
                }
            if (detail.view.ride.phase in recordingPhases) delay(StreamPolicy.historyRideUs / 1_000)
        }
    }

    private suspend fun marker() {
        when (val read = rides.marker()) {
            is StoreResult.Ok -> list.publish(HistoryListView(maxOf(read.value, list.value.revision)), urgent = true)
            is StoreResult.Failed -> readFailed(read)
        }
        listed.complete(Unit)
    }

    private fun remember(rideId: RideId, view: RideDetailView): Long {
        while (true) {
            val current = shown.load()
            val previous = current[rideId]
            if (previous != null && previous.view == view) return previous.revision
            val next = Shown((previous?.revision ?: 0) + 1, view)
            if (shown.compareAndSet(current, current + (rideId to next))) return next.revision
        }
    }

    private suspend fun detail(rideId: RideId, previous: Flight?): Detail? {
        val record = read(rides.ride(rideId)) ?: return null
        val ride = record.ride
        val events = read(measurements.events(rideId))
        var flight: Flight? = null
        var summary: RideSummary? = null
        val state =
            when {
                !summaries.summarized(ride) -> SummaryState.pending
                else -> {
                    summary = summaries.cached(rideId, record.revision)
                    if (summary != null) SummaryState.ready
                    else {
                        val current =
                            previous?.takeIf { it.revision == record.revision }
                                ?: Flight(record.revision, summaries.start(ride, record.revision))
                        flight = current
                        if (!current.answer.isCompleted) SummaryState.pending
                        else
                            when (val answer = completed(current.answer)) {
                                is SummaryAnswer.Ready -> {
                                    summary = answer.summary
                                    SummaryState.ready
                                }
                                SummaryAnswer.Unavailable -> SummaryState.unavailable
                            }
                    }
                }
            }
        val exporting = exports.exporting()
        val readback =
            if (health?.kind == HealthProviderKind.healthConnect)
                read(healthWork.work(rideId)).filter { it.scope is HealthWorkScope.Readback }
            else null
        val view =
            RideDetailView(
                ride =
                    RideView(
                        rideId,
                        ride.startedUtcUs,
                        ride.end?.timerUs ?: ride.checkpoint.timerUs,
                        ride.phase,
                        ride.interrupted,
                        ride.example,
                        ride.recordedBy,
                        ride.options,
                    ),
                laps = ActiveTimeline(maxOf(0L, summary?.end?.elapsedUs ?: summaryEnd(ride, events)), events).laps.size,
                summary = summary?.let { summaryView(it, ride) },
                summaryState = state,
                route = summary?.route?.takeIf { it.isNotEmpty() },
                health = HealthViews.of(record, health?.kind, readback),
                allowed =
                    RideDetailAllowed(
                        finish = interrupted(ride),
                        discard = interrupted(ride),
                        delete = RideRules.deletable(ride, exporting),
                        export =
                            if (RideRules.exportReady(ride)) Availability(true, null)
                            else Availability(false, FailureCode.exportNotReady),
                        healthRefresh = available(ride.phase == RidePhase.completed && health != null),
                        healthRetry =
                            available(
                                health?.kind == HealthProviderKind.healthConnect &&
                                    record.publish.any { PublicationRules.retryAllowed(it.state, it.reason) }
                            ),
                    ),
            )
        return Detail(view, flight)
    }

    private fun completed(answer: Deferred<SummaryAnswer>): SummaryAnswer =
        try {
            @OptIn(ExperimentalCoroutinesApi::class) answer.getCompleted()
        } catch (cancelled: CancellationException) {
            throw ApiFailureException(failure(FailureCode.unknown))
        } catch (failed: StoreFailure) {
            throw ApiFailureException(readFailed(failed.failed))
        } catch (_: Throwable) {
            throw ApiFailureException(failure(FailureCode.unknown))
        }

    private fun summaryView(summary: RideSummary, ride: Ride): SummaryView {
        val method = ride.options.batteryMethod
        val consumption = SelectedConsumption.of(method, summary.battery)
        return SummaryView(
            timerUs = summary.end.timerUs,
            distanceM =
                summary.distance[ride.options.distanceSource]
                    ?.takeIf { it.coveredUs > 0 }
                    ?.let { it.distanceQ / MetricCatalog.of(MetricId.distanceMeters).quantum.toDouble() },
            averageSpeedMps = summary.averageSpeedMps,
            batteryMethod = method,
            energyWh = consumption.energy?.takeIf { it.available }?.amount,
            chargeAh = consumption.charge?.takeIf { it.available }?.amount,
            averagePowerW = summary.averagePowerW,
            averageCadenceRpm = summary.averageCadenceRpm,
            averageHeartRateBpm = summary.averageHeartRateBpm,
            ascentM = summary.ascentM,
            activeEnergyKcal = summary.activeEnergyKcal,
            physiologyBackfilled = summary.physiologyBackfilled,
        )
    }

    private suspend fun summaryEnd(ride: Ride, events: List<RideEvent>): Long =
        when (ride.phase) {
            RidePhase.completed -> requireNotNull(ride.end).elapsedUs
            RidePhase.interrupted -> {
                val last = (measurements.lastFact(ride.id) as? StoreResult.Ok)?.value
                FinishBoundary.of(ride.startedUtcUs, last, events).elapsedUs
            }
            else -> ride.checkpoint.elapsedUs
        }

    private fun row(record: RideRecord, exporting: Set<RideId>): HistoryRow {
        val ride = record.ride
        return HistoryRow(
            rideId = ride.id,
            startedUtcUs = ride.startedUtcUs,
            elapsedUs = ride.end?.elapsedUs ?: ride.checkpoint.elapsedUs,
            phase = ride.phase,
            interrupted = ride.interrupted,
            indoor = ride.options.indoor,
            example = ride.example,
            health = HealthViews.of(record, health?.kind, null),
            open =
                if (ride.phase in recordingPhases) Availability(false, FailureCode.rideActive)
                else Availability(true, null),
            delete = RideRules.deletable(ride, exporting),
        )
    }

    private fun interrupted(ride: Ride): Availability = available(ride.phase == RidePhase.interrupted)

    private fun available(enabled: Boolean): Availability =
        if (enabled) Availability(true, null) else Availability(false, FailureCode.actionUnavailable)

    private fun <T> read(result: StoreResult<T>): T =
        when (result) {
            is StoreResult.Ok -> result.value
            is StoreResult.Failed -> throw ApiFailureException(readFailed(result))
        }

    private fun readFailed(read: StoreResult.Failed): ApiFailure {
        val mapped = readFailure(read.error)
        if (mapped.code == FailureCode.storageRefused) refuse(read)
        return mapped
    }

    private fun text(cursor: RideCursor): String = "${cursor.startedUtcUs}:${cursor.rideId.value}"

    private fun cursor(text: String): RideCursor {
        val split = text.indexOf(':')
        require(split > 0) { "a History cursor comes from a page: $text" }
        return RideCursor(text.substring(0, split).toLong(), RideId(text.substring(split + 1)))
    }

    private companion object {
        val recordingPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)
    }
}
