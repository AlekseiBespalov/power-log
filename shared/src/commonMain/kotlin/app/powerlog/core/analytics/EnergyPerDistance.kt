package app.powerlog.core.analytics

import app.powerlog.core.inspection.InspectionPlotPoint
import app.powerlog.core.inspection.InspectionPlotReducer
import app.powerlog.core.protocol.*

object EnergyPerDistancePolicy {
    val metrics = listOf("batteryWhPerKm", "riderWhPerKm")

    fun validSmoothing(seconds: Int) = seconds == 0 || seconds == 5 || seconds == 30 || seconds == 60

    // Include the preceding telemetry edge and held GPS speed before the smoothing window.
    fun contextSeconds(seconds: Int) = maxOf(18, seconds + 13)

    // Inputs earlier than the end by more than this fold the same way for every such end: only the end selection treats
    // an input before the end differently, and only within one controller interval of it.
    const val checkpointMarginUs = DistancePolicy.maximumControllerIntervalUs

    fun isMetric(metric: String) = metric in metrics
}

class EnergyPerDistanceCheckpoint internal constructor(internal val state: EnergyPerDistanceQuery, val throughUs: Long)

/**
 * Joins energy with the selected distance on the same supported intervals. Adapters stream original energy edges, rider
 * readings and accepted distance edges in end-time order, with bounded pages. GPS chords are allocated uniformly within
 * their accepted edge; controller distance retains its endpoint speeds. Controller counter increments are never split.
 */
class EnergyPerDistanceQuery
private constructor(
    val startUs: Long,
    val endUs: Long,
    buckets: Int?,
    alignedSlotUs: Long?,
    private val timerUs: Long,
    private val controllerSpeed: Boolean,
    private val smoothingSeconds: Int,
    private val includeTotals: Boolean,
    origin: EnergyPerDistanceQuery?,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        startUs: Long,
        endUs: Long,
        buckets: Int,
        timerUs: Long,
        controllerSpeed: Boolean,
        smoothingSeconds: Int,
        includeTotals: Boolean = true,
    ) : this(startUs, endUs, buckets, null, timerUs, controllerSpeed, smoothingSeconds, includeTotals, null)

    @Throws(IllegalArgumentException::class)
    constructor(
        startUs: Long,
        endUs: Long,
        buckets: Int,
        alignedSlotUs: Long?,
        timerUs: Long,
        controllerSpeed: Boolean,
        smoothingSeconds: Int,
        includeTotals: Boolean,
    ) : this(startUs, endUs, buckets, alignedSlotUs, timerUs, controllerSpeed, smoothingSeconds, includeTotals, null)

    @Throws(IllegalArgumentException::class)
    constructor(
        startUs: Long,
        endUs: Long,
        timerUs: Long,
        controllerSpeed: Boolean,
        smoothingSeconds: Int,
        includeTotals: Boolean = true,
    ) : this(startUs, endUs, null, null, timerUs, controllerSpeed, smoothingSeconds, includeTotals, null)

    @Throws(IllegalArgumentException::class)
    constructor(
        checkpoint: EnergyPerDistanceCheckpoint,
        startUs: Long,
        endUs: Long,
        timerUs: Long,
        controllerSpeed: Boolean,
        smoothingSeconds: Int,
        includeTotals: Boolean = true,
    ) : this(startUs, endUs, null, null, timerUs, controllerSpeed, smoothingSeconds, includeTotals, checkpoint.state)

    private data class Distance(
        val start: Long,
        val end: Long,
        val meters: Double,
        val speed0: Double?,
        val speed1: Double?,
    ) {
        fun amount(a: Long, b: Long): Double {
            val duration = (end - start).toDouble()
            if (speed0 == null || speed1 == null) return meters * (b - a) / duration
            val u = speed0 + (speed1 - speed0) * (a - start) / duration
            val v = speed0 + (speed1 - speed0) * (b - start) / duration
            return (u + v) * (b - a) / 2_000_000.0
        }
    }

    private data class Energy(
        val start: Long,
        val end: Long,
        val wh: Double,
        val rate0: Double?,
        val rate1: Double?,
        val indivisible: Boolean,
        val timestamp: String,
    ) {
        fun amount(a: Long, b: Long): Double {
            if (a == start && b == end) return wh
            val u = requireNotNull(rate0)
            val v = requireNotNull(rate1)
            val duration = (end - start).toDouble()
            return positiveArea(
                u + (v - u) * (a - start) / duration,
                u + (v - u) * (b - start) / duration,
                b - a,
            )
        }
    }

    private data class Speed(val time: Long, val mps: Double, val active: Int)

    private data class Reading(val time: Long, val value: Double)

    private data class Rider(val time: Long, val watts: Double, val active: Int, val continuity: String)

    private class Selection(private val target: Long, origin: Selection? = null) {
        var point: Original? = origin?.point
            private set

        private var distance: Long = origin?.distance ?: Long.MAX_VALUE

        fun append(time: Long, value: Original?) {
            val delta = kotlin.math.abs(time - target)
            if (
                delta <= DistancePolicy.maximumControllerIntervalUs &&
                    (delta < distance || (delta == distance && value == null))
            ) {
                distance = delta
                point = value
            }
        }
    }

    private inner class Lane(val metric: String, val plot: InspectionPlotReducer?, origin: Lane?) {
        val pending: ArrayDeque<Energy> = ArrayDeque(origin?.pending.orEmpty())
        val smoothing: ArrayDeque<Reading> = ArrayDeque(origin?.smoothing.orEmpty())
        var smoothingArea: Double = origin?.smoothingArea ?: 0.0
        var active: Int? = origin?.active
        var continuity: String? = origin?.continuity
        var lastTimestamp: String = origin?.lastTimestamp ?: ""
        var energy: Double = origin?.energy ?: 0.0
        var meters: Double = origin?.meters ?: 0.0
        var coverage: Long = origin?.coverage ?: 0L
        var lastInputUs: Long = origin?.lastInputUs ?: -1L
        var latest: Original? = origin?.latest
        val left: Selection = Selection(startUs, origin?.left)
        val right = Selection(endUs)
        var minimum: Original? = origin?.minimum
        var maximum: Original? = origin?.maximum
        var count: Long = origin?.count ?: 0L
        var unresolved: Boolean = origin?.unresolved ?: false
    }

    init {
        require(EnergyPerDistancePolicy.validSmoothing(smoothingSeconds))
        require(startUs >= 0 && endUs >= startUs && timerUs >= 0)
        if (origin != null) {
            require(
                origin.startUs == startUs &&
                    origin.controllerSpeed == controllerSpeed &&
                    origin.smoothingSeconds == smoothingSeconds &&
                    origin.includeTotals == includeTotals
            ) {
                "The checkpoint belongs to a query with other parameters"
            }
            require(endUs - origin.highWater > EnergyPerDistancePolicy.checkpointMarginUs) {
                "Energy/distance checkpoints must precede the query end by more than the checkpoint margin"
            }
        }
    }

    private val distance: ArrayDeque<Distance> = ArrayDeque(origin?.distance.orEmpty())
    private val lanes: List<Lane> =
        EnergyPerDistancePolicy.metrics.mapIndexed { index, metric ->
            val plot = buckets?.let {
                InspectionPlotReducer(metric, AnalysisRange(startUs, endUs), it, alignedSlotUs = alignedSlotUs)
            }
            Lane(metric, plot, origin?.lanes?.get(index))
        }
    private var distanceThrough: Long = origin?.distanceThrough ?: -1L
    private var highWater: Long = origin?.highWater ?: -1L
    private var rider: Rider? = origin?.rider
    private var gps: Speed? = origin?.gps
    private var finished = false

    internal var readings: ((lane: Int, reading: AnalysisItem) -> Unit)? = null

    private fun advance(time: Long) {
        check(!finished)
        require(time >= highWater) { "Energy/distance inputs must be ordered by end time" }
        highWater = time
        drain(false)
        while (distance.firstOrNull()?.end?.let { it < time - 20_000_000 } == true) distance.removeFirst()
    }

    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        app.powerlog.core.inspection.InspectionCapacityException::class,
    )
    fun appendDistance(startUs: Long, endUs: Long, meters: Double, speed0: Double?, speed1: Double?) {
        advance(endUs)
        require(startUs >= 0 && endUs > startUs && endUs - startUs <= DistancePolicy.maximumGPSIntervalUs)
        require(meters.isFinite() && meters >= 0 && (speed0 == null) == (speed1 == null))
        require(speed0?.let { it.isFinite() && it >= 0 } != false)
        require(speed1?.let { it.isFinite() && it >= 0 } != false)
        require(distance.lastOrNull()?.end?.let { startUs >= it } != false)
        distance.addLast(Distance(startUs, endUs, meters, speed0, speed1))
        distanceThrough = endUs
        drain(false)
    }

    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        app.powerlog.core.inspection.InspectionCapacityException::class,
    )
    fun appendBattery(
        startUs: Long,
        endUs: Long,
        wattHours: Double,
        rate0: Double?,
        rate1: Double?,
        indivisible: Boolean,
        timestamp: String,
    ) {
        advance(endUs)
        if (endUs == startUs) return
        require(
            startUs >= 0 &&
                endUs > startUs &&
                endUs - startUs <=
                    if (indivisible) BatteryConsumptionPolicy.maximumCounterEdgeUs
                    else DistancePolicy.maximumControllerIntervalUs
        )
        require(wattHours.isFinite() && wattHours >= 0)
        require(indivisible || rate0 != null && rate1 != null)
        require(rate0?.isFinite() != false && rate1?.isFinite() != false)
        enqueue(lanes[0], Energy(startUs, endUs, wattHours, rate0, rate1, indivisible, timestamp))
    }

    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        app.powerlog.core.inspection.InspectionCapacityException::class,
    )
    fun appendSpeed(timeUs: Long, mps: Double?, accuracy: Double?, activeInterval: Int?) {
        advance(timeUs)
        gps =
            if (
                activeInterval != null &&
                    Metrics.policy("speedMps")
                        .valid(
                            mps?.let { Scalar.Real64(it) },
                            accuracy?.let { Scalar.Real64(it) },
                        )
            )
                Speed(timeUs, requireNotNull(mps), activeInterval)
            else null
    }

    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        app.powerlog.core.inspection.InspectionCapacityException::class,
    )
    fun appendTelemetry(
        timeUs: Long,
        watts: Double?,
        batteryWatts: Double?,
        speedMps: Double?,
        activeInterval: Int?,
        continuity: String?,
        timestamp: String,
    ) {
        advance(timeUs)
        val speed =
            if (controllerSpeed)
                speedMps?.takeIf {
                    Metrics.policy("controllerSpeedMps").valid(Scalar.Real64(it))
                }
            else
                gps?.takeIf {
                        it.active == activeInterval && timeUs - it.time in 0..DistancePolicy.maximumGPSIntervalUs
                    }
                    ?.mps
        for ((index, power) in listOf(batteryWatts, watts).withIndex()) {
            val metric = if (index == 0) "motorInputPowerW" else "humanPowerW"
            val valid =
                activeInterval != null &&
                    !continuity.isNullOrEmpty() &&
                    speed != null &&
                    speed > 0 &&
                    power != null &&
                    power >= 0 &&
                    Metrics.policy(metric).valid(Scalar.Real64(power))
            val value = if (valid) power!! / (speed!! * 3.6) else null
            plot(index, timeUs, value?.takeIf { it.isFinite() }, activeInterval, continuity, timestamp)
        }
        if (!includeTotals) return
        val old = rider
        val valid = watts != null && Metrics.policy("humanPowerW").valid(Scalar.Real64(watts))
        rider =
            if (valid && activeInterval != null && !continuity.isNullOrEmpty())
                Rider(timeUs, requireNotNull(watts), activeInterval, continuity)
            else null
        val current = rider ?: return
        if (
            old == null ||
                current.time <= old.time ||
                current.time - old.time > DistancePolicy.maximumControllerIntervalUs ||
                current.active != old.active ||
                current.continuity != old.continuity
        )
            return
        enqueue(
            lanes[1],
            Energy(
                old.time,
                timeUs,
                positiveArea(old.watts, current.watts, timeUs - old.time),
                old.watts,
                current.watts,
                false,
                timestamp,
            ),
        )
    }

    private fun enqueue(lane: Lane, edge: Energy) {
        require(edge.start >= lane.lastInputUs) { "Overlapping energy edges" }
        lane.lastInputUs = edge.end
        lane.pending.addLast(edge)
        require(lane.pending.size <= 1024) { "Energy input exceeds its bounded window" }
        drain(false)
    }

    private fun matchedMeters(start: Long, end: Long): Double? {
        var through = end
        var meters = 0.0
        // Queries normally touch the newest distance edge. Walk backward so high-rate, long rides stay linear.
        for (index in distance.size - 1 downTo 0) {
            val edge = distance[index]
            if (edge.start >= through) continue
            if (edge.end < through) return null
            val next = maxOf(start, edge.start)
            meters += edge.amount(next, through)
            through = next
            if (through == start) return meters
        }
        return null
    }

    private fun drain(final: Boolean) {
        for (lane in lanes) {
            while (true) {
                val edge = lane.pending.firstOrNull() ?: break
                if (!final && edge.end > distanceThrough && edge.end >= highWater - 10_000_000) break
                lane.pending.removeFirst()
                val meters = matchedMeters(edge.start, edge.end)
                if (meters != null) summarize(lane, edge)
            }
        }
    }

    private fun plot(index: Int, time: Long, raw: Double?, active: Int?, continuity: String?, timestamp: String) {
        val lane = lanes[index]
        val previous = lane.smoothing.lastOrNull()
        val interrupted =
            lane.active != active ||
                lane.continuity != continuity ||
                previous?.let { time - it.time !in 1..DistancePolicy.maximumControllerIntervalUs } == true
        if (raw == null || interrupted) {
            lane.smoothing.clear()
            lane.smoothingArea = 0.0
        }
        lane.active = active
        lane.continuity = continuity
        var value = raw
        if (raw != null && smoothingSeconds > 0) {
            lane.smoothing.lastOrNull()?.let { lane.smoothingArea += (it.value + raw) * (time - it.time) / 2 }
            lane.smoothing.addLast(Reading(time, raw))
            val start = time - smoothingSeconds * 1_000_000L
            // Maintain the trapezoidal area incrementally rather than re-summing a minute at each frame.
            while (lane.smoothing.size > 2 && lane.smoothing[1].time <= start) {
                val a = lane.smoothing.removeFirst()
                val b = lane.smoothing.first()
                lane.smoothingArea -= (a.value + b.value) * (b.time - a.time) / 2
            }
            require(lane.smoothing.size <= 1024) { "Smoothing input exceeds its bounded window" }
            val first = lane.smoothing.first()
            var area = lane.smoothingArea
            if (first.time < start && lane.smoothing.size > 1) {
                val second = lane.smoothing[1]
                val boundary =
                    first.value +
                        (second.value - first.value) * (start - first.time).toDouble() / (second.time - first.time)
                area -= (first.value + boundary) * (start - first.time) / 2
            }
            val covered = time - maxOf(start, first.time)
            if (covered > 0) value = maxOf(0.0, area / covered)
        }
        val id = "${lane.metric}:$time"
        val point = value?.let { Original(id, id, timestamp, time / 1e6, Scalar.Real64(it)) }
        val sink = readings
        if (lane.plot != null || sink != null) {
            val reading =
                AnalysisItem(
                    time,
                    time / 1e6,
                    id,
                    id,
                    continuity ?: "missing",
                    time,
                    "telemetry",
                    "derived",
                    timestamp,
                    value?.let { mapOf(lane.metric to Scalar.Real64(it)) } ?: emptyMap(),
                    continuity = Continuity(connectionEpoch = continuity, interruptionIndex = active ?: -1),
                )
            lane.plot?.append(reading)
            sink?.invoke(index, reading)
        }
        if (time <= endUs) {
            lane.latest = point
            lane.lastTimestamp = timestamp
        }
        // Missing/zero-speed originals are selection barriers, not a reason to reuse a nearby moving value.
        lane.left.append(time, point)
        lane.right.append(time, point)
        if (point != null) {
            if (time in startUs..endUs) {
                lane.count++
                if (lane.minimum == null || value!! < lane.minimum!!.value.real()) lane.minimum = point
                if (lane.maximum == null || value!! > lane.maximum!!.value.real()) lane.maximum = point
            }
        }
    }

    private fun summarize(lane: Lane, edge: Energy) {
        val a = maxOf(startUs, edge.start)
        val b = minOf(endUs, edge.end)
        if (b <= a) return
        if (edge.indivisible && (a != edge.start || b != edge.end)) {
            lane.unresolved = true
            return
        }
        val meters = matchedMeters(a, b) ?: return
        lane.energy += edge.amount(a, b)
        lane.meters += meters
        lane.coverage += b - a
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun checkpoint(): EnergyPerDistanceCheckpoint {
        check(!finished) { "A finished energy/distance query has drained its pending edges" }
        check(lanes.all { it.plot == null }) { "Only series-free energy/distance queries can be resumed" }
        val state =
            EnergyPerDistanceQuery(
                startUs,
                endUs,
                null,
                null,
                timerUs,
                controllerSpeed,
                smoothingSeconds,
                includeTotals,
                this,
            )
        return EnergyPerDistanceCheckpoint(state, highWater)
    }

    /** Shape matches MonitorSource; ratio is total matched Wh/km, never a mean of instantaneous readings. */
    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        app.powerlog.core.inspection.InspectionCapacityException::class,
    )
    fun resultJson(): String = resultJson(lanes.mapNotNull { lane -> lane.plot?.let { lane.metric to it.result() } })

    internal fun resultJson(series: List<Pair<String, List<InspectionPlotPoint>>>): String {
        if (!finished) {
            drain(true)
            finished = true
        }
        fun point(value: Original?): Json =
            value?.let {
                Obj(
                    mapOf(
                        "elapsedSeconds" to Num(it.elapsedSeconds),
                        "timestamp" to Str(it.timestamp),
                        "value" to Num(it.value.real()),
                        "observationId" to Str(it.observationId),
                        "derived" to Bool(true),
                    )
                )
            } ?: Nil
        fun at(lane: Lane): Json =
            lane.latest
                ?.takeIf { endUs - FixedPoint.timeUs(it.elapsedSeconds) <= DistancePolicy.maximumControllerIntervalUs }
                .let(::point)
        return JsonCodec.encode(
            Obj(
                mapOf(
                    "points" to Obj(lanes.associate { it.metric to at(it) }),
                    "averages" to
                        Obj(
                            lanes.associate { lane ->
                                lane.metric to
                                    point(
                                        if (lane.meters > 0 && lane.coverage > 0 && lane.lastTimestamp.isNotEmpty())
                                            Original(
                                                "${lane.metric}:average",
                                                "${lane.metric}:average",
                                                lane.lastTimestamp,
                                                endUs / 1e6,
                                                Scalar.Real64(lane.energy * 1000 / lane.meters),
                                            )
                                        else null
                                    )
                            }
                        ),
                    "endpoints" to
                        Obj(
                            mapOf(
                                "start" to Obj(lanes.associate { it.metric to point(it.left.point) }),
                                "end" to Obj(lanes.associate { it.metric to point(it.right.point) }),
                            )
                        ),
                    "series" to
                        Obj(
                            series.associate { (metric, points) ->
                                var segment: Int? = null
                                metric to
                                    Arr(
                                        points.map {
                                            val p = point(it.point.original).obj()
                                            val result =
                                                Obj(p.fields + ("startsSegment" to Bool(segment != it.segment)))
                                            segment = it.segment
                                            result
                                        }
                                    )
                            }
                        ),
                    "statistics" to
                        Obj(
                            lanes.associate { lane ->
                                lane.metric to
                                    Obj(
                                        buildMap {
                                            put("count", Num(lane.count.toDouble()))
                                            put("min", point(lane.minimum))
                                            put("max", point(lane.maximum))
                                            put("coveredSeconds", Num(lane.coverage / 1e6))
                                            put("partial", Bool(lane.unresolved || timerUs - lane.coverage > 1000))
                                            put("unresolvedBoundary", Bool(lane.unresolved))
                                            if (lane.meters > 0 && lane.coverage > 0)
                                                put("ratio", Num(lane.energy * 1000 / lane.meters))
                                        }
                                    )
                            }
                        ),
                )
            )
        )
    }
}
