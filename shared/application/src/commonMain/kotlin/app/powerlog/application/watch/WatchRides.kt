package app.powerlog.application.watch

import app.powerlog.application.Deadlines
import app.powerlog.application.Logs
import app.powerlog.application.PagePolicy
import app.powerlog.application.api.Answer
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.answer
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.newId
import app.powerlog.application.health.HealthWorkPolicy
import app.powerlog.application.health.WatchHealthItems
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.Responder
import app.powerlog.application.port.WatchLink
import app.powerlog.application.port.WatchLinkAvailability
import app.powerlog.application.port.WatchLinkEvents
import app.powerlog.application.recording.LivePublisher
import app.powerlog.application.recording.RecordingState
import app.powerlog.application.recording.RideEvents
import app.powerlog.application.recording.RideRecorder
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthOutcomeRules
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.health.HealthIds
import app.powerlog.domain.health.HealthPlan
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.IndexedRoutePoint
import app.powerlog.domain.health.RoutePoint
import app.powerlog.domain.health.RouteReduction
import app.powerlog.domain.recording.CommandIssuer
import app.powerlog.domain.recording.RideCommand
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.RideSignal
import app.powerlog.domain.recording.RideTransition
import app.powerlog.domain.recording.StartPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class WatchRides(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val clock: Clock,
    private val link: WatchLink,
    private val codec: WatchCodec,
    private val state: RecordingState,
    private val live: LivePublisher,
    private val units: () -> UnitSystem,
    private val writer: ArchiveWriter,
    private val rides: RideRepository,
    private val measurements: MeasurementRepository,
    private val logs: Logs,
    private val deadlines: Deadlines,
    private val mapSamples: suspend (Ride, List<HealthOriginal>) -> List<HealthSample>,
) : RideEvents, WatchHealthItems {
    lateinit var recorder: RideRecorder

    private var opened = false
    private val held = mutableListOf<() -> Unit>()
    private var revision = clock.utcUs()
    private var watched: Ride? = null
    private val laps = ArrayDeque<Long>()
    private var lapCount = 0
    private var routine: Job? = null
    private var routineMonoUs = Long.MIN_VALUE
    private var handshake: Job? = null
    private var replay: Job? = null
    private var reachableWait: CompletableDeferred<Unit>? = null
    private var unreachableLogged = false
    private val quantities = ArrayDeque<HealthPlan.Quantity>()
    private val route = ArrayDeque<IndexedRoutePoint>()
    private var stride = 1
    private var finalPlan = CompletableDeferred<Unit>()
    private var restored = false
    private var ended: Pair<RideId, WatchMessage.RideMessage>? = null

    val events: WatchLinkEvents =
        object : WatchLinkEvents {
            override fun availabilityChanged(availability: WatchLinkAvailability) {
                if (!availability.reachable) return
                unreachableLogged = false
                reachableWait?.complete(Unit)
                reachableWait = null
            }

            override fun received(message: ByteArray, respond: Responder, at: ClockReading) {
                if (opened) receive(message, respond, at) else held += { receive(message, respond, at) }
            }
        }

    fun opened() {
        if (opened) return
        opened = true
        val waiting = held.toList()
        held.clear()
        waiting.forEach { it() }
    }

    override fun planned(
        rideId: RideId,
        quantities: List<HealthPlan.Quantity>,
        points: List<IndexedRoutePoint>,
        stride: Int,
        final: Boolean,
    ) {
        if (watched?.id != rideId) return
        this.quantities += quantities
        route += points
        this.stride = stride
        if (final) finalPlan.complete(Unit)
    }

    override fun rideTransitioned(ride: Ride, transition: RideTransition, eventId: EventId?, events: List<RideEvent>) {
        if (!ride.options.useWatch) return
        for (event in events) if (event.kind == RideEventKind.lap) lap(event.utcUs)
        when (transition) {
            RideTransition.prepared -> {
                follow(ride)
                val waiting = recorder.waiting?.takeIf { it.rideId == ride.id }
                handshake = scope.launch(this.ride) { handshake(ride, eventId, waiting?.requestId) }
            }
            RideTransition.completed,
            RideTransition.discarded,
            RideTransition.failed -> end(ride, transition)
            RideTransition.interruptedAtLaunch -> Unit
            else -> {
                watched = ride
                changed()
            }
        }
    }

    override fun batchCommitted(
        rideId: RideId,
        rows: TelemetryRows,
        sessions: Map<Int, ControllerSession>,
        fixes: List<LocationFix>,
        edges: List<GpsEdge>,
    ) = Unit

    override fun rideRestored(ride: Ride) {
        if (!ride.options.useWatch || ride.phase !in openPhases) return
        follow(ride)
        restored = true
        finalPlan.complete(Unit)
        scope.launch(this.ride) {
            val events = (measurements.events(ride.id) as? StoreResult.Ok)?.value.orEmpty()
            events.filter { it.kind == RideEventKind.lap }.forEach { lap(it.utcUs) }
            changed()
        }
    }

    private fun follow(ride: Ride) {
        watched = ride
        laps.clear()
        lapCount = 0
        quantities.clear()
        route.clear()
        stride = 1
        finalPlan = CompletableDeferred()
        replay?.cancel()
        routine?.cancel()
        restored = false
        ended = null
        routine =
            scope.launch(this.ride) {
                var nextMonoUs = clock.monoUs() + WatchProtocol.rideIntervalUs
                while (true) {
                    deadlines.until(nextMonoUs)
                    nextMonoUs += WatchProtocol.rideIntervalUs
                    if (clock.monoUs() - routineMonoUs >= WatchProtocol.routineMinIntervalUs) send(message())
                }
            }
    }

    private fun lap(utcUs: Long) {
        lapCount++
        laps.addLast(utcUs)
        while (laps.size > WatchProtocol.maxLaps) laps.removeFirst()
    }

    private fun changed() {
        revision++
        send(message())
    }

    private fun end(ride: Ride, transition: RideTransition) {
        handshake?.cancel()
        routine?.cancel()
        routine = null
        if (watched?.id != ride.id) return
        watched = ride
        revision++
        val endedUtcUs = clock.utcUs()
        val phase = if (transition == RideTransition.completed) WatchPhase.completed else WatchPhase.discarded
        ended = ride.id to message(phase, endedUtcUs, items = false) as WatchMessage.RideMessage
        val wasRestored = restored
        replay =
            scope.launch(this.ride) {
                if (transition == RideTransition.completed && ride.options.saveToHealth)
                    deadlines.await(finalPlan, clock.monoUs() + HealthWorkPolicy.portCallBudgetUs)
                if (wasRestored && transition == RideTransition.completed) newestStoredFix(ride)?.let { route += it }
                revision++
                val terminal = message(phase, endedUtcUs)
                val untilMonoUs = clock.monoUs() + WatchProtocol.replayUs
                while (clock.monoUs() < untilMonoUs) {
                    val sentMonoUs = clock.monoUs()
                    val reply = deadlines.await(send(terminal), sentMonoUs + WatchProtocol.resendUs)
                    if (reply is WatchMessage.AckMessage) break
                    deadlines.until(sentMonoUs + WatchProtocol.resendUs)
                }
                if (watched?.id == ride.id) watched = null
            }
    }

    private suspend fun handshake(ride: Ride, eventId: EventId?, requestId: String?) {
        val event = eventId ?: return
        val expiresUtcUs = ride.startedUtcUs + StartPolicy.windowUs
        val start =
            WatchMessage.StartMessage(
                eventId = event,
                requestId = requestId,
                rideId = ride.id,
                startedUtcUs = ride.startedUtcUs,
                expiresUtcUs = expiresUtcUs,
                options =
                    WatchRideOptions(
                        ride.options.indoor,
                        ride.options.saveToHealth,
                        ride.options.recordGps,
                        ride.options.distanceSource,
                    ),
                workout = WorkoutIds(HealthIds.workout(ride.id), HealthIds.syncVersion),
            )
        if (!link.availability().reachable) {
            val launched = answer<Unit> { link.launchWatchApp(ride.options.indoor, it) }
            if (launched is Answer.Failed) {
                logs.failure("watchLink", launched.error)
                recorder.observe(RideSignal.StartFailed(ride.id, FailureCode.watchLaunchFailed))
                return
            }
        }
        val expiresMonoUs = clock.monoUs() + (expiresUtcUs - clock.utcUs())
        while (clock.monoUs() < expiresMonoUs) {
            if (!link.availability().reachable) {
                val waiting = CompletableDeferred<Unit>().also { reachableWait = it }
                deadlines.await(waiting, expiresMonoUs) ?: return
            }
            val sentMonoUs = clock.monoUs()
            when (val reply = deadlines.await(send(start), expiresMonoUs)) {
                is WatchMessage.AckMessage -> {
                    when (val code = reply.error) {
                        null -> recorder.observe(RideSignal.StartConfirmed(ride.id))
                        FailureCode.startCancelled ->
                            recorder.decide(
                                RideCommand.Control(EventId(newId()), ride.id, RideAction.discard, CommandIssuer.watch)
                            )
                        else -> {
                            logs.event(LogLevel.warning, "failure", "source" to "watch", "error" to code)
                            recorder.observe(RideSignal.StartFailed(ride.id, code))
                        }
                    }
                    return
                }
                else -> deadlines.until(sentMonoUs + WatchProtocol.resendUs)
            }
        }
    }

    private fun receive(bytes: ByteArray, respond: Responder, at: ClockReading) {
        when (val decoded = codec.decode(bytes)) {
            WatchDecodeResult.VersionMismatch -> respond.reply(ack(FailureCode.watchVersionMismatch))
            WatchDecodeResult.Invalid -> respond.reply(ack(FailureCode.watchMessageInvalid))
            is WatchDecodeResult.Valid ->
                scope.launch(ride) {
                    val reply =
                        try {
                            answer(decoded.message, at)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            logs.unexpected("WatchRides", error)
                            WatchMessage.AckMessage(FailureCode.unknown, null)
                        }
                    respond.reply(codec.encode(reply))
                }
        }
    }

    private suspend fun answer(message: WatchMessage, at: ClockReading): WatchMessage =
        when (message) {
            is WatchMessage.StartRequestMessage -> started(message)
            is WatchMessage.CancelStartMessage -> cancelled(message)
            is WatchMessage.CommandMessage -> commanded(message)
            is WatchMessage.HealthSamplesMessage -> samples(message, at)
            is WatchMessage.HealthOutcomeMessage -> outcome(message)
            else -> WatchMessage.AckMessage(FailureCode.watchMessageInvalid, null)
        }

    private suspend fun started(message: WatchMessage.StartRequestMessage): WatchMessage {
        val options = message.options
        val request = StartRequest(options.indoor, useWatch = true, options.saveToHealth, options.recordGps)
        return when (val outcome = recorder.startFromWatch(request, message.requestId)) {
            is Outcome.Ok -> message()
            is Outcome.Failed -> WatchMessage.AckMessage(outcome.failure.code, null)
            Outcome.Cancelled -> WatchMessage.AckMessage(FailureCode.startCancelled, null)
        }
    }

    private suspend fun cancelled(message: WatchMessage.CancelStartMessage): WatchMessage {
        val waiting = recorder.waiting
        val open = state.open
        if (
            waiting != null &&
                open?.id == waiting.rideId &&
                open.options.useWatch &&
                (message.eventId == waiting.event ||
                    message.requestId != null && message.requestId == waiting.requestId) &&
                (message.rideId == null || message.rideId == waiting.rideId)
        )
            recorder.decide(
                RideCommand.Control(EventId(newId()), waiting.rideId, RideAction.discard, CommandIssuer.watch)
            )
        else if (waiting == null && message.requestId != null) recorder.cancelStart(message.requestId)
        return WatchMessage.AckMessage(null, null)
    }

    private suspend fun commanded(message: WatchMessage.CommandMessage): WatchMessage {
        if (clock.utcUs() > message.expiresUtcUs) return WatchMessage.AckMessage(FailureCode.actionUnavailable, null)
        val open = state.open
        if (open == null || open.id != message.rideId || !open.options.useWatch)
            return WatchMessage.AckMessage(FailureCode.rideChanged, null)
        val command = RideCommand.Control(EventId(newId()), message.rideId, message.action, CommandIssuer.watch)
        return when (val outcome = recorder.decide(command)) {
            is Outcome.Ok -> ended?.takeIf { it.first == message.rideId }?.second ?: message()
            is Outcome.Failed -> WatchMessage.AckMessage(outcome.failure.code, null)
            Outcome.Cancelled -> WatchMessage.AckMessage(FailureCode.actionUnavailable, null)
        }
    }

    private suspend fun samples(message: WatchMessage.HealthSamplesMessage, at: ClockReading): WatchMessage {
        val record =
            when (val read = rides.ride(message.rideId)) {
                is StoreResult.Ok -> read.value ?: return WatchMessage.AckMessage(null, message.throughSequence)
                is StoreResult.Failed -> return WatchMessage.AckMessage(null, null)
            }
        message.heartRate?.let { recorder.heartRate(message.rideId, it, at) }
        val ride = record.ride
        val originals = message.samples.filter { !HealthIds.isOwn(it.syncId) }
        val samples = mapSamples(ride, originals)
        if (samples.isEmpty()) return WatchMessage.AckMessage(null, message.throughSequence)
        val result = writer.commit(WriteBatch(listOf(Mutation.AppendHealthSamples(ride.id, samples))))
        if (result is StoreResult.Failed) logs.storageFailed(result.error, "command")
        val stored = (result as? StoreResult.Ok)?.value is Commit.Committed
        return WatchMessage.AckMessage(null, message.throughSequence.takeIf { stored })
    }

    private suspend fun outcome(message: WatchMessage.HealthOutcomeMessage): WatchMessage {
        val record =
            when (val read = rides.ride(message.rideId)) {
                is StoreResult.Ok -> read.value ?: return WatchMessage.AckMessage(null, null)
                is StoreResult.Failed -> return WatchMessage.AckMessage(commitFailure(read.error).code, null)
            }
        val observed = RideHealth(HealthProviderKind.appleHealth, message.outcome, message.reason, message.workoutId)
        val next = HealthOutcomeRules.next(record.health, observed) ?: return WatchMessage.AckMessage(null, null)
        return when (val result = writer.commit(WriteBatch(listOf(Mutation.SaveHealthOutcome(record.ride.id, next))))) {
            is StoreResult.Ok -> {
                if (result.value is Commit.Committed)
                    logs.event(
                        if (next.outcome == HealthOutcome.failed || next.outcome == HealthOutcome.unknown)
                            LogLevel.warning
                        else LogLevel.info,
                        "healthOutcome",
                        "outcome" to next.outcome,
                        "reason" to next.reason,
                        "provider" to next.provider,
                    )
                WatchMessage.AckMessage(null, null)
            }
            is StoreResult.Failed -> {
                logs.storageFailed(result.error, "health")
                WatchMessage.AckMessage(commitFailure(result.error).code, null)
            }
        }
    }

    private fun message(terminal: WatchPhase? = null, endedUtcUs: Long? = null, items: Boolean = true): WatchMessage {
        val ride = watched ?: return WatchMessage.AckMessage(null, null)
        val open = state.open?.takeIf { it.id == ride.id }
        val now = clock.read()
        val phase =
            terminal
                ?: when (open?.phase ?: ride.phase) {
                    RidePhase.preparing -> WatchPhase.preparing
                    RidePhase.running -> WatchPhase.running
                    RidePhase.paused -> WatchPhase.paused
                    RidePhase.interrupted -> WatchPhase.interrupted
                    RidePhase.completed -> WatchPhase.completed
                    else -> WatchPhase.discarded
                }
        val sentQuantities = if (items) take(quantities, WatchProtocol.maxQuantities) else emptyList()
        val sentRoute = if (items) take(route, WatchProtocol.maxRoutePoints) else emptyList()
        return WatchMessage.RideMessage(
                rideId = ride.id,
                stateRevision = revision,
                phase = phase,
                timer = timer(open, ride, now),
                lapsUtcUs = laps.toList(),
                lapCount = lapCount,
                units = units(),
                display = display(ride, now).takeIf { phase == WatchPhase.running },
                quantities = sentQuantities,
                routeStride = stride,
                route = sentRoute,
                endedUtcUs = endedUtcUs,
            )
            .also { if (terminal == null) routineMonoUs = now.monoUs }
    }

    private fun timer(open: Ride?, ride: Ride, now: ClockReading): RideTimer {
        val rideClock = state.lifecycle.clock
        val origin = rideClock?.timerOriginMonoUs
        if (open == null) return RideTimer(TimerState.frozen, (ride.end ?: ride.checkpoint).timerUs, null)
        if (open.phase != RidePhase.running || rideClock == null || origin == null)
            return RideTimer(TimerState.frozen, RideLifecycle.timing(state.lifecycle, now).timerUs, null)
        return RideTimer(
            TimerState.running,
            rideClock.timerBaseUs,
            now.utcUs - (now.monoUs - origin) - rideClock.timerBaseUs,
        )
    }

    private fun display(ride: Ride, now: ClockReading): WatchDisplay {
        val newest = live.snapshot().newest
        val power = newest[MetricId.humanPowerW]
        val hold = requireNotNull(MetricCatalog.of(MetricId.humanPowerW).displayHoldUs)
        val acquired = power?.acquiredMonoUs ?: now.monoUs - hold
        return WatchDisplay(
            powerW = power?.value,
            cadenceRpm = newest[MetricId.cadenceRpm]?.value,
            distanceM = live.distanceM(ride.options.distanceSource),
            freshUntilUtcUs = now.utcUs + (acquired + hold - now.monoUs),
        )
    }

    private fun <T> take(queue: ArrayDeque<T>, limit: Int): List<T> {
        val taken = mutableListOf<T>()
        while (taken.size < limit && queue.isNotEmpty()) taken += queue.removeFirst()
        return taken
    }

    private fun send(message: WatchMessage): CompletableDeferred<WatchMessage?> {
        val answered = CompletableDeferred<WatchMessage?>()
        if (message !is WatchMessage.RideMessage && message !is WatchMessage.StartMessage) {
            answered.complete(null)
            return answered
        }
        link.send(
            codec.encode(message),
            Reply(
                onOk = { bytes -> answered.complete((codec.decode(bytes) as? WatchDecodeResult.Valid)?.message) },
                onFailed = { error ->
                    unreachable(error)
                    answered.complete(null)
                },
            ),
        )
        return answered
    }

    private suspend fun newestStoredFix(ride: Ride): IndexedRoutePoint? {
        val reduction = RouteReduction.Apple(HealthPolicy.appleRouteCapacity)
        var after: LocationKey? = null
        while (true) {
            val page =
                (measurements.locations(ride.id, TimeRange(0, Long.MAX_VALUE), after, PagePolicy.rows)
                        as? StoreResult.Ok)
                    ?.value ?: return reduction.latest
            page.fixes.mapNotNull(RoutePoint::of).forEach { reduction.offer(it) }
            after = page.next ?: return reduction.latest
        }
    }

    private fun unreachable(error: PortError) {
        if (error.kind == PortErrorKind.notReachable) {
            if (unreachableLogged) return
            unreachableLogged = true
        }
        logs.failure("watchLink", error)
    }

    private fun ack(code: FailureCode): ByteArray = codec.encode(WatchMessage.AckMessage(code, null))

    private companion object {
        val openPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)
    }
}
