package app.powerlog.domain.recording

import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthWriter
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.FinishBoundary
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.RideTiming
import kotlinx.serialization.Serializable

public data class LifecycleState(public val open: Ride?, public val clock: RideClock?)

public data class RideClock(
    public val elapsedOriginMonoUs: Long,
    public val timerBaseUs: Long,
    public val timerOriginMonoUs: Long?,
    public val startDeadlineMonoUs: Long?,
    public val startEvent: EventId?,
    public val activeInterval: Int?,
    public val intervalStartUs: Long?,
    public val intervalsOpened: Int,
)

public data class StartContext(
    public val ownerForeign: Boolean,
    public val settingsSaveFailed: Boolean,
    public val settingsReadable: Boolean,
    public val bikeVerified: Boolean,
    public val options: RideOptions?,
    public val watchInstalled: Boolean,
    public val fromWatch: Boolean,
    public val locationDenied: Boolean,
    public val healthSessionEnding: Boolean,
    public val healthWriteDenied: Boolean,
)

@Serializable
public enum class CommandIssuer {
    rider,
    watch,
}

public sealed interface RideCommand {
    public val event: EventId

    public data class Start(
        override val event: EventId,
        public val rideId: RideId,
        public val recordedBy: Device,
        public val context: StartContext,
    ) : RideCommand

    public data class Control(
        override val event: EventId,
        public val rideId: RideId,
        public val action: RideAction,
        public val issuer: CommandIssuer,
    ) : RideCommand
}

public sealed interface RideSignal {
    public data class StartConfirmed(public val rideId: RideId) : RideSignal

    public data class StartFailed(public val rideId: RideId, public val code: FailureCode) : RideSignal

    public data class StartTimedOut(public val rideId: RideId) : RideSignal

    public data object ProcessRestarted : RideSignal

    public data class StorageFailed(public val rideId: RideId) : RideSignal
}

@Serializable
public enum class RideTransition {
    prepared,
    started,
    paused,
    resumed,
    lapped,
    completed,
    discarded,
    failed,
    interruptedAtLaunch,
    interruptedByStorage,
}

public sealed interface LifecycleDecision {
    public data class Accepted(
        public val next: LifecycleState,
        public val saved: Ride?,
        public val deleted: RideId?,
        public val events: List<RideEvent>,
        public val transition: RideTransition,
    ) : LifecycleDecision

    public data object Unchanged : LifecycleDecision

    public data class Refused(public val code: FailureCode) : LifecycleDecision
}

public data class FinishContext(public val lastFact: RetainedEnd?, public val events: List<RideEvent>)

@Serializable
public data class RideAllowed(
    public val start: Availability,
    public val pause: Availability,
    public val resume: Availability,
    public val lap: Availability,
    public val finish: Availability,
    public val discard: Availability,
)

public object RideLifecycle {
    private val openPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)
    private val recordingPhases = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)

    public fun decide(
        state: LifecycleState,
        command: RideCommand,
        clock: ClockReading,
        finish: FinishContext?,
    ): LifecycleDecision {
        val open = open(state)
        return when (command) {
            is RideCommand.Start -> start(open, command, clock)
            is RideCommand.Control -> {
                if (open == null || open.id != command.rideId) return LifecycleDecision.Refused(FailureCode.rideChanged)
                when (val cell = cell(open.phase, command.action, command.issuer)) {
                    is Cell.Refuse -> LifecycleDecision.Refused(cell.code)
                    is Cell.Move -> move(state, open, cell.transition, command.event, clock, finish)
                }
            }
        }
    }

    public fun observe(state: LifecycleState, signal: RideSignal, clock: ClockReading): LifecycleDecision {
        val open = open(state) ?: return LifecycleDecision.Unchanged
        val ride = signal.rideId
        if (ride != null && ride != open.id) return LifecycleDecision.Unchanged
        return when (signal) {
            is RideSignal.StartConfirmed -> if (open.phase == RidePhase.preparing) confirm(state, open, clock) else null
            is RideSignal.StartFailed,
            is RideSignal.StartTimedOut ->
                if (open.phase == RidePhase.preparing) {
                    val failed = open.copy(phase = RidePhase.failed, checkpoint = timing(state, clock))
                    accepted(LifecycleState(null, null), failed, RideTransition.failed)
                } else null
            RideSignal.ProcessRestarted -> interrupt(open, RideTransition.interruptedAtLaunch)
            is RideSignal.StorageFailed -> interrupt(open, RideTransition.interruptedByStorage)
        } ?: LifecycleDecision.Unchanged
    }

    public fun available(state: LifecycleState, context: StartContext): RideAllowed {
        if (context.ownerForeign) {
            val foreign = Availability(false, FailureCode.ownerForeign)
            return RideAllowed(foreign, foreign, foreign, foreign, foreign, foreign)
        }
        val open = open(state)
        fun rider(action: RideAction): Availability {
            if (open == null) return Availability(false, FailureCode.rideChanged)
            return when (val cell = cell(open.phase, action, CommandIssuer.rider)) {
                is Cell.Refuse -> Availability(false, cell.code)
                is Cell.Move -> Availability(true, null)
            }
        }
        val refusal = startRefusal(open, context)
        return RideAllowed(
            start = Availability(refusal == null, refusal),
            pause = rider(RideAction.pause),
            resume = rider(RideAction.resume),
            lap = rider(RideAction.lap),
            finish = rider(RideAction.finish),
            discard = rider(RideAction.discard),
        )
    }

    public fun restored(open: Ride?): LifecycleState = LifecycleState(open?.takeIf { it.phase in openPhases }, null)

    public fun timing(state: LifecycleState, clock: ClockReading): RideTiming {
        val ride = open(state) ?: return RideTiming(clock.utcUs, 0, 0)
        val rideClock = state.clock
        if (ride.phase == RidePhase.interrupted || rideClock == null) return ride.checkpoint
        val elapsedUs = clock.monoUs - rideClock.elapsedOriginMonoUs
        val timerUs =
            rideClock.timerOriginMonoUs?.let { rideClock.timerBaseUs + (clock.monoUs - it) } ?: rideClock.timerBaseUs
        return RideTiming(clock.utcUs, elapsedUs, timerUs)
    }

    private sealed interface Cell {
        data class Refuse(val code: FailureCode) : Cell

        data class Move(val transition: RideTransition) : Cell
    }

    private fun cell(phase: RidePhase, action: RideAction, issuer: CommandIssuer): Cell {
        val pending = Cell.Refuse(FailureCode.actionPending)
        val unavailable = Cell.Refuse(FailureCode.actionUnavailable)
        return when (phase) {
            RidePhase.preparing -> if (action == RideAction.discard) Cell.Move(RideTransition.discarded) else pending
            RidePhase.running ->
                when (action) {
                    RideAction.pause -> Cell.Move(RideTransition.paused)
                    RideAction.resume -> unavailable
                    RideAction.lap -> Cell.Move(RideTransition.lapped)
                    RideAction.finish -> Cell.Move(RideTransition.completed)
                    RideAction.discard -> Cell.Move(RideTransition.discarded)
                }
            RidePhase.paused ->
                when (action) {
                    RideAction.pause -> unavailable
                    RideAction.resume -> Cell.Move(RideTransition.resumed)
                    RideAction.lap ->
                        if (issuer == CommandIssuer.watch) Cell.Move(RideTransition.lapped) else unavailable
                    RideAction.finish -> Cell.Move(RideTransition.completed)
                    RideAction.discard -> Cell.Move(RideTransition.discarded)
                }
            RidePhase.interrupted ->
                when (action) {
                    RideAction.finish -> Cell.Move(RideTransition.completed)
                    RideAction.discard -> Cell.Move(RideTransition.discarded)
                    else -> unavailable
                }
            RidePhase.completed,
            RidePhase.failed -> Cell.Refuse(FailureCode.rideChanged)
        }
    }

    private fun startRefusal(open: Ride?, context: StartContext): FailureCode? {
        val options = context.options
        return when {
            context.ownerForeign -> FailureCode.ownerForeign
            open?.phase == RidePhase.preparing -> FailureCode.actionPending
            open != null -> FailureCode.rideActive
            context.healthSessionEnding && options?.healthWriter == HealthWriter.phone -> FailureCode.actionPending
            context.settingsSaveFailed -> FailureCode.settingsSaveFailed
            !context.settingsReadable -> FailureCode.settingsUnreadable
            !context.bikeVerified -> FailureCode.bikeRequired
            options == null -> FailureCode.gpsRequired
            options.useWatch && !context.watchInstalled && !context.fromWatch -> FailureCode.watchNotInstalled
            options.recordGps && context.locationDenied -> FailureCode.locationDenied
            options.healthWriter == HealthWriter.phone && context.healthWriteDenied -> FailureCode.healthDenied
            else -> null
        }
    }

    private fun start(open: Ride?, command: RideCommand.Start, clock: ClockReading): LifecycleDecision {
        startRefusal(open, command.context)?.let {
            return LifecycleDecision.Refused(it)
        }
        val ride =
            Ride(
                id = command.rideId,
                recordedBy = command.recordedBy,
                example = false,
                startedUtcUs = clock.utcUs,
                options = requireNotNull(command.context.options),
                phase = RidePhase.preparing,
                interrupted = false,
                checkpoint = RideTiming(clock.utcUs, 0, 0),
                end = null,
            )
        val rideClock =
            RideClock(
                elapsedOriginMonoUs = clock.monoUs,
                timerBaseUs = 0,
                timerOriginMonoUs = null,
                startDeadlineMonoUs = StartPolicy.deadline(clock.monoUs),
                startEvent = command.event,
                activeInterval = null,
                intervalStartUs = null,
                intervalsOpened = 0,
            )
        return accepted(LifecycleState(ride, rideClock), ride, RideTransition.prepared)
    }

    private fun confirm(state: LifecycleState, open: Ride, clock: ClockReading): LifecycleDecision {
        val rideClock = requireNotNull(state.clock) { "a preparing ride has its clock" }
        val start = RideEvent(requireNotNull(rideClock.startEvent), RideEventKind.start, open.startedUtcUs, 0)
        val next =
            rideClock.copy(
                timerBaseUs = 0,
                timerOriginMonoUs = clock.monoUs,
                startDeadlineMonoUs = null,
                startEvent = null,
                activeInterval = 1,
                intervalStartUs = 0,
                intervalsOpened = 1,
            )
        val timing = RideTiming(clock.utcUs, clock.monoUs - rideClock.elapsedOriginMonoUs, 0)
        val running = open.copy(phase = RidePhase.running, checkpoint = timing)
        return accepted(LifecycleState(running, next), running, RideTransition.started, listOf(start))
    }

    private fun interrupt(open: Ride, transition: RideTransition): LifecycleDecision? {
        if (open.phase !in recordingPhases) return null
        val interrupted = open.copy(phase = RidePhase.interrupted, interrupted = true)
        return accepted(LifecycleState(interrupted, null), interrupted, transition)
    }

    private fun move(
        state: LifecycleState,
        open: Ride,
        transition: RideTransition,
        event: EventId,
        clock: ClockReading,
        finish: FinishContext?,
    ): LifecycleDecision {
        if (transition == RideTransition.discarded)
            return LifecycleDecision.Accepted(LifecycleState(null, null), null, open.id, emptyList(), transition)
        if (transition == RideTransition.completed && open.phase == RidePhase.interrupted) {
            val context = requireNotNull(finish) { "an interrupted ride finishes at its Finish boundary" }
            val end = FinishBoundary.of(open.startedUtcUs, context.lastFact, context.events)
            val stop = RideEvent(event, RideEventKind.stop, end.utcUs, end.elapsedUs)
            val completed = open.copy(phase = RidePhase.completed, checkpoint = end, end = end)
            return accepted(LifecycleState(null, null), completed, transition, listOf(stop))
        }
        val rideClock = requireNotNull(state.clock) { "a recording ride has its clock" }
        val now = timing(state, clock)
        return when (transition) {
            RideTransition.paused -> {
                val paused = open.copy(phase = RidePhase.paused, checkpoint = now)
                val next =
                    rideClock.copy(
                        timerBaseUs = now.timerUs,
                        timerOriginMonoUs = null,
                        activeInterval = null,
                        intervalStartUs = null,
                    )
                accepted(
                    LifecycleState(paused, next),
                    paused,
                    transition,
                    listOf(event(event, RideEventKind.pause, now)),
                )
            }
            RideTransition.resumed -> {
                val running = open.copy(phase = RidePhase.running, checkpoint = now)
                val opened = rideClock.intervalsOpened + 1
                val next =
                    rideClock.copy(
                        timerOriginMonoUs = clock.monoUs,
                        activeInterval = opened,
                        intervalStartUs = now.elapsedUs,
                        intervalsOpened = opened,
                    )
                accepted(
                    LifecycleState(running, next),
                    running,
                    transition,
                    listOf(event(event, RideEventKind.resume, now)),
                )
            }
            RideTransition.lapped -> {
                val lapped = open.copy(checkpoint = now)
                accepted(
                    LifecycleState(lapped, rideClock),
                    lapped,
                    transition,
                    listOf(event(event, RideEventKind.lap, now)),
                )
            }
            RideTransition.completed -> {
                val completed = open.copy(phase = RidePhase.completed, checkpoint = now, end = now)
                accepted(
                    LifecycleState(null, null),
                    completed,
                    transition,
                    listOf(event(event, RideEventKind.stop, now)),
                )
            }
            else -> error("$transition is not a rider command")
        }
    }

    private fun event(id: EventId, kind: RideEventKind, at: RideTiming): RideEvent =
        RideEvent(id, kind, at.utcUs, at.elapsedUs)

    private fun accepted(
        next: LifecycleState,
        saved: Ride,
        transition: RideTransition,
        events: List<RideEvent> = emptyList(),
    ): LifecycleDecision = LifecycleDecision.Accepted(next, saved, null, events, transition)

    private fun open(state: LifecycleState): Ride? = state.open?.takeIf { it.phase in openPhases }

    private val RideSignal.rideId: RideId?
        get() =
            when (this) {
                is RideSignal.StartConfirmed -> rideId
                is RideSignal.StartFailed -> rideId
                is RideSignal.StartTimedOut -> rideId
                is RideSignal.StorageFailed -> rideId
                RideSignal.ProcessRestarted -> null
            }
}
