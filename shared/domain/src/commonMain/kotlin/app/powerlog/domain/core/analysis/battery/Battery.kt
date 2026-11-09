package app.powerlog.domain.core.analysis.battery

import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.numeric.positiveArea
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.core.time.UtcRange
import kotlinx.serialization.Serializable

@Serializable
public enum class BatteryMethod {
    controller,
    calculated,
}

@Serializable
public enum class ConsumptionMetric {
    controllerEnergyWh,
    controllerChargeAh,
    calculatedEnergyWh,
    calculatedChargeAh,
}

public object BatteryPolicy {
    public const val maxCounterCurrentA: Double = 200.0
    public const val maxCounterPowerW: Double = 25_000.0
    public const val integrationLimitUs: Long = 2_500_000L
}

public data class ConsumptionInterval(
    public val metric: ConsumptionMetric,
    public val range: TimeRange,
    public val utc: UtcRange,
    public val startValue: Double,
    public val endValue: Double,
    public val rate0: Double?,
    public val rate1: Double?,
    public val indivisible: Boolean,
    public val segment: Int,
    public val activeInterval: Int,
    public val baseline: Boolean,
)

@Serializable
public data class ConsumptionTotals(
    public val amount: Double,
    public val coveredUs: Long,
    public val available: Boolean,
    public val unresolvedBoundary: Boolean,
    public val partial: Boolean,
)

@Serializable
internal data class ConsumptionReading(
    val elapsedUs: Long,
    val utcUs: Long,
    val activeInterval: Int,
    val run: Int,
    val identity: ControllerIdentity?,
    val value: Double,
)

@Serializable
internal data class ConsumptionLane(val total: Double, val segment: Int, val previous: ConsumptionReading?)

@ConsistentCopyVisibility
@Serializable
public data class BatteryConsumptionState
internal constructor(internal val lanes: List<ConsumptionLane>, internal val highWaterUs: Long?)

public class BatteryConsumptionReducer(state: BatteryConsumptionState?) {
    private val totals = DoubleArray(LANES.size) { state?.lanes?.get(it)?.total ?: 0.0 }
    private val segments = IntArray(LANES.size) { state?.lanes?.get(it)?.segment ?: 0 }
    private val previous = Array(LANES.size) { state?.lanes?.get(it)?.previous }
    private var highWaterUs = state?.highWaterUs
    private val values = arrayOfNulls<Double>(LANES.size)

    public fun append(
        rows: TelemetryRows,
        from: Int,
        to: Int,
        sessions: Map<Int, ControllerSession>,
    ): List<ConsumptionInterval> {
        val intervals = mutableListOf<ConsumptionInterval>()
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            reading(rows, row, session, intervals)
        }
        return intervals
    }

    public fun state(): BatteryConsumptionState =
        BatteryConsumptionState(
            LANES.indices.map { ConsumptionLane(totals[it], segments[it], previous[it]) },
            highWaterUs,
        )

    private fun reading(
        rows: TelemetryRows,
        row: Int,
        session: ControllerSession,
        into: MutableList<ConsumptionInterval>,
    ) {
        val elapsedUs = rows.elapsedUs[row].toLong()
        val water = highWaterUs
        if (elapsedUs >= 0 && water != null && elapsedUs <= water) {
            for (lane in LANES.indices) breakLane(lane)
            return
        }
        if (elapsedUs >= 0) highWaterUs = elapsedUs
        val current = rows.value(MetricId.batteryCurrentA, row, session)?.takeIf { it.isFinite() }
        val voltage = rows.value(MetricId.batteryVoltageV, row, session)?.takeIf { it.isFinite() && it >= 0 }
        values[CHARGE] = counter(rows.value(MetricId.consumedAh, row, session))
        values[ENERGY] = counter(rows.value(MetricId.consumedWh, row, session))
        values[CURRENT] = current
        values[POWER] = if (current != null && voltage != null) (current * voltage).takeIf { it.isFinite() } else null
        val activeInterval = rows.activeInterval[row]
        for (lane in LANES.indices) {
            val value = values[lane]
            if (elapsedUs < 0 || activeInterval == 0 || value == null) {
                breakLane(lane)
                continue
            }
            val utcUs = rows.utcUs[row].toLong()
            lane(lane, ConsumptionReading(elapsedUs, utcUs, activeInterval, rows.run[row], session.identity, value))
                ?.let(into::add)
        }
    }

    private fun lane(lane: Int, next: ConsumptionReading): ConsumptionInterval? {
        val old = previous[lane]
        val counter = lane == CHARGE || lane == ENERGY
        val durationUs = if (old == null) 0L else next.elapsedUs - old.elapsedUs
        if (old != null && durationUs <= 0) {
            previous[lane] = null
            segments[lane]++
            return null
        }
        previous[lane] = next
        val same = old != null && durationUs <= BatteryPolicy.integrationLimitUs && old.run == next.run
        val limit = if (lane == CHARGE) BatteryPolicy.maxCounterCurrentA else BatteryPolicy.maxCounterPowerW
        val bridged =
            counter &&
                old != null &&
                durationUs < ControllerPolicy.joinUs &&
                old.identity == next.identity &&
                (next.value - old.value) * HOUR_US <= limit * durationUs
        val connects =
            old != null &&
                old.activeInterval == next.activeInterval &&
                (!counter || next.value >= old.value) &&
                (same || bridged)
        val metric = LANES[lane]
        if (!connects) {
            if (old != null) segments[lane]++
            val total = totals[lane]
            return ConsumptionInterval(
                metric,
                TimeRange(next.elapsedUs, next.elapsedUs),
                UtcRange(next.utcUs, next.utcUs),
                total,
                total,
                null,
                null,
                counter,
                segments[lane],
                next.activeInterval,
                true,
            )
        }
        requireNotNull(old)
        val before = totals[lane]
        totals[lane] =
            if (counter) {
                val delta =
                    FixedPoint.subtract(
                        FixedPoint.quantize(next.value, COUNTER_QUANTUM),
                        FixedPoint.quantize(old.value, COUNTER_QUANTUM),
                    )
                FixedPoint.divide(
                    FixedPoint.add(FixedPoint.quantize(before, COUNTER_QUANTUM), delta),
                    COUNTER_QUANTUM,
                )
            } else before + positiveArea(old.value, next.value, durationUs)
        return ConsumptionInterval(
            metric,
            TimeRange(old.elapsedUs, next.elapsedUs),
            UtcRange(old.utcUs, next.utcUs),
            before,
            totals[lane],
            if (counter) null else old.value,
            if (counter) null else next.value,
            counter,
            segments[lane],
            next.activeInterval,
            false,
        )
    }

    private fun breakLane(lane: Int) {
        if (previous[lane] != null) segments[lane]++
        previous[lane] = null
    }

    private fun counter(value: Double?): Double? {
        if (value == null || !value.isFinite() || value < 0) return null
        return try {
            FixedPoint.divide(FixedPoint.quantize(value, COUNTER_QUANTUM), COUNTER_QUANTUM)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private companion object {
        val LANES =
            listOf(
                ConsumptionMetric.controllerChargeAh,
                ConsumptionMetric.controllerEnergyWh,
                ConsumptionMetric.calculatedChargeAh,
                ConsumptionMetric.calculatedEnergyWh,
            )
        const val CHARGE = 0
        const val ENERGY = 1
        const val CURRENT = 2
        const val POWER = 3
        const val COUNTER_QUANTUM = 10_000L
        const val HOUR_US = 3_600_000_000.0
    }
}

public class ConsumptionReducer(
    private val metric: ConsumptionMetric,
    private val range: TimeRange,
    initial: ConsumptionTotals?,
) {
    private var amount = initial?.amount ?: 0.0
    private var coveredUs = initial?.coveredUs ?: 0L
    private var available = initial?.available ?: false
    private var unresolved = initial?.unresolvedBoundary ?: false

    public fun append(interval: ConsumptionInterval) {
        if (interval.metric != metric) return
        val start = interval.range.startUs
        val end = interval.range.endUs
        if (interval.baseline) {
            if (start >= range.startUs && start <= range.endUs) available = true
            return
        }
        val a = maxOf(range.startUs, start)
        val b = minOf(range.endUs, end)
        if (b <= a) return
        available = true
        if (interval.indivisible && (a != start || b != end)) {
            unresolved = true
            return
        }
        amount +=
            if (interval.indivisible) interval.endValue - interval.startValue
            else {
                val durationUs = (end - start).toDouble()
                val first = requireNotNull(interval.rate0)
                val delta = requireNotNull(interval.rate1) - first
                positiveArea(
                    first + delta * ((a - start) / durationUs),
                    first + delta * ((b - start) / durationUs),
                    b - a,
                )
            }
        coveredUs += b - a
    }

    public fun result(timerUs: Long): ConsumptionTotals =
        ConsumptionTotals(amount, coveredUs, available, unresolved, unresolved || timerUs - coveredUs > 1_000L)
}

public data class SelectedConsumption(public val energy: ConsumptionTotals?, public val charge: ConsumptionTotals?) {
    public companion object {
        public fun of(method: BatteryMethod, totals: Map<ConsumptionMetric, ConsumptionTotals>): SelectedConsumption =
            when (method) {
                BatteryMethod.controller ->
                    SelectedConsumption(
                        totals[ConsumptionMetric.controllerEnergyWh],
                        totals[ConsumptionMetric.controllerChargeAh],
                    )
                BatteryMethod.calculated ->
                    SelectedConsumption(
                        totals[ConsumptionMetric.calculatedEnergyWh],
                        totals[ConsumptionMetric.calculatedChargeAh],
                    )
            }
    }
}

internal fun ConsumptionMetric.energy(): Boolean =
    this == ConsumptionMetric.controllerEnergyWh || this == ConsumptionMetric.calculatedEnergyWh
