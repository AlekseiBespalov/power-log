package app.powerlog.domain.health

import app.powerlog.domain.analytics.FixedPoint
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricDefinition
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.core.time.UtcRange
import kotlinx.serialization.Serializable

@Serializable
public data class RoutePoint(
    public val utcUs: Long,
    public val latitude: Double,
    public val longitude: Double,
    public val altitudeM: Double?,
    public val horizontalAccuracyM: Double,
    public val verticalAccuracyM: Double?,
    public val courseDeg: Double?,
    public val speedMps: Double?,
) {
    public companion object {
        public fun of(fix: LocationFix): RoutePoint? {
            val accuracy = fix.horizontalAccuracyM ?: return null
            return RoutePoint(
                fix.utcUs,
                fix.latitude,
                fix.longitude,
                fix.mslM,
                accuracy,
                fix.mslVerticalAccuracyM,
                fix.courseDeg,
                fix.speedMps,
            )
        }
    }
}

@Serializable public data class IndexedRoutePoint(public val index: Long, public val point: RoutePoint)

public data class SecondBounds(public val n: Long, public val range: UtcRange)

public sealed interface HealthPlan {
    public val syncId: String
    public val syncVersion: Int
        get() = HealthIds.syncVersion

    public data class Workout(
        override val syncId: String,
        public val rideId: String,
        public val startUtcUs: Long,
        public val endUtcUs: Long?,
        public val indoor: Boolean,
        public val laps: List<UtcRange>,
        public val pauses: List<UtcRange>,
    ) : HealthPlan

    @Serializable
    public data class Quantity(
        override val syncId: String,
        public val quantity: HealthQuantity,
        public val startUtcUs: Long,
        public val endUtcUs: Long,
        public val value: Double,
    ) : HealthPlan

    public data class Route(override val syncId: String, public val points: List<RoutePoint>) : HealthPlan
}

public sealed interface HealthConnectRecordPlan {
    public data class Session(public val plan: HealthPlan.Workout, public val route: List<RoutePoint>) :
        HealthConnectRecordPlan

    public data class Record(public val plan: HealthPlan.Quantity) : HealthConnectRecordPlan
}

public object HealthPublication {
    private const val SECOND_US = 1_000_000L

    public fun bounds(rideStartUtcUs: Long, cutoffUtcUs: Long, n: Long): SecondBounds {
        val start = rideStartUtcUs + n * SECOND_US
        return SecondBounds(n, UtcRange(start, maxOf(start, minOf(cutoffUtcUs, start + SECOND_US))))
    }

    public fun eligible(bounds: SecondBounds, nowUtcUs: Long, stopped: Boolean): Boolean =
        stopped || bounds.range.endUtcUs <= nowUtcUs - HealthPolicy.bufferUs

    public fun second(
        rideId: RideId,
        bounds: SecondBounds,
        quantity: HealthQuantity,
        rows: TelemetryRows,
        sessions: Map<Int, ControllerSession>,
        fixes: List<LocationFix>,
    ): HealthPlan.Quantity? {
        val metric =
            when (quantity) {
                HealthQuantity.riderPower -> MetricId.humanPowerW
                HealthQuantity.cyclingCadence -> MetricId.cadenceRpm
                HealthQuantity.cyclingSpeed -> MetricId.speedMps
                HealthQuantity.cyclingDistance -> return null
            }
        val startUs = bounds.n * SECOND_US
        val second =
            SecondIntegral(MetricCatalog.of(metric), startUs, startUs + bounds.range.endUtcUs - bounds.range.startUtcUs)
        if (quantity == HealthQuantity.cyclingSpeed)
            fixes.sortedWith(compareBy({ it.elapsedUs }, { it.seq })).forEach { fix ->
                second.append(fix.elapsedUs, fix.speedMps?.takeIf { speedValid(fix) }, fix.activeInterval, fix.run)
            }
        else {
            val limit =
                if (quantity == HealthQuantity.riderPower) HealthPolicy.maxRiderPowerW else HealthPolicy.maxCadenceRpm
            val order =
                (0 until rows.rows).sortedWith(
                    compareBy({ rows.elapsedUs[it] }, { rows.sessionNo[it] }, { rows.seq[it] })
                )
            for (row in order) {
                val session = sessions[rows.sessionNo[row]]
                val value = session?.let { rows.value(metric, row, it) }?.takeIf { it in 0.0..limit }
                val interval = rows.activeInterval[row].takeIf { it != 0 }
                second.append(rows.elapsedUs[row].toLong(), value, interval, rows.run[row])
            }
        }
        val value = second.value() ?: return null
        return HealthPlan.Quantity(
            HealthIds.second(rideId, quantity, bounds.n),
            quantity,
            bounds.range.startUtcUs,
            bounds.range.endUtcUs,
            value,
        )
    }

    private fun speedValid(fix: LocationFix): Boolean {
        val speed = fix.speedMps ?: return false
        val speedAccuracy = fix.speedAccuracyMps
        val accuracy = fix.horizontalAccuracyM ?: return false
        return !fix.barrier &&
            speed.isFinite() &&
            speed in 0.0..HealthPolicy.maxSpeedMps &&
            (speedAccuracy == null || speedAccuracy.isFinite() && speedAccuracy >= 0) &&
            accuracy.isFinite() &&
            accuracy in 0.0..HealthPolicy.maxSpeedFixAccuracyM
    }

    public fun distance(rideId: RideId, edge: GpsEdge): HealthPlan.Quantity? {
        val interval = edge.interval
        if (interval.distanceQ <= 0 || interval.end.utcUs <= interval.start.utcUs) return null
        return HealthPlan.Quantity(
            HealthIds.distance(rideId, edge.firstSeq, edge.lastSeq),
            HealthQuantity.cyclingDistance,
            interval.start.utcUs,
            interval.end.utcUs,
            FixedPoint.divide(interval.distanceQ, MetricCatalog.of(MetricId.gpsDistanceMeters).quantum),
        )
    }

    public fun workout(ride: Ride, timeline: ActiveTimeline): HealthPlan.Workout {
        val start = ride.startedUtcUs
        fun utc(range: TimeRange) = UtcRange(start + range.startUs, start + range.endUs)
        val intervals = timeline.intervals
        val finish = ride.end?.elapsedUs
        val pauses = intervals.mapIndexedNotNull { index, interval ->
            val resume = intervals.getOrNull(index + 1)?.startUs ?: finish ?: interval.endUs
            TimeRange(interval.endUs, resume).takeIf { resume > interval.endUs }
        }
        return HealthPlan.Workout(
            syncId = HealthIds.workout(ride.id),
            rideId = ride.id.value,
            startUtcUs = start,
            endUtcUs = ride.end?.utcUs,
            indoor = ride.options.indoor,
            laps = timeline.laps.map { it.range }.filter { it.endUs > it.startUs }.map(::utc),
            pauses = pauses.map(::utc),
        )
    }

    public fun healthConnect(
        ride: Ride,
        timeline: ActiveTimeline,
        route: List<RoutePoint>,
    ): HealthConnectRecordPlan.Session = HealthConnectRecordPlan.Session(workout(ride, timeline), route)
}

private class SecondIntegral(
    private val definition: MetricDefinition,
    private val startUs: Long,
    private val endUs: Long,
) {
    private var previous: Reading? = null
    private var count = 0L
    private var integralQUs = 0L
    private var coveredUs = 0L
    private var overflow = false

    private class Reading(val timeUs: Long, val valueQ: Long, val interval: Int, val run: Int)

    fun append(timeUs: Long, value: Double?, interval: Int?, run: Int) {
        val q =
            if (value == null || interval == null) null
            else
                try {
                    FixedPoint.quantize(value, definition.quantum)
                } catch (_: ArithmeticException) {
                    null
                }
        if (q == null || interval == null) {
            previous = null
            return
        }
        if (timeUs in startUs until endUs) count++
        val last = previous
        if (last != null && last.interval == interval && last.run == run) {
            val durationUs = timeUs - last.timeUs
            if (durationUs > 0 && durationUs <= definition.integrationGapUs) {
                val from = maxOf(last.timeUs, startUs)
                val to = minOf(timeUs, endUs)
                if (to > from)
                    try {
                        val a = FixedPoint.interpolate(last.valueQ, q, from - last.timeUs, durationUs)
                        val b = FixedPoint.interpolate(last.valueQ, q, to - last.timeUs, durationUs)
                        integralQUs = FixedPoint.add(integralQUs, FixedPoint.trapezoid(a, b, to - from))
                        coveredUs += to - from
                    } catch (_: ArithmeticException) {
                        overflow = true
                    }
            }
        }
        previous = Reading(timeUs, q, interval, run)
    }

    fun value(): Double? =
        if (count == 0L || coveredUs == 0L || overflow) null
        else
            try {
                FixedPoint.divide(integralQUs, FixedPoint.product(definition.quantum, coveredUs))
            } catch (_: ArithmeticException) {
                null
            }
}
