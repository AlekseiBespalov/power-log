package app.powerlog.domain.recording

import app.powerlog.domain.analytics.FixedPoint
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import kotlin.math.abs
import kotlinx.serialization.Serializable

public data class RunKey(public val activeInterval: Int?, public val sessionNo: Int)

public data class TelemetryCursor(
    public val lastSeq: Long,
    public val lastMonoUs: Long?,
    public val run: Int,
    public val runKey: RunKey?,
)

@Serializable
public enum class DropReason {
    older,
    notRunning,
    tooOld,
    future,
    outOfOrder,
    invalid,
}

public sealed interface TelemetryDecision {
    public data class Admitted(public val sample: TelemetrySample, public val next: TelemetryCursor) : TelemetryDecision

    public data class Dropped(public val reason: DropReason) : TelemetryDecision
}

public object TelemetryAdmission {
    public fun admit(
        values: Map<MetricId, Double>,
        session: ControllerSession,
        at: ClockReading,
        state: LifecycleState,
        cursor: TelemetryCursor,
    ): TelemetryDecision {
        val clock = state.clock
        if (state.open?.phase != RidePhase.running || clock == null)
            return TelemetryDecision.Dropped(DropReason.notRunning)
        return sample(values, session, at, at.monoUs - clock.elapsedOriginMonoUs, clock.activeInterval, cursor)
    }

    public fun preview(
        values: Map<MetricId, Double>,
        session: ControllerSession,
        at: ClockReading,
        originMonoUs: Long,
        cursor: TelemetryCursor,
    ): TelemetryDecision = sample(values, session, at, at.monoUs - originMonoUs, null, cursor)

    private fun sample(
        values: Map<MetricId, Double>,
        session: ControllerSession,
        at: ClockReading,
        elapsedUs: Long,
        activeInterval: Int?,
        cursor: TelemetryCursor,
    ): TelemetryDecision {
        val last = cursor.lastMonoUs
        if (elapsedUs < 0 || last != null && at.monoUs < last) return TelemetryDecision.Dropped(DropReason.older)
        val quanta = quantized(values) ?: return TelemetryDecision.Dropped(DropReason.invalid)
        val key = RunKey(activeInterval, session.sessionNo)
        val seq = if (cursor.runKey?.sessionNo == session.sessionNo) cursor.lastSeq + 1 else 1
        val run = if (cursor.runKey == key) cursor.run else cursor.run + 1
        val sample =
            TelemetrySample(session.rideId, session.sessionNo, seq, elapsedUs, at.utcUs, activeInterval, run, quanta)
        return TelemetryDecision.Admitted(sample, TelemetryCursor(seq, at.monoUs, run, key))
    }

    private fun quantized(values: Map<MetricId, Double>): Map<MetricId, Long>? {
        val quanta = LinkedHashMap<MetricId, Long>(values.size)
        for ((metric, value) in values) {
            if (metric == MetricId.controllerSpeedMps) continue
            if (!value.isFinite()) return null
            quanta[metric] =
                try {
                    FixedPoint.quantize(value, MetricCatalog.of(metric).quantum)
                } catch (_: ArithmeticException) {
                    return null
                }
        }
        return quanta
    }
}

public data class LocationOriginal(
    public val utcUs: Long,
    public val acquiredMonoUs: Long?,
    public val latitude: Double,
    public val longitude: Double,
    public val horizontalAccuracyM: Double?,
    public val altitudeM: Double?,
    public val altitudeDatum: AltitudeDatum,
    public val verticalAccuracyM: Double?,
    public val mslM: Double?,
    public val mslVerticalAccuracyM: Double?,
    public val speedMps: Double?,
    public val speedAccuracyMps: Double?,
    public val courseDeg: Double?,
    public val courseAccuracyDeg: Double?,
)

public data class GpsClock(
    public val lastReceipt: ClockReading?,
    public val lastAcquisitionMonoUs: Long?,
    public val uncertainUntilMonoUs: Long?,
    public val barrierPending: Boolean,
    public val lastSeq: Long,
    public val run: Int,
    public val lastInterval: Int? = null,
    public val lastBarrier: Boolean = false,
)

public object LocationPolicy {
    public const val maxFixAgeUs: Long = 15_000_000L
    public const val clockJumpUs: Long = 1_000_000L
    public const val uncertainUs: Long = 15_000_000L
    public const val weakAccuracyM: Double = 50.0
}

public sealed interface LocationDecision {
    public data class Admitted(public val fix: LocationFix, public val next: GpsClock) : LocationDecision

    public data class Dropped(public val reason: DropReason, public val next: GpsClock) : LocationDecision
}

public object LocationAdmission {
    public fun admit(
        original: LocationOriginal,
        at: ClockReading,
        state: LifecycleState,
        clock: GpsClock,
    ): LocationDecision {
        val ride = state.open
        val rideClock = state.clock
        if (ride?.phase != RidePhase.running || rideClock == null)
            return LocationDecision.Dropped(DropReason.notRunning, clock)
        if (!valid(original)) return LocationDecision.Dropped(DropReason.invalid, clock.copy(barrierPending = true))
        val received =
            clock.copy(lastReceipt = at, uncertainUntilMonoUs = uncertainUntil(at, ride.startedUtcUs, rideClock, clock))
        val ageUs = original.acquiredMonoUs?.let { at.monoUs - it } ?: (at.utcUs - original.utcUs)
        if (ageUs < 0) return LocationDecision.Dropped(DropReason.future, received.copy(barrierPending = true))
        if (ageUs > LocationPolicy.maxFixAgeUs)
            return LocationDecision.Dropped(DropReason.tooOld, received.copy(barrierPending = true))
        val acquisitionMonoUs = at.monoUs - ageUs
        val elapsedUs = acquisitionMonoUs - rideClock.elapsedOriginMonoUs
        if (elapsedUs < 0) return LocationDecision.Dropped(DropReason.tooOld, received.copy(barrierPending = true))
        val last = clock.lastAcquisitionMonoUs
        if (last != null && acquisitionMonoUs - last <= 1)
            return LocationDecision.Dropped(DropReason.outOfOrder, received)
        val uncertain = received.uncertainUntilMonoUs
        val barrier = clock.barrierPending || uncertain != null && at.monoUs <= uncertain
        val interval = rideClock.activeInterval?.takeIf { elapsedUs >= (rideClock.intervalStartUs ?: 0) }
        val continues = clock.lastSeq > 0 && !barrier && !clock.lastBarrier && interval == clock.lastInterval
        val run = if (continues) clock.run else clock.run + 1
        val seq = clock.lastSeq + 1
        val fix =
            LocationFix(
                rideId = ride.id,
                seq = seq,
                elapsedUs = elapsedUs,
                utcUs = original.utcUs,
                activeInterval = interval,
                run = run,
                barrier = barrier,
                latitude = original.latitude,
                longitude = original.longitude,
                horizontalAccuracyM = original.horizontalAccuracyM,
                altitudeM = original.altitudeM,
                altitudeDatum = original.altitudeDatum,
                verticalAccuracyM = original.verticalAccuracyM,
                mslM = original.mslM,
                mslVerticalAccuracyM = original.mslVerticalAccuracyM,
                speedMps = original.speedMps,
                speedAccuracyMps = original.speedAccuracyMps,
                courseDeg = original.courseDeg,
                courseAccuracyDeg = original.courseAccuracyDeg,
            )
        return LocationDecision.Admitted(
            fix,
            received.copy(
                lastAcquisitionMonoUs = acquisitionMonoUs,
                barrierPending = false,
                lastSeq = seq,
                run = run,
                lastInterval = interval,
                lastBarrier = barrier,
            ),
        )
    }

    private fun valid(original: LocationOriginal): Boolean =
        original.latitude.isFinite() &&
            original.longitude.isFinite() &&
            original.latitude in -90.0..90.0 &&
            original.longitude in -180.0..180.0

    private fun uncertainUntil(at: ClockReading, startedUtcUs: Long, rideClock: RideClock, clock: GpsClock): Long? {
        val last = clock.lastReceipt
        val utcStepUs = if (last == null) at.utcUs - startedUtcUs else at.utcUs - last.utcUs
        val monoStepUs = if (last == null) at.monoUs - rideClock.elapsedOriginMonoUs else at.monoUs - last.monoUs
        val jumped = abs(utcStepUs - monoStepUs) > LocationPolicy.clockJumpUs
        return if (jumped) at.monoUs + LocationPolicy.uncertainUs else clock.uncertainUntilMonoUs
    }
}
