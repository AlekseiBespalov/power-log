package app.powerlog.core.analytics

import app.powerlog.core.protocol.*

object BatteryConsumptionPolicy {
    val metrics: List<String> =
        listOf("controllerChargeAh", "controllerEnergyWh", "calculatedChargeAh", "calculatedEnergyWh")

    // The controller keeps counting while its Bluetooth link drops, so a counter increase across a loss
    // shorter than the six-second hold is one indivisible edge. The counter base cannot be confirmed across
    // a loss, so the increase must also stay within what any supported controller could draw.
    const val maximumCounterEdgeUs = 6000000L
    const val maximumCounterCurrentA = 200.0
    const val maximumCounterPowerW = 25000.0

    fun isMetric(metric: String): Boolean = metric in metrics

    fun validSource(source: String): Boolean = source == "controller" || source == "calculated"
}

/** One original edge, or a zero-duration baseline. Rates are A or W, so the integral is Ah or Wh. */
data class BatteryConsumptionInterval(
    val metric: String,
    val startUs: Long,
    val endUs: Long,
    val startValue: Double,
    val endValue: Double,
    val rate0: Double?,
    val rate1: Double?,
    val indivisible: Boolean,
    val segment: Int,
    val activeInterval: Int,
    val baseline: Boolean = false,
) {
    init {
        require(BatteryConsumptionPolicy.isMetric(metric))
        require(startUs >= 0 && endUs >= startUs && segment >= 0 && activeInterval >= 0)
        require(startValue.isFinite() && endValue.isFinite() && startValue >= 0 && endValue >= startValue)
        require(baseline == (startUs == endUs))
        require(
            baseline ||
                endUs - startUs <=
                    if (indivisible) BatteryConsumptionPolicy.maximumCounterEdgeUs
                    else DistancePolicy.maximumControllerIntervalUs
        )
        require((rate0 == null) == (rate1 == null))
        require(rate0?.isFinite() != false && rate1?.isFinite() != false)
        require(indivisible || baseline || rate0 != null)
        require(!baseline || startValue == endValue)
    }

    fun range(startUs: Long, endUs: Long): BatteryConsumptionRange {
        require(startUs >= 0 && endUs >= startUs)
        if (baseline) {
            return BatteryConsumptionRange(0.0, 0L, this.startUs in startUs..endUs, false, endUs > startUs)
        }
        val a = maxOf(startUs, this.startUs)
        val b = minOf(endUs, this.endUs)
        if (b <= a) return BatteryConsumptionRange(0.0, 0L, false, false, endUs > startUs)
        if (indivisible && (a != this.startUs || b != this.endUs)) {
            return BatteryConsumptionRange(0.0, 0L, true, true, true)
        }
        val amount =
            if (indivisible) endValue - startValue
            else {
                val duration = (this.endUs - this.startUs).toDouble()
                val first = requireNotNull(rate0)
                val delta = requireNotNull(rate1) - first
                val va = first + delta * ((a - this.startUs) / duration)
                val vb = first + delta * ((b - this.startUs) / duration)
                positiveArea(va, vb, b - a)
            }
        return BatteryConsumptionRange(amount, b - a, true, false, endUs - startUs - (b - a) > 1000L)
    }
}

data class BatteryConsumptionRange(
    val amount: Double,
    val coveredUs: Long,
    val available: Boolean,
    val unresolvedBoundary: Boolean,
    val partial: Boolean,
) {
    val coveredSeconds: Double
        get() = coveredUs / 1000000.0
}

class BatteryConsumptionReducer(
    val metric: String,
    val startUs: Long,
    val endUs: Long,
    initial: BatteryConsumptionRange?,
) {
    constructor(metric: String, startUs: Long, endUs: Long) : this(metric, startUs, endUs, null)

    private var amount = initial?.amount ?: 0.0
    private var coveredUs = initial?.coveredUs ?: 0L
    private var available = initial?.available ?: false
    private var unresolved = initial?.unresolvedBoundary ?: false

    init {
        require(BatteryConsumptionPolicy.isMetric(metric) && startUs >= 0 && endUs >= startUs)
    }

    fun append(interval: BatteryConsumptionInterval) {
        require(interval.metric == metric)
        val clipped = interval.range(startUs, endUs)
        amount += clipped.amount
        require(amount.isFinite())
        coveredUs = FixedPoint.add(coveredUs, clipped.coveredUs)
        available = available || clipped.available
        unresolved = unresolved || clipped.unresolvedBoundary
    }

    fun result(timerUs: Long): BatteryConsumptionRange {
        require(timerUs >= 0)
        return BatteryConsumptionRange(
            amount,
            coveredUs,
            available,
            unresolved,
            unresolved || timerUs - coveredUs > 1000L,
        )
    }
}

private data class ConsumptionObservation(
    val timeUs: Long,
    val activeInterval: Int,
    val continuity: String,
    val value: Double,
)

private class ConsumptionLane(
    val metric: String,
    var total: Double = 0.0,
    var segment: Int = 0,
    var previous: ConsumptionObservation? = null,
)

/** Keeps four totals and predecessor observations, independent of ride length and source-page boundaries. */
class BatteryConsumptionKernel @Throws(IllegalArgumentException::class) constructor(checkpoint: String?) {
    constructor() : this(null)

    private val lanes = BatteryConsumptionPolicy.metrics.map(::ConsumptionLane)
    private var highWaterUs: Long? = null

    init {
        if (checkpoint != null) {
            val root = JsonCodec.parse(checkpoint, arrayLimit = 4, byteLimit = 16384, wireNumbers = false).obj()
            root.closed("version lanes highWaterUs")
            highWaterUs =
                root.required("highWaterUs").let {
                    if (it == Nil) null else it.string().toLong().also { value -> require(value >= 0) }
                }
            require(root.required("version").number() == 1.0)
            val saved = root.required("lanes").array()
            require(saved.size == lanes.size)
            saved.zip(lanes).forEach { (value, lane) ->
                val item = value.obj()
                item.closed("metric total segment", "previous")
                require(item.required("metric").string() == lane.metric)
                lane.total = item.required("total").number().also { require(it.isFinite() && it >= 0) }
                lane.segment =
                    item.required("segment").number().let {
                        require(it >= 0 && it <= Int.MAX_VALUE && it % 1 == 0.0)
                        it.toInt()
                    }
                lane.previous =
                    item["previous"]?.obj()?.let { old ->
                        old.closed("timeUs activeInterval continuity value")
                        val time = old.required("timeUs").string().toLong().also { require(it >= 0) }
                        val active =
                            old.required("activeInterval").number().also {
                                require(it >= 0 && it <= Int.MAX_VALUE && it % 1 == 0.0)
                            }
                        val continuity = old.required("continuity").string().also { require(it.isNotEmpty()) }
                        val scalar = old.required("value").number().also { require(it.isFinite()) }
                        require(lane.metric.startsWith("calculated") || scalar >= 0)
                        ConsumptionObservation(time, active.toInt(), continuity, scalar)
                    }
            }
        }
    }

    fun checkpoint(): String =
        JsonCodec.encode(
            Obj(
                mapOf(
                    "version" to Num(1.0),
                    "highWaterUs" to (highWaterUs?.let { Str(it.toString()) } ?: Nil),
                    "lanes" to
                        Arr(
                            lanes.map { lane ->
                                Obj(
                                    linkedMapOf<String, Json>(
                                            "metric" to Str(lane.metric),
                                            "total" to Num(lane.total),
                                            "segment" to Num(lane.segment.toDouble()),
                                        )
                                        .apply {
                                            lane.previous?.let { old ->
                                                put(
                                                    "previous",
                                                    Obj(
                                                        mapOf(
                                                            "timeUs" to Str(old.timeUs.toString()),
                                                            "activeInterval" to Num(old.activeInterval.toDouble()),
                                                            "continuity" to Str(old.continuity),
                                                            "value" to Num(old.value),
                                                        )
                                                    ),
                                                )
                                            }
                                        }
                                )
                            }
                        ),
                )
            )
        )

    fun append(
        timeUs: Long,
        activeInterval: Int?,
        continuityIdentity: String?,
        chargeAh: Double?,
        energyWh: Double?,
        currentA: Double?,
        voltageV: Double?,
    ): List<BatteryConsumptionInterval> {
        if (timeUs >= 0 && highWaterUs?.let { timeUs <= it } == true) {
            lanes.forEach { lane ->
                if (lane.previous != null) lane.segment++
                lane.previous = null
            }
            return emptyList()
        }
        if (timeUs >= 0) highWaterUs = timeUs
        val current = currentA?.takeIf { it.isFinite() }
        val voltage = voltageV?.takeIf { it.isFinite() && it >= 0 }
        val values =
            listOf(
                admitCounter(chargeAh),
                admitCounter(energyWh),
                current,
                if (current != null && voltage != null) (current * voltage).takeIf { it.isFinite() } else null,
            )
        return lanes.mapIndexedNotNull { index, lane ->
            val value = values[index]
            if (
                timeUs < 0 ||
                    activeInterval == null ||
                    activeInterval < 0 ||
                    continuityIdentity.isNullOrEmpty() ||
                    value == null
            ) {
                if (lane.previous != null) lane.segment++
                lane.previous = null
                return@mapIndexedNotNull null
            }
            val old = lane.previous
            val next = ConsumptionObservation(timeUs, activeInterval, continuityIdentity, value)
            val counter = index < 2
            val dt = old?.let { timeUs - it.timeUs } ?: 0
            if (old != null && dt <= 0) {
                lane.previous = null
                lane.segment++
                return@mapIndexedNotNull null
            }
            lane.previous = next
            val same =
                old != null && dt <= DistancePolicy.maximumControllerIntervalUs && old.continuity == continuityIdentity
            val limit =
                if (index == 0) BatteryConsumptionPolicy.maximumCounterCurrentA
                else BatteryConsumptionPolicy.maximumCounterPowerW
            val bridged =
                counter &&
                    old != null &&
                    dt < BatteryConsumptionPolicy.maximumCounterEdgeUs &&
                    (value - old.value) * 3600000000.0 <= limit * dt
            val connects =
                old != null &&
                    old.activeInterval == activeInterval &&
                    (!counter || value >= old.value) &&
                    (same || bridged)
            if (!connects) {
                if (old != null) lane.segment++
                return@mapIndexedNotNull BatteryConsumptionInterval(
                    lane.metric,
                    timeUs,
                    timeUs,
                    lane.total,
                    lane.total,
                    null,
                    null,
                    counter,
                    lane.segment,
                    activeInterval,
                    true,
                )
            }
            requireNotNull(old)
            val before = lane.total
            lane.total =
                if (counter) {
                    val delta =
                        FixedPoint.subtract(FixedPoint.quantize(value, 10000), FixedPoint.quantize(old.value, 10000))
                    FixedPoint.divide(FixedPoint.add(FixedPoint.quantize(before, 10000), delta), 10000)
                } else before + positiveArea(old.value, value, dt)
            require(lane.total.isFinite())
            BatteryConsumptionInterval(
                lane.metric,
                old.timeUs,
                timeUs,
                before,
                lane.total,
                if (counter) null else old.value,
                if (counter) null else value,
                counter,
                lane.segment,
                activeInterval,
            )
        }
    }
}

/** Integrates only the positive triangle/trapezoid of a linearly interpolated original rate. */
internal fun positiveArea(first: Double, last: Double, durationUs: Long): Double {
    val rateArea =
        when {
            first <= 0 && last <= 0 -> 0.0
            first >= 0 && last >= 0 -> first / 2 + last / 2
            first > 0 -> first / 2 * (first / (first - last))
            else -> last / 2 * (last / (last - first))
        }
    return rateArea * (durationUs / 3600000000.0)
}

private fun admitCounter(value: Double?): Double? {
    if (value == null || !value.isFinite() || value < 0) return null
    return try {
        FixedPoint.divide(FixedPoint.quantize(value, 10000), 10000)
    } catch (_: ArithmeticException) {
        null
    }
}
