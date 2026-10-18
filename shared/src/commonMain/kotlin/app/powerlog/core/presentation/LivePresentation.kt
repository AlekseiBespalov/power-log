package app.powerlog.core.presentation

import app.powerlog.core.StreamStatus
import app.powerlog.core.telemetry.MetricCatalog

enum class LiveSource {
    controller,
    heartRate,
    gps,
}

data class PresentationClock
@Throws(IllegalArgumentException::class)
constructor(val epoch: String, val monotonicUs: Long, val utcUs: Long) {
    init {
        require(epoch.isNotBlank() && monotonicUs >= 0)
    }
}

data class LiveAcquisition
@Throws(IllegalArgumentException::class)
constructor(
    val observationId: String,
    val sequence: Long?,
    val originalTimeUs: Long,
    val epoch: String,
    val monotonicUs: Long,
) {
    init {
        require(
            observationId.isNotBlank() && (sequence == null || sequence > 0) && epoch.isNotBlank() && monotonicUs >= 0
        )
    }

    fun ageUs(clock: PresentationClock): Long? =
        if (epoch == clock.epoch && clock.monotonicUs >= monotonicUs) clock.monotonicUs - monotonicUs else null
}

class LiveReading
@Throws(IllegalArgumentException::class)
constructor(val acquisition: LiveAcquisition, values: Map<String, Double>) {
    private val retained = values.toMap()
    val values: Map<String, Double>
        get() = retained.toMap()

    init {
        retained.forEach { (id, value) ->
            MetricCatalog.metric(id)
            require(value.isFinite())
        }
    }
}

class LivePresentationState internal constructor(val rideId: String?, readings: Map<LiveSource, LiveReading>) {
    private val retained = readings.toMap()

    fun reading(source: LiveSource): LiveReading? = retained[source]

    internal fun replacing(source: LiveSource, reading: LiveReading) =
        LivePresentationState(rideId, retained + (source to reading))
}

data class LivePresentationInput(
    val phase: String,
    val controllerRequested: Boolean = true,
    val heartRequested: Boolean = true,
    val gpsRequested: Boolean = true,
    val gpsProviderStatus: String? = null,
    val gpsAccuracy: Double? = null,
    val heartProviderStatus: String? = null,
)

class LivePresentationView(
    val controllerStatus: String,
    val gpsStatus: String,
    val heartStatus: String,
    val controllerDisplay: String,
    val controllerConnection: String,
    values: Map<String, Double>,
) {
    private val retained = values.toMap()
    val values: Map<String, Double>
        get() = retained.toMap()
}

internal enum class ObservationAdmission {
    replace,
    earlierDuplicate,
    retain,
}

internal object ObservationOrder {
    fun admit(
        sameIdentity: Boolean,
        sameEpoch: Boolean,
        oldSequence: Long?,
        nextSequence: Long?,
        acquisitionOrder: Int,
        backfilled: Boolean,
    ): ObservationAdmission {
        if (sameIdentity)
            return if (sameEpoch && acquisitionOrder < 0) ObservationAdmission.earlierDuplicate
            else ObservationAdmission.retain
        if (backfilled) return ObservationAdmission.retain
        if (sameEpoch) {
            if (oldSequence != null && nextSequence != null) {
                if (nextSequence <= oldSequence) return ObservationAdmission.retain
            } else if (acquisitionOrder <= 0) return ObservationAdmission.retain
        }
        return ObservationAdmission.replace
    }
}

object LivePresentation {
    fun empty(): LivePresentationState = LivePresentationState(null, emptyMap())

    @Throws(IllegalArgumentException::class)
    fun adopt(state: LivePresentationState, rideId: String?): LivePresentationState {
        val normalized = rideId?.let(::presentationId)
        return if (state.rideId == normalized) state else LivePresentationState(normalized, emptyMap())
    }

    @Throws(IllegalArgumentException::class)
    fun observe(
        state: LivePresentationState,
        rideId: String,
        source: LiveSource,
        reading: LiveReading,
        clock: PresentationClock,
        backfilled: Boolean = false,
    ): LivePresentationState {
        if (presentationId(rideId) != state.rideId || reading.acquisition.ageUs(clock) == null) return state
        val retained = state.reading(source)
        val old = retained?.acquisition
        val next = reading.acquisition
        return when (
            ObservationOrder.admit(
                old?.observationId == next.observationId,
                old != null && old.epoch == next.epoch,
                old?.sequence,
                next.sequence,
                if (old == null) 1 else next.monotonicUs.compareTo(old.monotonicUs),
                backfilled,
            )
        ) {
            ObservationAdmission.earlierDuplicate ->
                state.replacing(source, LiveReading(old!!.copy(monotonicUs = next.monotonicUs), retained!!.values))
            ObservationAdmission.retain -> state
            ObservationAdmission.replace -> state.replacing(source, reading)
        }
    }

    fun view(
        state: LivePresentationState,
        input: LivePresentationInput,
        clock: PresentationClock,
    ): LivePresentationView {
        fun age(source: LiveSource) = state.reading(source)?.acquisition?.ageUs(clock)?.div(1_000_000.0)
        val phase = if (state.rideId == null) "idle" else input.phase
        val values = mutableMapOf<String, Double>()
        for (source in LiveSource.entries) {
            val cutoff =
                when (source) {
                    LiveSource.controller -> StreamStatus.controllerStaleSeconds
                    LiveSource.heartRate -> StreamStatus.heartStaleSeconds
                    LiveSource.gps -> StreamStatus.gpsStaleSeconds
                }
            val requested =
                when (source) {
                    LiveSource.controller -> input.controllerRequested
                    LiveSource.heartRate -> input.heartRequested
                    LiveSource.gps -> input.gpsRequested
                }
            if (requested && StreamStatus.available(age(source), cutoff, phase))
                values.putAll(state.reading(source)!!.values)
        }
        return LivePresentationView(
            StreamStatus.controller(input.controllerRequested, phase, age(LiveSource.controller)),
            StreamStatus.gps(
                input.gpsRequested && state.rideId != null,
                phase,
                age(LiveSource.gps),
                input.gpsAccuracy,
                input.gpsProviderStatus,
            ),
            StreamStatus.heart(
                input.heartRequested && state.rideId != null,
                phase,
                age(LiveSource.heartRate),
                input.heartProviderStatus,
            ),
            StreamStatus.controllerDisplay(age(LiveSource.controller), phase),
            StreamStatus.controllerConnection(age(LiveSource.controller), phase),
            values,
        )
    }
}
