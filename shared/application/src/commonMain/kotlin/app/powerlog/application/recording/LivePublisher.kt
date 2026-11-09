package app.powerlog.application.recording

import app.powerlog.application.Deadlines
import app.powerlog.application.api.Published
import app.powerlog.application.api.StreamPolicy
import app.powerlog.application.api.occurrence
import app.powerlog.application.port.Clock
import app.powerlog.application.port.LocationProviderState
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideAllowed
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.SourceKind
import app.powerlog.domain.recording.SourceState
import app.powerlog.domain.recording.Sources
import app.powerlog.domain.recording.StartContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalAtomicApi::class)
internal class LivePublisher(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val clock: Clock,
    private val state: RecordingState,
    private val deadlines: Deadlines,
) : LiveReadings {
    var startContext: () -> StartContext = { error("the recorder sets the Start context") }
    var bikeWanted: () -> Boolean = { false }
    var published: () -> Unit = {}

    private val current = AtomicReference(LiveSnapshot(null, null, null, null, emptyMap(), emptyMap(), emptyMap()))
    private var controllerMonoUs: Long? = null
    private var staleCheck: Job? = null
    private var staleAtMonoUs: Long? = null
    private var inboxFull = false
    private var reducedRide: RideId? = null
    private var controllerDistance = ControllerDistanceReducer(null)
    private var gpsDistance = GpsDistanceReducer(null)
    private var controllerDistanceQ = 0L
    private var gpsDistanceQ = 0L
    private var gpsProvider: LocationProviderState? = null
    private var fixMonoUs: Long? = null
    private var fixAccuracyM: Double? = null
    private var heartRateMonoUs: Long? = null
    private var sourcesRide: RideId? = null

    val stream: Published<RecordingView> by lazy { Published(view(), StreamPolicy.recordingStateUs, clock) }

    override fun snapshot(): LiveSnapshot = current.load()

    fun previewStarted(previewId: RideId, originMonoUs: Long) {
        controllerMonoUs = null
        update { it.copy(previewId = previewId, previewOriginMonoUs = originMonoUs, newest = emptyMap()) }
        publish(urgent = false)
    }

    fun previewReading(values: Map<MetricId, Double>, elapsedUs: Long, acquiredMonoUs: Long) {
        controllerMonoUs = acquiredMonoUs
        if (state.open == null)
            update { snapshot ->
                snapshot.copy(
                    newest = snapshot.newest + values.mapValues { LiveValue(it.value, elapsedUs, acquiredMonoUs) }
                )
            }
        publish(urgent = false)
    }

    fun committed(
        rows: TelemetryRows?,
        sessions: Map<Int, ControllerSession>,
        fixes: List<LocationFix>,
    ): List<GpsEdge> {
        val rideId = state.open?.id
        if (rideId != reducedRide) {
            reducedRide = rideId
            controllerDistance = ControllerDistanceReducer(null)
            gpsDistance = GpsDistanceReducer(null)
            controllerDistanceQ = 0
            gpsDistanceQ = 0
        }
        if (rows != null)
            controllerDistance.append(rows, 0, rows.rows, sessions).forEach {
                controllerDistanceQ += it.distanceQ
            }
        val edges = fixes.mapNotNull(gpsDistance::append)
        edges.forEach { gpsDistanceQ += it.interval.distanceQ }
        if (rows != null) committedReadings(rows, sessions)
        return edges
    }

    fun distanceM(source: DistanceSource): Double =
        (if (source == DistanceSource.controller) controllerDistanceQ else gpsDistanceQ) / 1e6

    fun gpsProvider(provider: LocationProviderState) {
        gpsProvider = provider
        publish(urgent = true)
    }

    fun fixAdmitted(acquiredMonoUs: Long, accuracyM: Double?) {
        fixMonoUs = acquiredMonoUs
        fixAccuracyM = accuracyM
        publish(urgent = false)
    }

    fun heartRate(bpm: Double, acquiredMonoUs: Long) {
        val previous = heartRateMonoUs
        if (previous != null && acquiredMonoUs <= previous) return
        heartRateMonoUs = acquiredMonoUs
        val origin = state.lifecycle.clock?.elapsedOriginMonoUs ?: acquiredMonoUs
        update {
            it.copy(
                newest = it.newest + (MetricId.heartRateBpm to LiveValue(bpm, acquiredMonoUs - origin, acquiredMonoUs))
            )
        }
        publish(urgent = false)
    }

    private fun committedReadings(rows: TelemetryRows, sessions: Map<Int, ControllerSession>) {
        if (rows.rows == 0) return
        val origin = state.lifecycle.clock?.elapsedOriginMonoUs ?: return
        val newest = LinkedHashMap(current.load().newest)
        for (row in 0 until rows.rows) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            for (metric in session.capabilities.metrics) {
                val value = rows.value(metric, row, session) ?: continue
                newest[metric] = LiveValue(value, elapsedUs, origin + elapsedUs)
            }
        }
        controllerMonoUs = origin + rows.elapsedUs[rows.rows - 1].toLong()
        update { it.copy(newest = newest) }
        publish(urgent = false)
    }

    fun inboxFull(full: Boolean) {
        if (full == inboxFull) return
        inboxFull = full
        if (full) state.failure = occurrence(FailureCode.storageBusy, state.failure)
        else if (state.failure?.code == FailureCode.storageBusy) state.failure = null
        publish(urgent = true)
    }

    fun rideChanged() {
        val open = state.open
        val origin = state.lifecycle.clock?.takeIf { open?.phase in RecordingState.recordingPhases }
        if (open?.id != sourcesRide) {
            sourcesRide = open?.id
            fixMonoUs = null
            fixAccuracyM = null
            heartRateMonoUs = null
        }
        update {
            it.copy(
                rideId = open?.id,
                rideOriginMonoUs = origin?.elapsedOriginMonoUs,
                newest = if (open == null) emptyMap() else it.newest,
            )
        }
        publish(urgent = true)
    }

    fun publish(urgent: Boolean) {
        stream.publish(view(), urgent)
        armStaleCheck()
        published()
    }

    private fun update(change: (LiveSnapshot) -> LiveSnapshot) {
        while (true) {
            val before = current.load()
            if (current.compareAndSet(before, change(before))) return
        }
    }

    private fun armStaleCheck() {
        val now = clock.monoUs()
        val next =
            listOfNotNull(
                    controllerMonoUs?.plus(hold(MetricId.humanPowerW)),
                    fixMonoUs?.plus(hold(MetricId.speedMps)),
                    heartRateMonoUs?.plus(hold(MetricId.heartRateBpm)),
                )
                .filter { it > now }
                .minOrNull() ?: return
        val armed = staleAtMonoUs
        if (staleCheck?.isActive == true && armed != null && armed <= next) return
        staleCheck?.cancel()
        staleAtMonoUs = next
        staleCheck =
            scope.launch(ride) {
                deadlines.until(next)
                staleCheck = null
                staleAtMonoUs = null
                publish(urgent = false)
            }
    }

    private fun hold(metric: MetricId): Long = requireNotNull(MetricCatalog.of(metric).displayHoldUs)

    private fun view(): RecordingView {
        val lifecycle = state.lifecycle
        val open = state.open
        val now = clock.read()
        val timing = RideLifecycle.timing(lifecycle, now)
        val rideClock = lifecycle.clock
        val timerOrigin = rideClock?.timerOriginMonoUs
        val timer =
            when {
                open == null -> TimerView(TimerState.unavailable, 0, null)
                open.phase == RidePhase.running && rideClock != null && timerOrigin != null ->
                    TimerView(TimerState.running, rideClock.timerBaseUs, timerOrigin - rideClock.timerBaseUs)
                else -> TimerView(TimerState.frozen, timing.timerUs, null)
            }
        val ageUs = controllerMonoUs?.let { now.monoUs - it }
        val phase = open?.phase
        val controller = Sources.state(SourceKind.controller, open != null || bikeWanted(), phase, ageUs, null, null)
        val heartRate =
            Sources.state(
                SourceKind.heartRate,
                open?.options?.useWatch == true,
                phase,
                heartRateMonoUs?.let { now.monoUs - it },
                null,
                null,
            )
        val gps =
            Sources.state(
                SourceKind.gps,
                open?.options?.recordGps == true,
                phase,
                fixMonoUs?.let { now.monoUs - it },
                fixAccuracyM,
                when (gpsProvider) {
                    LocationProviderState.denied,
                    LocationProviderState.restricted -> SourceState.denied
                    LocationProviderState.servicesOff,
                    LocationProviderState.unavailable -> SourceState.unavailable
                    else -> null
                },
            )
        return RecordingView(
            phase = phase,
            ride = open?.let { OpenRideView(it.id, it.options) },
            pending = state.pending,
            timer = timer,
            allowed = allowed(),
            finishRequest =
                state.finishRequest?.takeIf {
                    it.rideId == open?.id && (phase == RidePhase.running || phase == RidePhase.paused)
                },
            sources = SourcesView(controller, heartRate, gps),
            health = null,
            otherTab = state.otherTab,
            failure = state.failure,
        )
    }

    private fun allowed(): RideAllowed {
        val allowed = RideLifecycle.available(state.lifecycle, startContext())
        if (state.pending == null && !state.deciding) return allowed
        val pending = app.powerlog.domain.core.failure.Availability(false, FailureCode.actionPending)
        val discard = if (state.open?.phase == RidePhase.preparing) allowed.discard else pending
        return RideAllowed(pending, pending, pending, pending, pending, discard)
    }
}
