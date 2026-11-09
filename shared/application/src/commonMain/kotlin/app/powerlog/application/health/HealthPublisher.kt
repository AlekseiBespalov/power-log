package app.powerlog.application.health

import app.powerlog.application.Executors
import app.powerlog.application.FactReader
import app.powerlog.application.FactSink
import app.powerlog.application.FactStreams
import app.powerlog.application.orThrow
import app.powerlog.application.port.AppleWorkoutSession
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.Clock
import app.powerlog.application.port.FinishedWorkout
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.LiveHeartRate
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.port.SessionOptions
import app.powerlog.application.port.SessionState
import app.powerlog.application.port.WorkoutSessionEvents
import app.powerlog.application.recording.RideEvents
import app.powerlog.application.recording.RideRecorderEntries
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.HealthWriter
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.health.HealthIds
import app.powerlog.domain.health.HealthPlan
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.HealthPublication
import app.powerlog.domain.health.IndexedRoutePoint
import app.powerlog.domain.health.RoutePoint
import app.powerlog.domain.health.RouteReduction
import app.powerlog.domain.recording.RideSignal
import app.powerlog.domain.recording.RideTransition
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalAtomicApi::class)
internal class HealthPublisher(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val clock: Clock,
    private val provider: HealthProvider,
    private val rides: RideRepository,
    private val repository: HealthRepository,
    private val measurements: MeasurementRepository,
    private val writer: ArchiveWriter,
    private val readback: HealthReadback,
    private val scheduler: BackgroundScheduler?,
    private val armed: (RideId, Long?) -> Unit,
    private val interrupted: (RideId) -> Unit,
) : RideEvents {
    var recorder: RideRecorderEntries? = null
    var watchItems: WatchHealthItems? = null

    private class Live(var ride: Ride, val watch: Boolean) {
        val samples = mutableListOf<TelemetrySample>()
        val fixes = mutableListOf<LocationFix>()
        val sessions = mutableMapOf<Int, ControllerSession>()
        val quantities = ArrayDeque<HealthPlan.Quantity>()
        val route = RouteReduction.Apple(HealthPolicy.appleRouteCapacity)
        var nextSecond = 0L
        var begun = false
        var ended = false
        val unsaved = mutableSetOf<HealthComponent>()
        val written = mutableSetOf<HealthComponent>()
    }

    private val live = mutableMapOf<RideId, Live>()
    private val inbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val current = AtomicReference<RideId?>(null)
    private val session: AppleWorkoutSession? = provider.session
    private val android = provider.kind == HealthProviderKind.healthConnect

    init {
        scope.launch(executors.health) { for (job in inbox) runCatching { job() } }
        session?.attach(
            object : WorkoutSessionEvents {
                override fun stateChanged(state: SessionState, atUtcUs: Long) {
                    if (state == SessionState.ended) post { lost() }
                }

                override fun failed(error: PortError) = post { lost() }

                override fun heartRateReceived(heartRate: LiveHeartRate, at: ClockReading) {
                    current.load()?.let { recorder?.heartRate(it, heartRate, at) }
                }

                override fun samplesCollected(samples: List<HealthOriginal>, at: ClockReading) = post {
                    phoneSamples(samples)
                }
            }
        )
    }

    override fun rideTransitioned(ride: Ride, transition: RideTransition, eventId: EventId?, events: List<RideEvent>) =
        post {
            when (transition) {
                RideTransition.prepared -> prepared(ride)
                RideTransition.started -> Unit
                RideTransition.paused -> live[ride.id]?.takeIf { it.begun && !it.ended }?.let { session?.pause() }
                RideTransition.resumed -> live[ride.id]?.takeIf { it.begun && !it.ended }?.let { session?.resume() }
                RideTransition.lapped -> lapped(ride, events)
                RideTransition.completed -> completed(ride)
                RideTransition.discarded,
                RideTransition.failed -> discarded(ride)
                RideTransition.interruptedAtLaunch -> interrupted(ride.id)
                RideTransition.interruptedByStorage -> Unit
            }
        }

    override fun batchCommitted(
        rideId: RideId,
        rows: TelemetryRows,
        sessions: Map<Int, ControllerSession>,
        fixes: List<LocationFix>,
        edges: List<GpsEdge>,
    ) {
        val samples = (0 until rows.rows).map { sample(rows, it) }
        post {
            val ride = live[rideId] ?: return@post
            ride.sessions += sessions
            ride.samples += samples
            ride.fixes += fixes
            fixes.mapNotNull(RoutePoint::of).forEach { offer(ride, it) }
            edges.mapNotNullTo(ride.quantities) { HealthPublication.distance(rideId, it) }
            plan(ride, stopped = false)
            send(ride)
        }
    }

    override fun rideRestored(ride: Ride) = Unit

    fun deleted(rideId: RideId) {
        post { live.remove(rideId) }
        scheduler?.cancel(rideId.value)
    }

    private suspend fun prepared(ride: Ride) {
        val writerKind = ride.options.healthWriter ?: return
        if (android) return
        val state = Live(ride, watch = writerKind == HealthWriter.watch)
        live[ride.id] = state
        current.store(ride.id)
        if (state.watch) return
        val session = session ?: return
        val workout = HealthPublication.workout(ride, ActiveTimeline(0, emptyList()))
        val answer =
            called<Unit> { session.begin(workout, SessionOptions(ride.options.indoor, ride.options.recordGps), it) }
        val recorder = recorder ?: return
        when (answer) {
            is Called.Ok -> {
                state.begun = true
                recorder.observe(RideSignal.StartConfirmed(ride.id))
            }
            is Called.Failed -> {
                live.remove(ride.id)
                recorder.observe(RideSignal.StartFailed(ride.id, beginFailure(answer.error)))
            }
            Called.Abandoned -> {
                live.remove(ride.id)
                session.discard(clock.utcUs())
                recorder.observe(RideSignal.StartFailed(ride.id, FailureCode.startUnconfirmed))
            }
        }
    }

    private fun beginFailure(error: PortError): FailureCode =
        when (error.kind) {
            PortErrorKind.denied -> FailureCode.healthDenied
            PortErrorKind.locked -> FailureCode.healthLocked
            PortErrorKind.unsupported -> FailureCode.healthUnavailable
            PortErrorKind.uiUnavailable -> FailureCode.uiUnavailable
            else -> FailureCode.startInterrupted
        }

    private fun plan(ride: Live, stopped: Boolean) {
        val cutoffUtcUs = ride.ride.end?.utcUs ?: clock.utcUs()
        val nowUtcUs = clock.utcUs()
        val rows = TelemetryRows.of(ride.samples)
        while (true) {
            val bounds = HealthPublication.bounds(ride.ride.startedUtcUs, cutoffUtcUs, ride.nextSecond)
            if (bounds.range.endUtcUs <= bounds.range.startUtcUs) break
            if (!HealthPublication.eligible(bounds, nowUtcUs, stopped)) break
            for (quantity in secondQuantities) HealthPublication.second(
                    ride.ride.id,
                    bounds,
                    quantity,
                    rows,
                    ride.sessions,
                    ride.fixes,
                )
                ?.let {
                    ride.quantities += it
                }
            ride.nextSecond++
        }
        val keepFromUs = ride.nextSecond * SECOND_US - KEEP_US
        ride.samples.removeAll { it.elapsedUs < keepFromUs }
        ride.fixes.removeAll { it.elapsedUs < keepFromUs }
    }

    private fun offer(ride: Live, point: RoutePoint) {
        if (!ride.watch || !ride.ride.options.recordGps) return
        val index = ride.route.offer(point) ?: return
        if (index % ride.route.stride == 0L)
            watchItems?.planned(
                ride.ride.id,
                emptyList(),
                listOf(IndexedRoutePoint(index, point)),
                ride.route.stride,
                false,
            )
    }

    private suspend fun send(ride: Live) {
        if (ride.watch) {
            if (ride.quantities.isEmpty()) return
            val items = ride.quantities.toList()
            ride.quantities.clear()
            watchItems?.planned(ride.ride.id, items, emptyList(), ride.route.stride, final = false)
            return
        }
        val session = session ?: return
        if (!ride.begun || ride.ended) return
        while (ride.quantities.isNotEmpty() && quantityComponents.none { it in ride.unsaved }) {
            val chunk =
                List(minOf(HealthWorkPolicy.appleQuantitiesPerCall, ride.quantities.size)) {
                    ride.quantities.removeFirst()
                }
            val held = chunk.map { component(it.quantity) }.toSet()
            when (val answer = called<Unit> { session.add(chunk, it) }) {
                is Called.Ok -> ride.written += held
                is Called.Failed -> stopQuantities(ride, reason(answer.error), emptySet())
                Called.Abandoned -> stopQuantities(ride, HealthReason.ambiguous, held)
            }
        }
    }

    private suspend fun stopQuantities(ride: Live, reason: HealthReason, uncertain: Set<HealthComponent>) {
        ride.quantities.clear()
        val failed = quantityComponents.filter { it !in ride.written || it in uncertain }
        ride.written -= uncertain
        ride.unsaved += quantityComponents
        components(ride.ride.id, failed.associateWith { ComponentState.failed to reason })
    }

    private suspend fun lapped(ride: Ride, events: List<RideEvent>) {
        val state = live[ride.id] ?: return
        val session = session ?: return
        if (state.watch || !state.begun || state.ended || HealthComponent.laps in state.unsaved) return
        for (event in events.filter { it.kind == app.powerlog.domain.core.ride.RideEventKind.lap }) {
            when (val answer = called<Unit> { session.addLap(event.utcUs, it) }) {
                is Called.Ok -> state.written += HealthComponent.laps
                is Called.Failed -> return lapsStopped(state, reason(answer.error))
                Called.Abandoned -> {
                    state.written -= HealthComponent.laps
                    return lapsStopped(state, HealthReason.ambiguous)
                }
            }
        }
    }

    private suspend fun lapsStopped(ride: Live, reason: HealthReason) {
        ride.unsaved += HealthComponent.laps
        components(ride.ride.id, mapOf(HealthComponent.laps to (ComponentState.failed to reason)))
    }

    private suspend fun completed(ride: Ride) {
        val end = ride.end ?: return
        if (android) {
            if (end.utcUs <= ride.startedUtcUs) {
                clockFailure(ride)
                return
            }
            readback.schedule(ride)
            scheduler?.schedule(ride.id.value, null, ScheduleKind.replace)
            armed(ride.id, end.utcUs + HealthPolicy.readbackAfterFinishUs.first())
            return
        }
        val state = live.remove(ride.id)
        release(ride.id)
        if (state != null) {
            state.ride = ride
            plan(state, stopped = true)
            if (state.watch) finishWatch(state) else finishSession(state)
        }
        readback.schedule(ride)
        armed(ride.id, end.utcUs + HealthPolicy.readbackAfterFinishUs.first())
    }

    private fun finishWatch(ride: Live) {
        val latest = ride.route.latest?.takeIf { it.index % ride.route.stride != 0L }
        watchItems?.planned(
            ride.ride.id,
            ride.quantities.toList(),
            listOfNotNull(latest),
            ride.route.stride,
            final = true,
        )
        ride.quantities.clear()
    }

    private suspend fun finishSession(ride: Live) {
        val session = session ?: return
        if (!ride.begun || ride.ended) {
            recorder?.healthSessionEnded(ride.ride.id)
            return
        }
        send(ride)
        val end = requireNotNull(ride.ride.end)
        val points = if (ride.ride.options.recordGps) routePoints(ride.ride) else emptyList()
        val route = points.takeIf { it.isNotEmpty() }?.let { HealthPlan.Route(HealthIds.route(ride.ride.id), it) }
        val answer = called<FinishedWorkout> { session.finish(end.utcUs, route, it) }
        ride.ended = true
        when (answer) {
            is Called.Ok -> saved(ride, answer.value, route != null)
            is Called.Failed -> {
                val reason = reason(answer.error)
                val current = repository.outcome(ride.ride.id).orThrow() ?: return
                commit(
                    listOf(
                        Mutation.SaveHealthOutcome(
                            ride.ride.id,
                            current.copy(outcome = HealthOutcome.failed, reason = reason),
                        )
                    ) + unsavedRows(ride, ComponentState.failed, reason)
                )
            }
            Called.Abandoned -> Unit
        }
        recorder?.healthSessionEnded(ride.ride.id)
    }

    private suspend fun saved(ride: Live, finished: FinishedWorkout, routeSent: Boolean) {
        val current = repository.outcome(ride.ride.id).orThrow() ?: return
        val rows = repository.work(ride.ride.id).orThrow().filter { it.scope is HealthWorkScope.Publish }
        val changed = rows.mapNotNull { row ->
            val component = (row.scope as HealthWorkScope.Publish).component
            when {
                row.state != ComponentState.pending -> null
                component == HealthComponent.readback -> null
                component == HealthComponent.workout || component == HealthComponent.association ->
                    row.copy(state = ComponentState.saved)
                component == HealthComponent.route ->
                    row.copy(
                        state =
                            if (routeSent && finished.routeSaved) ComponentState.saved else ComponentState.notRequested
                    )
                component in ride.written -> row.copy(state = ComponentState.saved)
                else -> row.copy(state = ComponentState.notRequested)
            }
        }
        val uncertain = rows.any { it.state == ComponentState.failed && it.reason == HealthReason.ambiguous }
        val observed =
            if (uncertain)
                current.copy(
                    outcome = HealthOutcome.unknown,
                    reason = HealthReason.ambiguous,
                    workoutId = finished.workoutId,
                )
            else current.copy(outcome = HealthOutcome.saved, reason = null, workoutId = finished.workoutId)
        commit(
            listOfNotNull(
                HealthOutcomeRules.next(current, observed)?.let { Mutation.SaveHealthOutcome(ride.ride.id, it) },
                changed.takeIf { it.isNotEmpty() }?.let(Mutation::PutHealthWork),
            )
        )
    }

    private suspend fun routePoints(ride: Ride): List<RoutePoint> {
        val reduction = RouteReduction.Apple(HealthPolicy.appleRouteCapacity)
        FactReader(measurements, ride.id, emptyMap())
            .feed(
                TimeRange(0, Long.MAX_VALUE),
                FactStreams(false, true, null),
                object : FactSink {
                    override fun append(
                        rows: TelemetryRows,
                        from: Int,
                        to: Int,
                        sessions: Map<Int, ControllerSession>,
                    ) = Unit

                    override fun append(fact: Fact) {
                        (fact as? Fact.Location)?.fix?.let(RoutePoint::of)?.let(reduction::offer)
                    }
                },
            )
        return reduction.result()
    }

    private suspend fun discarded(ride: Ride) {
        release(ride.id)
        val state = live.remove(ride.id) ?: return
        if (state.begun && !state.ended) session?.discard(clock.utcUs())
        recorder?.healthSessionEnded(ride.id)
    }

    private suspend fun lost() {
        for (state in live.values.filter { it.begun && !it.ended }) {
            state.ended = true
            state.quantities.clear()
            val current = repository.outcome(state.ride.id).orThrow() ?: continue
            commit(
                listOf(
                    Mutation.SaveHealthOutcome(
                        state.ride.id,
                        current.copy(outcome = HealthOutcome.failed, reason = HealthReason.builderLost),
                    )
                ) + unsavedRows(state, ComponentState.failed, HealthReason.builderLost)
            )
        }
    }

    private suspend fun phoneSamples(samples: List<HealthOriginal>) {
        val state = live.values.firstOrNull { !it.watch } ?: return
        val own = samples.filter { !HealthIds.isOwn(it.syncId) }
        if (own.isEmpty()) return
        val record = rides.ride(state.ride.id).orThrow() ?: return
        val mapped = readback.samples(record.ride, own)
        if (mapped.isNotEmpty()) commit(listOf(Mutation.AppendHealthSamples(state.ride.id, mapped)))
    }

    private suspend fun clockFailure(ride: Ride) {
        val current = repository.outcome(ride.id).orThrow() ?: return
        commit(
            listOf(
                Mutation.RemoveHealthWork(ride.id, null),
                Mutation.SaveHealthOutcome(
                    ride.id,
                    current.copy(outcome = HealthOutcome.unavailable, reason = HealthReason.clock),
                ),
            )
        )
    }

    private suspend fun unsavedRows(ride: Live, state: ComponentState, reason: HealthReason): List<Mutation> {
        val rows =
            repository.work(ride.ride.id).orThrow().filter {
                it.scope is HealthWorkScope.Publish &&
                    it.state != ComponentState.saved &&
                    (it.scope as HealthWorkScope.Publish).component != HealthComponent.readback
            }
        return listOfNotNull(
            rows
                .map { it.copy(state = state, reason = reason) }
                .takeIf { it.isNotEmpty() }
                ?.let(Mutation::PutHealthWork)
        )
    }

    private suspend fun components(rideId: RideId, changes: Map<HealthComponent, Pair<ComponentState, HealthReason?>>) {
        val rows = repository.work(rideId).orThrow().filter { it.scope is HealthWorkScope.Publish }
        val changed = rows.mapNotNull { row ->
            val change = changes[(row.scope as HealthWorkScope.Publish).component] ?: return@mapNotNull null
            row.copy(state = change.first, reason = change.second)
        }
        if (changed.isNotEmpty()) commit(listOf(Mutation.PutHealthWork(changed)))
    }

    private fun reason(error: PortError): HealthReason =
        when (error.kind) {
            PortErrorKind.denied -> HealthReason.permission
            PortErrorKind.locked -> HealthReason.locked
            PortErrorKind.rateLimited -> HealthReason.quota
            PortErrorKind.invalid -> HealthReason.invalid
            else -> HealthReason.transientFailure
        }

    private suspend fun commit(mutations: List<Mutation>) {
        if (mutations.isNotEmpty()) writer.commit(WriteBatch(mutations))
    }

    private fun post(block: suspend () -> Unit) {
        inbox.trySend(block)
    }

    private fun release(ride: RideId) {
        if (current.load() == ride) current.store(null)
    }

    private fun sample(rows: TelemetryRows, row: Int): TelemetrySample =
        TelemetrySample(
            RideId(""),
            rows.sessionNo[row],
            rows.seq[row].toLong(),
            rows.elapsedUs[row].toLong(),
            rows.utcUs[row].toLong(),
            rows.activeInterval[row].takeIf { it != 0 },
            rows.run[row],
            rows.quanta
                .mapNotNull { (metric, values) -> values[row].takeIf { !it.isNaN() }?.let { metric to it.toLong() } }
                .toMap(),
        )

    private companion object {
        const val SECOND_US = 1_000_000L
        const val KEEP_US = 10_000_000L
        val secondQuantities =
            listOf(HealthQuantity.riderPower, HealthQuantity.cyclingCadence, HealthQuantity.cyclingSpeed)
        val quantityComponents =
            setOf(
                HealthComponent.riderPower,
                HealthComponent.cyclingCadence,
                HealthComponent.cyclingSpeed,
                HealthComponent.cyclingDistance,
            )

        fun component(quantity: HealthQuantity): HealthComponent =
            when (quantity) {
                HealthQuantity.riderPower -> HealthComponent.riderPower
                HealthQuantity.cyclingCadence -> HealthComponent.cyclingCadence
                HealthQuantity.cyclingSpeed -> HealthComponent.cyclingSpeed
                HealthQuantity.cyclingDistance -> HealthComponent.cyclingDistance
            }
    }
}
