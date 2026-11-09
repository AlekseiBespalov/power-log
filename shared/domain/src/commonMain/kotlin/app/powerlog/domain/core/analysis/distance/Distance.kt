package app.powerlog.domain.core.analysis.distance

import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.CheckedSum
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.numeric.PureMath
import app.powerlog.domain.core.time.TimeRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class DistanceSelection {
    auto,
    controller,
    @SerialName("gps:phone") phoneGps,
}

@Serializable
public enum class DistanceSource {
    controller,
    @SerialName("gps:phone") phoneGps,
}

public object DistancePolicy {
    public const val gpsEdgeUs: Long = 10_000_000L
    public const val gpsMaxChordMps: Double = 40.0
}

public data class DistanceInterval(
    public val range: TimeRange,
    public val distanceQ: Long,
    public val segment: Int,
    public val activeInterval: Int,
    public val start: OriginalRef,
    public val end: OriginalRef,
    public val speed0Q: Long?,
    public val speed1Q: Long?,
)

@Serializable
public data class DistanceTotals(
    public val distanceQ: Long,
    public val coveredUs: Long,
    public val partial: Boolean,
    public val unresolvedBoundary: Boolean,
)

@Serializable
internal data class ControllerReading(
    val elapsedUs: Long,
    val utcUs: Long,
    val speedQ: Long,
    val activeInterval: Int,
    val sessionNo: Int,
    val identity: ControllerIdentity?,
)

@ConsistentCopyVisibility
@Serializable
public data class ControllerDistanceState
internal constructor(internal val previous: ControllerReading?, internal val segment: Int, internal val totalQ: Long)

public class ControllerDistanceReducer(state: ControllerDistanceState?) {
    private var previous = state?.previous
    private var segment = state?.segment ?: 0
    private var totalQ = state?.totalQ ?: 0L

    public fun append(
        rows: TelemetryRows,
        from: Int,
        to: Int,
        sessions: Map<Int, ControllerSession>,
    ): List<DistanceInterval> {
        val intervals = mutableListOf<DistanceInterval>()
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            reading(rows, row, session)?.let(intervals::add)
        }
        return intervals
    }

    public fun state(): ControllerDistanceState = ControllerDistanceState(previous, segment, totalQ)

    private fun reading(rows: TelemetryRows, row: Int, session: ControllerSession): DistanceInterval? {
        val elapsedUs = rows.elapsedUs[row].toLong()
        val activeInterval = rows.activeInterval[row]
        val speed = if (session.capabilities.controllerSpeed) speed(rows, row, session) else null
        if (elapsedUs < 0 || activeInterval == 0 || speed == null) {
            previous = null
            segment++
            return null
        }
        val current =
            ControllerReading(
                elapsedUs,
                rows.utcUs[row].toLong(),
                FixedPoint.quantize(speed, SPEED_QUANTUM),
                activeInterval,
                session.sessionNo,
                session.identity,
            )
        val old = previous
        previous = current
        if (old == null) return null
        val durationUs = elapsedUs - old.elapsedUs
        if (durationUs <= 0) {
            segment++
            previous = current.takeIf {
                durationUs == 0L && (old.sessionNo != current.sessionNo || old.activeInterval != activeInterval)
            }
            return null
        }
        if (
            durationUs >= ControllerPolicy.joinUs ||
                old.identity != current.identity ||
                old.activeInterval != current.activeInterval
        ) {
            segment++
            return null
        }
        val distanceQ = controllerContribution(old.speedQ, current.speedQ, durationUs)
        val startQ = totalQ
        totalQ = FixedPoint.add(totalQ, distanceQ)
        return DistanceInterval(
            TimeRange(old.elapsedUs, elapsedUs),
            distanceQ,
            segment,
            activeInterval,
            OriginalRef(old.utcUs, old.elapsedUs, startQ),
            OriginalRef(current.utcUs, elapsedUs, totalQ),
            old.speedQ,
            current.speedQ,
        )
    }

    private fun speed(rows: TelemetryRows, row: Int, session: ControllerSession): Double? =
        rows.value(MetricId.controllerSpeedMps, row, session)?.takeIf { validSpeed(it) }
}

@Serializable
internal data class GpsPoint(
    val seq: Long,
    val elapsedUs: Long,
    val utcUs: Long,
    val activeInterval: Int,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
)

public data class GpsEdge(public val interval: DistanceInterval, public val firstSeq: Long, public val lastSeq: Long)

@ConsistentCopyVisibility
@Serializable
public data class GpsDistanceState
internal constructor(internal val previous: GpsPoint?, internal val segment: Int, internal val totalQ: Long)

public class GpsDistanceReducer(state: GpsDistanceState?) {
    private val track = GpsTrack(state)

    public fun append(fix: LocationFix): GpsEdge? = track.append(fix).edge

    public fun state(): GpsDistanceState = track.state()
}

internal class GpsStep(val accepted: Boolean, val startsSegment: Boolean, val edge: GpsEdge?)

internal class GpsTrack(state: GpsDistanceState?) {
    private var previous = state?.previous
    var segment = state?.segment ?: -1
        private set

    private var totalQ = state?.totalQ ?: 0L

    fun append(fix: LocationFix): GpsStep {
        val current = fix.distancePoint()
        if (current == null) {
            previous = null
            return REJECTED
        }
        val old = previous
        val durationUs = if (old == null) 0L else current.elapsedUs - old.elapsedUs
        val same = old != null && old.activeInterval == current.activeInterval
        previous = current
        if (old != null && durationUs <= 0) previous = current.takeIf { durationUs == 0L && !same }
        val chordM =
            if (old != null && durationUs > 0 && durationUs <= DistancePolicy.gpsEdgeUs && same)
                PureMath.haversine(old.latitude, old.longitude, current.latitude, current.longitude)
            else null
        if (old == null || chordM == null || !supported(durationUs, chordM)) {
            segment++
            return GpsStep(true, true, null)
        }
        val stationary =
            validSpeed(old.speedMps, old.speedAccuracyMps) &&
                validSpeed(current.speedMps, current.speedAccuracyMps) &&
                requireNotNull(old.speedMps) < STATIONARY_MPS &&
                requireNotNull(current.speedMps) < STATIONARY_MPS
        val distanceQ = if (stationary) 0L else FixedPoint.quantize(chordM, DISTANCE_QUANTUM)
        val startQ = totalQ
        totalQ = FixedPoint.add(totalQ, distanceQ)
        val interval =
            DistanceInterval(
                TimeRange(old.elapsedUs, current.elapsedUs),
                distanceQ,
                segment,
                current.activeInterval,
                OriginalRef(old.utcUs, old.elapsedUs, startQ),
                OriginalRef(current.utcUs, current.elapsedUs, totalQ),
                null,
                null,
            )
        return GpsStep(true, false, GpsEdge(interval, old.seq, current.seq))
    }

    fun state(): GpsDistanceState = GpsDistanceState(previous, segment, totalQ)

    private fun supported(durationUs: Long, chordM: Double): Boolean =
        chordM.isFinite() &&
            chordM >= 0 &&
            chordM / FixedPoint.divide(durationUs, SECOND_US) <= DistancePolicy.gpsMaxChordMps

    private companion object {
        val REJECTED = GpsStep(false, false, null)
    }
}

internal class GpsAltitude {
    private var anchorM: Double? = null
    private var anchorQ = 0L
    private val ascent = CheckedSum()
    private val descent = CheckedSum()
    private var segments = 0L
    private var previous: LocationFix? = null

    fun append(fix: LocationFix, step: GpsStep) {
        if (!step.accepted) {
            anchorM = null
            previous = null
            return
        }
        val old = previous
        previous = fix
        if (step.edge == null || old == null) {
            anchorM = null
            seed(fix)
            return
        }
        if (
            !valid(fix.mslM, altitudeRange) ||
                !valid(fix.mslVerticalAccuracyM, accuracyRange) ||
                !valid(old.mslVerticalAccuracyM, accuracyRange)
        ) {
            anchorM = null
            return
        }
        val anchor = anchorM
        if (anchor == null) {
            seed(fix)
            return
        }
        segments++
        val altitude = requireNotNull(fix.mslM)
        val delta = altitude - anchor
        val threshold =
            maxOf(MIN_STEP_M, requireNotNull(fix.mslVerticalAccuracyM), requireNotNull(old.mslVerticalAccuracyM))
        if (PureMath.abs(delta) >= threshold) {
            val q = FixedPoint.quantize(altitude, ALTITUDE_QUANTUM)
            val change = FixedPoint.subtract(q, anchorQ)
            if (delta > 0) ascent.add(change) else descent.add(-change)
            anchorM = altitude
            anchorQ = q
        }
    }

    fun ascentM(): Double? = total(ascent)

    fun descentM(): Double? = total(descent)

    private fun total(sum: CheckedSum): Double? {
        if (segments == 0L) return null
        return sum.exact()?.let { FixedPoint.divide(it, ALTITUDE_QUANTUM) }
    }

    private fun seed(fix: LocationFix) {
        if (valid(fix.mslM, altitudeRange) && valid(fix.mslVerticalAccuracyM, accuracyRange)) {
            val altitude = requireNotNull(fix.mslM)
            anchorM = altitude
            anchorQ = FixedPoint.quantize(altitude, ALTITUDE_QUANTUM)
        }
    }

    private fun valid(value: Double?, range: ClosedFloatingPointRange<Double>?): Boolean =
        value != null && value.isFinite() && range?.contains(value) != false

    private companion object {
        val altitudeRange = MetricCatalog.of(MetricId.altitudeMeters).validRange
        val accuracyRange = MetricCatalog.of(MetricId.verticalAccuracyM).validRange
        const val MIN_STEP_M = 3.0
        const val ALTITUDE_QUANTUM = 1_000L
    }
}

public class DistanceReducer(private val range: TimeRange, initial: DistanceTotals?) {
    private val distance = CheckedSum().also { it.add(initial?.distanceQ ?: 0L) }
    private val covered = CheckedSum().also { it.add(initial?.coveredUs ?: 0L) }
    private var unresolved = initial?.unresolvedBoundary ?: false
    private var overflow = false

    public fun append(interval: DistanceInterval) {
        val a = maxOf(range.startUs, interval.range.startUs)
        val b = minOf(range.endUs, interval.range.endUs)
        if (b <= a) return
        try {
            distance.add(share(interval, a, b))
            covered.add(b - a)
        } catch (_: ArithmeticException) {
            overflow = true
        }
    }

    public fun result(timerUs: Long): Bounded<DistanceTotals> {
        val distanceQ = distance.exact()
        val coveredUs = covered.exact()
        if (overflow || distanceQ == null || coveredUs == null) return Bounded.Capacity
        return Bounded.Done(
            DistanceTotals(distanceQ, coveredUs, unresolved || uncoveredUs(timerUs, coveredUs) > 1_000L, unresolved)
        )
    }
}

internal fun share(interval: DistanceInterval, a: Long, b: Long): Long {
    val start = interval.range.startUs
    val durationUs = interval.range.endUs - start
    val v0 = interval.speed0Q
    val v1 = interval.speed1Q
    if (v0 == null || v1 == null) return FixedPoint.share(interval.distanceQ, b - a, durationUs)
    val va = FixedPoint.interpolate(v0, v1, a - start, durationUs)
    val vb = FixedPoint.interpolate(v0, v1, b - start, durationUs)
    return controllerContribution(va, vb, b - a)
}

internal fun uncoveredUs(timerUs: Long, coveredUs: Long): Long = maxOf(0L, timerUs - coveredUs)

internal fun validSpeed(speed: Double?, accuracy: Double? = null): Boolean =
    speed != null &&
        speed.isFinite() &&
        speed >= 0.0 &&
        speed <= MAX_SPEED_MPS &&
        (accuracy == null || accuracy.isFinite() && accuracy >= 0)

private fun controllerContribution(v0: Long, v1: Long, durationUs: Long): Long =
    FixedPoint.halfEven(FixedPoint.product(FixedPoint.add(v0, v1), durationUs), 2 * SECOND_US)

private fun LocationFix.distancePoint(): GpsPoint? {
    val interval = activeInterval ?: return null
    val accuracy = horizontalAccuracyM
    val valid =
        !barrier &&
            elapsedUs >= 0 &&
            latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            accuracy != null &&
            MetricCatalog.of(MetricId.horizontalAccuracyM).validRange?.contains(accuracy) == true
    return if (valid) GpsPoint(seq, elapsedUs, utcUs, interval, latitude, longitude, speedMps, speedAccuracyMps)
    else null
}

private const val SECOND_US = 1_000_000L
private const val SPEED_QUANTUM = 1_000_000L
private const val DISTANCE_QUANTUM = 1_000_000L
private const val MAX_SPEED_MPS = 40.0
private const val STATIONARY_MPS = 0.5
