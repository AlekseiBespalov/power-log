package app.powerlog.application.recording

import app.powerlog.application.Logs
import app.powerlog.application.api.newId
import app.powerlog.application.port.Clock
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.RideCardEvents
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.FreshnessPolicy
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.RideTransition
import app.powerlog.domain.recording.SourceKind

internal class RideCardPublisher(
    private val clock: Clock,
    private val card: RideCard,
    private val state: RecordingState,
    private val live: LivePublisher,
    private val logs: Logs,
) : RideEvents {
    var token: String = newId()
        private set

    private var shown: RideCardFacts? = null
    private var readingsAtMonoUs = Long.MIN_VALUE
    private var failedRide: String? = null
    private var timerOrigin: Pair<Long, Long>? = null
    private var ended = true

    val events: RideCardEvents =
        object : RideCardEvents {
            override fun failed(error: PortError) {
                val rideId = shown?.rideId
                if (rideId == failedRide) return
                failedRide = rideId
                logs.failure("rideCard", error)
            }
        }

    fun refresh() {
        val facts = facts() ?: return
        val previous = shown
        val now = clock.monoUs()
        val changed =
            previous == null ||
                previous.rideId != facts.rideId ||
                previous.state != facts.state ||
                previous.token != facts.token ||
                previous.controls != facts.controls ||
                previous.timer != facts.timer
        if (!changed && (facts == previous || now - readingsAtMonoUs < FreshnessPolicy.rideCardRefreshUs)) return
        shown = facts
        readingsAtMonoUs = now
        ended = false
        card.publish(facts)
    }

    override fun rideTransitioned(ride: Ride, transition: RideTransition, eventId: EventId?, events: List<RideEvent>) {
        when (transition) {
            RideTransition.completed,
            RideTransition.discarded,
            RideTransition.failed -> end()
            else -> {
                token = newId()
                refresh()
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
        if (ride.phase == RidePhase.interrupted) refresh()
    }

    fun end() {
        if (ended) return
        ended = true
        shown = null
        timerOrigin = null
        token = newId()
        card.end()
    }

    private fun facts(): RideCardFacts? {
        val open = state.open ?: return null
        val pending = state.pending != null || state.deciding
        val cardState =
            when (open.phase) {
                RidePhase.preparing -> RideCardState.starting
                RidePhase.running -> if (pending) RideCardState.updating else RideCardState.running
                RidePhase.paused -> if (pending) RideCardState.updating else RideCardState.paused
                RidePhase.interrupted -> RideCardState.interrupted
                else -> return null
            }
        val now = clock.read()
        val snapshot = live.snapshot()
        val power = snapshot.newest[MetricId.humanPowerW]
        val heartRate = snapshot.newest[MetricId.heartRateBpm]
        val controls =
            if (open.phase == RidePhase.interrupted || pending) emptyList()
            else {
                val allowed = RideLifecycle.available(state.lifecycle, live.startContext())
                listOfNotNull(
                    RideAction.pause.takeIf { allowed.pause.enabled },
                    RideAction.resume.takeIf { allowed.resume.enabled },
                    RideAction.finish.takeIf { allowed.finish.enabled },
                )
            }
        return RideCardFacts(
            rideId = open.id.value,
            token = token,
            state = cardState,
            timer = timer(open, now),
            powerW = power?.value,
            heartRateBpm = heartRate?.value,
            bikeFreshUntilUtcUs =
                power?.let { FreshnessPolicy.rideCardFreshUntilUtcUs(SourceKind.controller, it.acquiredMonoUs, now) },
            heartRateFreshUntilUtcUs =
                heartRate?.let {
                    FreshnessPolicy.rideCardFreshUntilUtcUs(SourceKind.heartRate, it.acquiredMonoUs, now)
                },
            controls = controls,
        )
    }

    private fun timer(open: Ride, now: app.powerlog.domain.core.time.ClockReading): RideTimer {
        val rideClock = state.lifecycle.clock
        val originMonoUs = rideClock?.timerOriginMonoUs
        if (open.phase != RidePhase.running || rideClock == null || originMonoUs == null) {
            timerOrigin = null
            return RideTimer(TimerState.frozen, RideLifecycle.timing(state.lifecycle, now).timerUs, null)
        }
        val origin =
            timerOrigin?.takeIf { it.first == originMonoUs }
                ?: (originMonoUs to now.utcUs - (now.monoUs - originMonoUs) - rideClock.timerBaseUs).also {
                    timerOrigin = it
                }
        return RideTimer(TimerState.running, rideClock.timerBaseUs, origin.second)
    }
}
