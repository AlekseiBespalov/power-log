package app.powerlog.domain.core.analysis.energyperdistance

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.positiveArea
import app.powerlog.domain.core.time.TimeRange
import kotlinx.serialization.Serializable

public object EnergyPerDistancePolicy {
    public val smoothingUs: Set<Long> = setOf(0L, 5_000_000L, 30_000_000L, 60_000_000L)

    public fun totalRefreshUs(spanUs: Long): Long =
        when {
            spanUs < 60 * SECOND_US -> 2 * SECOND_US
            spanUs < 600 * SECOND_US -> 5 * SECOND_US
            spanUs < 3_600 * SECOND_US -> 15 * SECOND_US
            else -> 30 * SECOND_US
        }

    public fun contextUs(smoothingUs: Long): Long = maxOf(18 * SECOND_US, smoothingUs + 13 * SECOND_US)

    internal const val controllerIntervalUs: Long = 2_500_000L

    private const val SECOND_US = 1_000_000L
}

@Serializable
public data class EnergyPerDistanceTotals(
    public val energyWh: Double,
    public val distanceM: Double,
    public val whPerKm: Double?,
)

@Serializable
internal data class DistanceSpan(
    val startUs: Long,
    val endUs: Long,
    val meters: Double,
    val speed0: Double?,
    val speed1: Double?,
) {
    fun amount(a: Long, b: Long): Double {
        val duration = (endUs - startUs).toDouble()
        if (speed0 == null || speed1 == null) return meters * (b - a) / duration
        val u = speed0 + (speed1 - speed0) * (a - startUs) / duration
        val v = speed0 + (speed1 - speed0) * (b - startUs) / duration
        return (u + v) * (b - a) / 2_000_000.0
    }
}

@Serializable
internal data class EnergySpan(
    val startUs: Long,
    val endUs: Long,
    val wh: Double,
    val rate0: Double?,
    val rate1: Double?,
    val indivisible: Boolean,
) {
    fun amount(a: Long, b: Long): Double {
        if (a == startUs && b == endUs) return wh
        val u = requireNotNull(rate0)
        val v = requireNotNull(rate1)
        val duration = (endUs - startUs).toDouble()
        return positiveArea(u + (v - u) * (a - startUs) / duration, u + (v - u) * (b - startUs) / duration, b - a)
    }
}

@Serializable
internal data class EnergyLane(
    val pending: List<EnergySpan>,
    val reachedUs: Long,
    val energyWh: Double,
    val distanceM: Double,
    val coveredUs: Long,
)

@Serializable
internal data class RiderReading(val elapsedUs: Long, val watts: Double, val activeInterval: Int, val run: Int)

@ConsistentCopyVisibility
@Serializable
public data class EnergyPerDistanceState
internal constructor(
    internal val distance: List<DistanceSpan>,
    internal val distanceThroughUs: Long,
    internal val battery: EnergyLane,
    internal val rider: EnergyLane,
    internal val previousRider: RiderReading?,
    internal val highWaterUs: Long,
    internal val refused: Boolean,
)

public class EnergyPerDistanceReducer(
    state: EnergyPerDistanceState?,
    private val method: BatteryMethod,
    private val source: DistanceSource,
    private val range: TimeRange? = null,
) : TelemetryConsumer {
    private class Lane(origin: EnergyLane?) {
        val pending = ArrayDeque(origin?.pending.orEmpty())
        var reachedUs = origin?.reachedUs ?: Long.MIN_VALUE
        var energyWh = origin?.energyWh ?: 0.0
        var distanceM = origin?.distanceM ?: 0.0
        var coveredUs = origin?.coveredUs ?: 0L

        fun saved() = EnergyLane(pending.toList(), reachedUs, energyWh, distanceM, coveredUs)
    }

    private val distance = ArrayDeque(state?.distance.orEmpty())
    private var distanceThroughUs = state?.distanceThroughUs ?: Long.MIN_VALUE
    private val battery = Lane(state?.battery)
    private val rider = Lane(state?.rider)
    private var previousRider = state?.previousRider
    private var highWaterUs = state?.highWaterUs ?: Long.MIN_VALUE
    private var refused = state?.refused ?: false
    private val energyMetric =
        if (method == BatteryMethod.controller) ConsumptionMetric.controllerEnergyWh
        else ConsumptionMetric.calculatedEnergyWh
    private val riderRange = MetricCatalog.of(MetricId.humanPowerW).validRange

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            advance(elapsedUs)
            val watts = rows.value(MetricId.humanPowerW, row, session)?.takeIf { riderRange?.contains(it) != false }
            val activeInterval = rows.activeInterval[row]
            val old = previousRider
            val current =
                if (watts != null && activeInterval != 0) RiderReading(elapsedUs, watts, activeInterval, rows.run[row])
                else null
            previousRider = current
            if (
                current == null ||
                    old == null ||
                    current.elapsedUs <= old.elapsedUs ||
                    current.elapsedUs - old.elapsedUs > EnergyPerDistancePolicy.controllerIntervalUs ||
                    current.activeInterval != old.activeInterval ||
                    current.run != old.run
            )
                continue
            enqueue(
                rider,
                EnergySpan(
                    old.elapsedUs,
                    elapsedUs,
                    positiveArea(old.watts, current.watts, elapsedUs - old.elapsedUs),
                    old.watts,
                    current.watts,
                    false,
                ),
            )
        }
    }

    public fun append(fact: Fact): Unit = Unit

    public fun distance(interval: DistanceInterval) {
        val start = interval.range.startUs
        val end = interval.range.endUs
        advance(end)
        if (start < 0 || end <= start || start < distanceThroughUs) return
        if ((source == DistanceSource.controller) != (interval.speed0Q != null && interval.speed1Q != null)) return
        val meters = interval.distanceQ / METERS_QUANTUM
        val speed0 = interval.speed0Q?.let { it / SPEED_QUANTUM }
        val speed1 = interval.speed1Q?.let { it / SPEED_QUANTUM }
        if (!meters.isFinite() || meters < 0) return
        distance.addLast(DistanceSpan(start, end, meters, speed0, speed1))
        distanceThroughUs = end
        decide(battery)
        decide(rider)
    }

    public fun battery(interval: ConsumptionInterval) {
        if (interval.metric != energyMetric) return
        advance(interval.range.endUs)
        if (interval.baseline) {
            battery.reachedUs = maxOf(battery.reachedUs, interval.range.startUs)
            return
        }
        enqueue(
            battery,
            EnergySpan(
                interval.range.startUs,
                interval.range.endUs,
                interval.endValue - interval.startValue,
                interval.rate0,
                interval.rate1,
                interval.indivisible,
            ),
        )
    }

    public fun result(): Bounded<Map<MetricId, EnergyPerDistanceTotals>> =
        if (refused) Bounded.Capacity
        else Bounded.Done(mapOf(MetricId.batteryWhPerKm to totals(battery), MetricId.riderWhPerKm to totals(rider)))

    public fun state(): EnergyPerDistanceState =
        EnergyPerDistanceState(
            distance.toList(),
            distanceThroughUs,
            battery.saved(),
            rider.saved(),
            previousRider,
            highWaterUs,
            refused,
        )

    private fun totals(lane: Lane): EnergyPerDistanceTotals =
        EnergyPerDistanceTotals(
            lane.energyWh,
            lane.distanceM,
            if (lane.distanceM > 0 && lane.coveredUs > 0) lane.energyWh * 1000 / lane.distanceM else null,
        )

    private fun enqueue(lane: Lane, span: EnergySpan) {
        if (span.endUs <= span.startUs || span.startUs < lane.reachedUs || !span.wh.isFinite() || span.wh < 0) return
        lane.reachedUs = span.endUs
        if (refused) return
        if (lane.pending.size == MAX_PENDING) {
            refused = true
            battery.pending.clear()
            rider.pending.clear()
            distance.clear()
            return
        }
        lane.pending.addLast(span)
        decide(lane)
    }

    private fun advance(timeUs: Long) {
        highWaterUs = maxOf(highWaterUs, timeUs)
        decide(battery)
        decide(rider)
        while (distance.isNotEmpty() && distance.first().endUs < highWaterUs - DISTANCE_KEPT_US) distance.removeFirst()
    }

    private fun decide(lane: Lane) {
        while (true) {
            val span = lane.pending.firstOrNull() ?: return
            if (span.endUs > distanceThroughUs && span.endUs >= highWaterUs - UNMATCHED_KEPT_US) return
            lane.pending.removeFirst()
            if (matched(span.startUs, span.endUs) == null) continue
            val a = maxOf(range?.startUs ?: Long.MIN_VALUE, span.startUs)
            val b = minOf(range?.endUs ?: Long.MAX_VALUE, span.endUs)
            if (b <= a || span.indivisible && (a != span.startUs || b != span.endUs)) continue
            val meters = matched(a, b) ?: continue
            lane.energyWh += span.amount(a, b)
            lane.distanceM += meters
            lane.coveredUs += b - a
        }
    }

    private fun matched(start: Long, end: Long): Double? {
        var through = end
        var meters = 0.0
        for (index in distance.indices.reversed()) {
            val edge = distance[index]
            if (edge.startUs >= through) continue
            if (edge.endUs < through) return null
            val next = maxOf(start, edge.startUs)
            meters += edge.amount(next, through)
            through = next
            if (through == start) return meters
        }
        return null
    }

    private companion object {
        const val METERS_QUANTUM = 1_000_000.0
        const val SPEED_QUANTUM = 1_000_000.0
        const val MAX_PENDING = 1_024
        const val UNMATCHED_KEPT_US = 10_000_000L
        const val DISTANCE_KEPT_US = 20_000_000L
    }
}
