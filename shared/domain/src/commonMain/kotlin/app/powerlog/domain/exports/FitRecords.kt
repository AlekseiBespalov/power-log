package app.powerlog.domain.exports

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistancePolicy
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsTrack
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.summary.EnergyReading
import app.powerlog.domain.core.analysis.summary.HealthEnergy
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthSourceSelection
import app.powerlog.domain.core.health.OriginCoverage
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.TimeRange
import kotlin.math.floor
import kotlinx.serialization.Serializable

public class FitRecordPlan(
    ride: Ride,
    timeline: ActiveTimeline,
    power: FitPowerSource,
    heartRate: HeartRateSource?,
) : TelemetryConsumer {
    private val clock = FitClock(ride.startedUtcUs)
    private val witnesses = WitnessSeconds()
    private val merge = WitnessMerge(ride, timeline, clock, power, heartRate, witnesses)

    public val needed: Boolean = clock.second(rideEnd(ride)) - clock.second(0) + 1 > maxRecords

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        if (needed) merge.append(rows, from, to, sessions)
    }

    public fun append(fact: Fact) {
        if (needed) merge.append(fact)
    }

    public fun distance(interval: DistanceInterval) {
        if (needed) merge.distance(interval)
    }

    public fun result(): RecordInterval {
        if (!needed) return RecordInterval.Planned(1)
        merge.finish()
        return witnesses.plan(clock.second(0), maxRecords, maxIntervalS)
    }

    public companion object {
        public const val maxRecords: Int = 99_998
        public const val maxIntervalS: Int = 3_600
    }
}

public sealed interface RecordInterval {
    public data class Planned(public val intervalS: Int) : RecordInterval

    public data class Refused(public val code: FailureCode) : RecordInterval
}

@Serializable
public enum class SecondKind {
    record,
    start,
    stop,
    lap,
}

public data class SecondRow(
    public val kind: SecondKind,
    public val second: Long?,
    public val elapsedSeconds: Double?,
    public val interval: Int?,
    public val lap: Int?,
    public val values: Map<String, Double>,
)

public class SecondRecords(
    ride: Ride,
    timeline: ActiveTimeline,
    intervalS: Int,
    power: FitPowerSource,
    heartRate: HeartRateSource?,
) : TelemetryConsumer {
    private val clock = FitClock(ride.startedUtcUs)
    private val emitter = RecordEmitter(clock.second(0), intervalS)
    private val merge = WitnessMerge(ride, timeline, clock, power, heartRate, emitter)

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        merge.append(rows, from, to, sessions)
    }

    public fun append(fact: Fact) {
        merge.append(fact)
    }

    public fun distance(interval: DistanceInterval) {
        merge.distance(interval)
    }

    public fun battery(interval: ConsumptionInterval) {
        merge.battery(interval)
    }

    public fun take(): List<SecondRow> {
        merge.advance()
        return emitter.take()
    }

    public fun finish(): List<SecondRow> {
        merge.finish()
        emitter.finish()
        return emitter.take()
    }
}

@Serializable
public enum class HeartRateKind {
    sample,
    latest,
}

public data class HeartRateSource(
    public val producer: Producer,
    public val kind: HeartRateKind,
    public val dataOrigin: String?,
) {
    public fun matches(sample: HealthSample): Boolean =
        sample.producer == producer &&
            sample.heartRate() &&
            sample.heartRateKind() == kind &&
            sample.dataOrigin.ifEmpty { null } == dataOrigin
}

public data class FitHealth(public val heartRate: HeartRateSource?, public val caloriesKcal: Double?)

public class FitHealthPlanner(private val ride: Ride) {
    private class Origin(range: TimeRange) {
        var sample = false
        var latest = false
        val samples = MetricReducer(MetricId.heartRateBpm, range)
        val latests = MetricReducer(MetricId.heartRateBpm, range)
    }

    private val endUs = rideEnd(ride)
    private val window = TimeRange(0, endUs + 1)
    private val owner = if (ride.options.useWatch) Producer.watch else Producer.phone
    private val heartRates = mutableMapOf<Producer, MutableMap<String, Origin>>()
    private val readings = mutableMapOf<Pair<Producer, String>, Origin>()
    private val energy = mutableMapOf<Producer, HealthEnergy>()
    private val energyByOrigin = mutableMapOf<Pair<Producer, String>, HealthEnergy>()
    private val energyIntervals = mutableMapOf<Producer, MutableMap<String, MutableList<TimeRange>>>()
    private val energySources = mutableSetOf<Producer>()

    public fun append(sample: HealthSample) {
        if (sample.producer != Producer.phone && sample.producer != Producer.watch) return
        if (sample.heartRate() && collected(sample.elapsedUs)) heartRate(sample)
        if (sample.metric?.let(HealthMetrics::metricId) == MetricId.activeEnergyKcal) activeEnergy(sample)
    }

    public fun result(): FitHealth = FitHealth(heartRateSource(), calories())

    private fun heartRate(sample: HealthSample) {
        val kind = sample.heartRateKind() ?: return
        val key = sample.producer to sample.dataOrigin
        val value = sample.value
        if (value != null && value.isFinite() && heartRateRange?.contains(value) != false) {
            val origin = readings.getOrPut(key) { Origin(window) }
            heartRates.getOrPut(sample.producer) { linkedMapOf() }[sample.dataOrigin] = origin
            if (kind == HeartRateKind.sample) origin.sample = true else origin.latest = true
        }
        val origin = readings.getOrPut(key) { Origin(window) }
        val active = Fact.Health(sample.copy(activeInterval = 1))
        if (kind == HeartRateKind.sample) origin.samples.append(active) else origin.latests.append(active)
    }

    private fun activeEnergy(sample: HealthSample) {
        val reading = HealthEnergy.evidence(sample, ride.startedUtcUs) ?: return
        energy.getOrPut(sample.producer) { HealthEnergy(endUs) }.append(reading)
        energyByOrigin.getOrPut(sample.producer to sample.dataOrigin) { HealthEnergy(endUs) }.append(reading)
        val range = (reading as? EnergyReading.Interval)?.range
        if (!collected(range?.endUs ?: sample.elapsedUs)) return
        energySources += sample.producer
        if (range == null) return
        energyIntervals.getOrPut(sample.producer) { linkedMapOf() }.getOrPut(sample.dataOrigin) { mutableListOf() } +=
            range
    }

    private fun collected(elapsedUs: Long): Boolean = elapsedUs >= -SECOND_US && elapsedUs <= MAX_RIDE_US

    private fun heartRateSource(): HeartRateSource? {
        val producer = preference().firstOrNull { heartRates[it]?.isNotEmpty() == true } ?: return null
        val origins = heartRates.getValue(producer)
        val coverage = origins.map { (origin, readings) ->
            val reducer = if (readings.sample) readings.samples else readings.latests
            OriginCoverage(origin, (reducer.result() as? Bounded.Done)?.value?.coveredUs ?: 0L)
        }
        val origin = HealthSourceSelection.select(coverage)?.dataOrigin ?: ""
        val kind = if (origins[origin]?.sample == true) HeartRateKind.sample else HeartRateKind.latest
        return HeartRateSource(producer, kind, origin.ifEmpty { null })
    }

    private fun calories(): Double? {
        val producer = preference().firstOrNull { it in energySources } ?: return null
        val coverage =
            energyIntervals[producer].orEmpty().map { (origin, ranges) -> OriginCoverage(origin, union(ranges)) }
        val origin = HealthSourceSelection.select(coverage)?.dataOrigin?.ifEmpty { null }
        val selected = if (origin == null) energy[producer] else energyByOrigin[producer to origin]
        return selected?.valueQ()?.let { it / ENERGY_QUANTUM }
    }

    private fun union(ranges: List<TimeRange>): Long {
        var covered = 0L
        var reached = 0L
        for (range in ranges.sortedWith(compareBy<TimeRange> { it.startUs }.thenBy { it.endUs })) {
            val a = maxOf(range.startUs, 0L, reached)
            val b = minOf(range.endUs, endUs)
            if (b > a) covered += b - a
            reached = maxOf(reached, minOf(range.endUs, endUs))
        }
        return covered
    }

    private fun preference(): List<Producer> = listOf(owner, Producer.phone, Producer.watch).distinct()

    private companion object {
        val heartRateRange = MetricCatalog.of(MetricId.heartRateBpm).validRange
        const val ENERGY_QUANTUM = 1_000_000.0
    }
}

internal class FitClock(startedUtcUs: Long) {
    private val fraction = startedUtcUs.floorDiv(1_000L).mod(1_000L) / 1000.0

    fun second(elapsedUs: Long): Long = nearest(fraction + elapsedUs / 1e6)

    private fun nearest(x: Double): Long {
        val r = floor(x)
        return (if (x - r >= 0.5) r + 1 else r).toLong()
    }
}

internal class LifecycleItem(
    val kind: SecondKind,
    val elapsedUs: Long,
    val interval: Int?,
    val lap: Int?,
    val role: Int = TRANSITION,
) {
    fun row(): SecondRow = SecondRow(kind, null, elapsedUs / 1e6, interval, lap, emptyMap())
}

internal class Witness(
    val stream: Int,
    val elapsedUs: Long,
    val second: Long,
    val interval: Int,
    val run: Int,
    val values: DoubleArray,
) {
    var witness = false
}

internal interface WitnessConsumer {
    fun witness(item: Witness)

    fun tie(tie: WitnessTie)

    fun lifecycle(item: LifecycleItem)
}

internal class WitnessMerge(
    ride: Ride,
    private val timeline: ActiveTimeline,
    private val clock: FitClock,
    private val power: FitPowerSource,
    private val heartRate: HeartRateSource?,
    private val consumer: WitnessConsumer,
) {
    private val endUs = rideEnd(ride)
    private val controller = ride.options.distanceSource == DistanceSource.controller
    private val energyLane =
        if (ride.options.batteryMethod == BatteryMethod.controller) ConsumptionMetric.controllerEnergyWh
        else ConsumptionMetric.calculatedEnergyWh
    private val chargeLane =
        if (ride.options.batteryMethod == BatteryMethod.controller) ConsumptionMetric.controllerChargeAh
        else ConsumptionMetric.calculatedChargeAh
    private val queues = Array(STREAM_COUNT) { ArrayDeque<Witness>() }
    private val track = GpsTrack(null)
    private val consumption = mutableMapOf<Long, DoubleArray>()
    private val speeds = RecentSpeeds()
    private val events = mutableListOf<RideEvent>()
    private val groups = groups()
    private var nextGroup = 0
    private var latestUs = Long.MIN_VALUE
    private val distanceTotal = DistanceReducer(TimeRange(0, MAX_RIDE_US + 1), null)
    private var distanceStarted = false
    private var previousEnd = 0L
    private var previousSegment = 0
    private var activity = 0

    fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            val values = DoubleArray(TELEMETRY_VALUES)
            values[POWER] =
                value(
                    rows,
                    row,
                    session,
                    if (power == FitPowerSource.battery) MetricId.motorInputPowerW else MetricId.humanPowerW,
                )
            values[CADENCE] = value(rows, row, session, MetricId.cadenceRpm)
            values[ASSIST] = value(rows, row, session, MetricId.assistLevel)
            values[MOTOR] = value(rows, row, session, MetricId.motorInputPowerW)
            for ((index, metric) in DEVELOPER.withIndex()) values[CHANNELS + index] =
                if (metric == null) Double.NaN else value(rows, row, session, metric)
            rows.value(MetricId.controllerSpeedMps, row, session)?.let { speeds.put(elapsedUs, it) }
            push(
                Witness(TELEMETRY, elapsedUs, clock.second(elapsedUs), rows.activeInterval[row], rows.run[row], values)
            )
            seen(elapsedUs)
        }
        advance()
    }

    fun append(fact: Fact) {
        when (fact) {
            is Fact.Location -> location(fact.fix)
            is Fact.Health -> {
                val sample = fact.sample
                if (heartRate?.matches(sample) == true) {
                    val value = sample.value ?: Double.NaN
                    val interval = sample.activeInterval ?: 0
                    val item =
                        Witness(
                            HEART_RATE,
                            sample.elapsedUs,
                            clock.second(sample.elapsedUs),
                            interval,
                            0,
                            doubleArrayOf(value),
                        )
                    item.witness = interval != 0 && heartRateRange?.contains(value) == true
                    push(item)
                }
                seen(sample.elapsedUs)
            }
            is Fact.Event -> {
                events += fact.event
                seen(fact.event.elapsedUs)
            }
        }
        advance()
    }

    fun distance(interval: DistanceInterval) {
        val start = interval.range.startUs
        val end = interval.range.endUs
        val activity = containing(start, end)
        val startSpeed = speed(start, interval.speed0Q)
        val endSpeed = speed(end, interval.speed1Q)
        if (!distanceStarted || interval.segment != previousSegment || start != previousEnd)
            point(start, meters(distanceTotal.result(0)), activity, startSpeed)
        val totals = (distanceTotal.result(0) as? Bounded.Done)?.value
        if (controller && end - start > SECOND_US && totals != null) {
            var at = start + SECOND_US
            while (at < end) {
                val reached = DistanceReducer(TimeRange(0, at), totals)
                reached.append(interval)
                val share = (at - start).toDouble() / (end - start).toDouble()
                point(at, meters(reached.result(0)), activity, startSpeed + (endSpeed - startSpeed) * share)
                at += SECOND_US
            }
        }
        distanceTotal.append(interval)
        point(end, meters(distanceTotal.result(0)), activity, endSpeed)
        distanceStarted = true
        previousEnd = end
        previousSegment = interval.segment
        advance()
    }

    fun battery(interval: ConsumptionInterval) {
        val lane =
            when (interval.metric) {
                energyLane -> 0
                chargeLane -> 1
                else -> return
            }
        consumption.getOrPut(interval.range.endUs) { doubleArrayOf(Double.NaN, Double.NaN) }[lane] = interval.endValue
    }

    fun advance() {
        if (latestUs != Long.MIN_VALUE) run(latestUs - DistancePolicy.gpsEdgeUs)
        speeds.dropBefore(latestUs - 2 * DistancePolicy.gpsEdgeUs)
    }

    fun finish() {
        run(Long.MAX_VALUE)
    }

    private fun run(watermarkUs: Long) {
        while (true) {
            val stream = earliest()
            val head = stream?.let { queues[it].first() }
            val group = groups.getOrNull(nextGroup)
            if (head == null && group == null) return
            if (group != null && (head == null || group.elapsedUs <= head.elapsedUs)) {
                if (group.elapsedUs >= watermarkUs) return
                group(group)
                nextGroup++
                continue
            }
            if (head == null || head.elapsedUs >= watermarkUs) return
            queues[requireNotNull(stream)].removeFirst()
            prepare(head)
            if (head.witness) consumer.witness(head)
        }
    }

    private fun earliest(): Int? {
        var best: Int? = null
        for (index in queues.indices) {
            val head = queues[index].firstOrNull() ?: continue
            if (best == null || head.elapsedUs < queues[best].first().elapsedUs) best = index
        }
        return best
    }

    private fun group(group: LifecycleGroup) {
        val ties = linkedMapOf<Int, WitnessTie>()
        for (queue in queues) {
            while (queue.firstOrNull()?.elapsedUs == group.elapsedUs) {
                val item = queue.removeFirst()
                prepare(item)
                if (!item.witness) continue
                val interval = timeline.intervals.getOrNull(item.interval - 1)
                if (interval == null || interval.endUs == group.elapsedUs && interval.startUs < group.elapsedUs)
                    consumer.witness(item)
                else ties.getOrPut(item.interval) { WitnessTie(item.interval, item.second) }.add(item)
            }
        }
        fun inside(tie: WitnessTie) = timeline.intervals[tie.interval - 1].endUs == group.elapsedUs
        for (item in group.ordered(events)) {
            val tie = if (item.kind == SecondKind.stop) ties[item.interval] else null
            if (tie != null && inside(tie)) {
                consumer.tie(tie)
                ties.remove(tie.interval)
            }
            consumer.lifecycle(item)
        }
        for (tie in ties.values) if (inside(tie)) consumer.tie(tie)
        for (tie in ties.values) if (!inside(tie)) consumer.tie(tie)
    }

    private fun prepare(item: Witness) {
        if (item.stream != TELEMETRY) return
        consumption.remove(item.elapsedUs)?.let {
            item.values[CHANNELS + CONSUMED_WH] = it[0]
            item.values[CHANNELS + CONSUMED_AH] = it[1]
        }
        val values = item.values
        var usable =
            inRange(values[POWER], POWER_MAX) || inRange(values[CADENCE], CADENCE_MAX) || validAssist(values[ASSIST])
        for (index in DEVELOPER.indices) if (!usable && FLOAT32[index]) usable = float32(values[CHANNELS + index])
        item.witness = item.interval != 0 && usable
    }

    private fun location(fix: LocationFix) {
        val step = track.append(fix)
        val altitude =
            if (valid(fix.mslM, altitudeRange) && valid(fix.mslVerticalAccuracyM, verticalRange)) fix.mslM else null
        val speed =
            if (controller) Double.NaN else fix.speedMps.takeIf { speedValid(it, fix.speedAccuracyMps) } ?: Double.NaN
        val item =
            Witness(
                GPS,
                fix.elapsedUs,
                clock.second(fix.elapsedUs),
                fix.activeInterval ?: 0,
                fix.run,
                doubleArrayOf(fix.latitude, fix.longitude, altitude ?: Double.NaN, speed),
            )
        item.witness = step.accepted
        push(item)
        seen(fix.elapsedUs)
    }

    private fun point(elapsedUs: Long, meters: Double, activity: Int, speed: Double) {
        val item =
            Witness(
                DISTANCE,
                elapsedUs,
                clock.second(elapsedUs),
                activity,
                0,
                doubleArrayOf(meters, if (controller) speed else Double.NaN),
            )
        item.witness = activity != 0 && encodable(meters)
        push(item)
    }

    private fun containing(start: Long, end: Long): Int {
        val intervals = timeline.intervals
        while (activity < intervals.size && intervals[activity].endUs < end) activity++
        val candidate = intervals.getOrNull(activity) ?: return 0
        return if (candidate.startUs <= start && end <= candidate.endUs) activity + 1 else 0
    }

    private fun speed(elapsedUs: Long, speedQ: Long?): Double =
        speeds[elapsedUs] ?: speedQ?.let { it / SPEED_QUANTUM } ?: Double.NaN

    private fun push(item: Witness) {
        queues[RANK.getValue(item.stream)].addLast(item)
    }

    private fun seen(elapsedUs: Long) {
        if (elapsedUs > latestUs) latestUs = elapsedUs
    }

    private fun groups(): List<LifecycleGroup> {
        val intervals = timeline.intervals
        val activeThroughEnd = timeline.intervalAt(endUs) != null
        val transitions = mutableListOf<LifecycleItem>()
        for ((index, interval) in intervals.withIndex()) {
            if (interval.startUs > 0) transitions += LifecycleItem(SecondKind.start, interval.startUs, index + 1, null)
            if (interval.endUs < endUs || !activeThroughEnd)
                transitions += LifecycleItem(SecondKind.stop, interval.endUs, index + 1, null)
        }
        for (lap in timeline.laps.dropLast(1)) transitions +=
            LifecycleItem(SecondKind.lap, lap.range.endUs, null, lap.index)
        val last = intervals.size
        val closedByEvent = transitions.any { it.kind == SecondKind.stop && it.interval == last }
        val items =
            listOf(LifecycleItem(SecondKind.start, 0, 1, null, HEAD)) +
                transitions.sortedBy { it.elapsedUs } +
                LifecycleItem(SecondKind.stop, endUs, if (closedByEvent) 0 else last, null, FINAL) +
                LifecycleItem(SecondKind.lap, endUs, null, timeline.laps.size - 1, FINAL)
        val groups = mutableListOf<MutableList<LifecycleItem>>()
        for (item in items) {
            val group = groups.lastOrNull()
            if (group != null && group.first().elapsedUs == item.elapsedUs) group += item
            else groups += mutableListOf(item)
        }
        return groups.map { LifecycleGroup(it.first().elapsedUs, it) }
    }

    private fun value(rows: TelemetryRows, row: Int, session: ControllerSession, metric: MetricId): Double =
        if (metric in session.capabilities.metrics) rows.value(metric, row, session) ?: Double.NaN else Double.NaN

    private fun valid(value: Double?, range: ClosedFloatingPointRange<Double>?): Boolean =
        value != null && value.isFinite() && range?.contains(value) != false

    private fun speedValid(speed: Double?, accuracy: Double?): Boolean =
        valid(speed, speedRange) && (accuracy == null || accuracy.isFinite() && accuracy >= 0)

    private fun meters(totals: Bounded<DistanceTotals>): Double =
        (totals as? Bounded.Done)?.value?.let { it.distanceQ / 1e6 } ?: Double.NaN

    private companion object {
        val altitudeRange = MetricCatalog.of(MetricId.altitudeMeters).validRange
        val verticalRange = MetricCatalog.of(MetricId.verticalAccuracyM).validRange
        val speedRange = MetricCatalog.of(MetricId.speedMps).validRange
        val heartRateRange = MetricCatalog.of(MetricId.heartRateBpm).validRange
        val RANK = mapOf(DISTANCE to 0, TELEMETRY to 1, GPS to 2, HEART_RATE to 3)
        const val STREAM_COUNT = 4
        const val SPEED_QUANTUM = 1e6
    }
}

internal class LifecycleGroup(val elapsedUs: Long, private val items: List<LifecycleItem>) {
    fun ordered(events: List<RideEvent>): List<LifecycleItem> {
        val order = mutableMapOf<Pair<RideEventKind, Long>, Int>()
        events.forEachIndexed { index, event -> order.getOrPut(event.kind to event.elapsedUs) { index } }
        val transitions = items.filter { it.role == TRANSITION }.sortedBy { order[it.event() to it.elapsedUs] ?: -1 }
        return items.filter { it.role == HEAD } + transitions + items.filter { it.role == FINAL }
    }

    private fun LifecycleItem.event(): RideEventKind =
        when (kind) {
            SecondKind.start -> RideEventKind.resume
            SecondKind.stop -> RideEventKind.pause
            else -> RideEventKind.lap
        }
}

internal open class FieldSums {
    protected val power = Mean()
    protected val cadence = Mean()
    protected val motor = Mean()
    protected val sums = DoubleArray(DEVELOPER.size)
    protected val counts = DoubleArray(DEVELOPER.size)
    protected val lasts = DoubleArray(DEVELOPER.size) { Double.NaN }
    protected var assist = Double.NaN
    protected var heartRate = Double.NaN
    protected var latitude = Double.NaN
    protected var longitude = Double.NaN
    protected var altitude = Double.NaN
    protected var speed = Double.NaN
    protected var distance = Double.NaN

    open fun reset() {
        power.reset()
        cadence.reset()
        motor.reset()
        sums.fill(0.0)
        counts.fill(0.0)
        lasts.fill(Double.NaN)
        assist = Double.NaN
        heartRate = Double.NaN
        latitude = Double.NaN
        longitude = Double.NaN
        altitude = Double.NaN
        speed = Double.NaN
        distance = Double.NaN
    }

    open fun add(item: Witness) {
        val values = item.values
        when (item.stream) {
            TELEMETRY -> {
                power.add(values[POWER], 0.0, POWER_MAX)
                cadence.add(values[CADENCE], 0.0, CADENCE_MAX)
                motor.add(values[MOTOR], -Double.MAX_VALUE, Double.MAX_VALUE)
                if (validAssist(values[ASSIST])) assist = values[ASSIST]
                for (index in DEVELOPER.indices) {
                    val value = values[CHANNELS + index]
                    val limit = if (FLOAT32[index]) FLOAT32_OVERFLOW else Double.POSITIVE_INFINITY
                    if (value > -limit && value < limit) {
                        counts[index] += 1.0
                        if (LAST[index]) lasts[index] = value else sums[index] += value
                    }
                }
            }
            GPS -> {
                latitude = values[0]
                longitude = values[1]
                if (!values[2].isNaN()) altitude = values[2]
                if (!values[3].isNaN()) speed = values[3]
            }
            HEART_RATE -> heartRate = values[0]
            else -> {
                distance = values[0]
                if (!values[1].isNaN()) speed = values[1]
            }
        }
    }

    open fun merge(other: FieldSums) {
        power.merge(other.power)
        cadence.merge(other.cadence)
        motor.merge(other.motor)
        for (index in DEVELOPER.indices) {
            if (other.counts[index] == 0.0) continue
            sums[index] += other.sums[index]
            counts[index] += other.counts[index]
            lasts[index] = other.lasts[index]
        }
        if (!other.assist.isNaN()) assist = other.assist
        if (!other.heartRate.isNaN()) heartRate = other.heartRate
        if (!other.latitude.isNaN()) {
            latitude = other.latitude
            longitude = other.longitude
        }
        if (!other.altitude.isNaN()) altitude = other.altitude
        if (!other.speed.isNaN()) speed = other.speed
        if (!other.distance.isNaN()) distance = other.distance
    }

    fun values(): Map<String, Double> = buildMap {
        put("power", power.value)
        put("cadence", cadence.value)
        put("motor", motor.value)
        put("assist", assist)
        put("heartRate", heartRate)
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude", altitude)
        put("speed", speed)
        put("distance", distance)
        for ((index, name) in DEVELOPER_NAMES.withIndex()) {
            val mean = if (counts[index] > 0) sums[index] / counts[index] else Double.NaN
            put(name, if (LAST[index]) lasts[index] else mean)
        }
    }
}

internal class Mean {
    var sum = 0.0
    var count = 0.0

    fun reset() {
        sum = 0.0
        count = 0.0
    }

    fun add(value: Double, low: Double, high: Double) {
        if (value >= low && value <= high) {
            sum += value
            count += 1.0
        }
    }

    fun merge(other: Mean) {
        sum += other.sum
        count += other.count
    }

    val value: Double
        get() = if (count > 0) sum / count else Double.NaN
}

internal class TieSegment(val kind: Int, val stream: Int) : FieldSums()

internal class WitnessTie(val interval: Int, val second: Long) {
    val segments = mutableListOf<TieSegment>()
    val firstRun = IntArray(2)
    val lastRun = IntArray(2)
    private val changed = BooleanArray(2)

    fun add(item: Witness) {
        val stream = item.stream
        var kind = if (segments.isEmpty()) START_SEGMENT else NO_SEGMENT
        if (stream == TELEMETRY || stream == GPS) {
            val run = item.run
            if (firstRun[stream] == 0) {
                firstRun[stream] = run
                kind = FIRST_SEGMENT
            } else if (!changed[stream] && run != lastRun[stream]) {
                changed[stream] = true
                kind = CHANGE_SEGMENT
            }
            lastRun[stream] = run
        }
        if (kind != NO_SEGMENT) segments += TieSegment(kind, stream)
        segments.last().add(item)
    }
}

internal class BinBreaks {
    private var started = false
    private var interval = 0
    private val runs = IntArray(2)

    fun next(stream: Int, interval: Int, run: Int): Boolean {
        var broken = !started || interval != this.interval
        if (stream == TELEMETRY || stream == GPS) {
            val previous = runs[stream]
            if (previous != 0 && previous != run) broken = true
            runs[stream] = run
        }
        started = true
        this.interval = interval
        return broken
    }

    fun tie(tie: WitnessTie): BooleanArray {
        val breaks = BooleanArray(tie.segments.size)
        for ((index, segment) in tie.segments.withIndex()) {
            var broken = segment.kind == CHANGE_SEGMENT
            if (segment.kind == FIRST_SEGMENT) {
                val previous = runs[segment.stream]
                broken = previous != 0 && previous != tie.firstRun[segment.stream]
            }
            breaks[index] = broken || index == 0 && (!started || tie.interval != interval)
        }
        started = true
        interval = tie.interval
        for (stream in intArrayOf(TELEMETRY, GPS)) if (tie.firstRun[stream] != 0) runs[stream] = tie.lastRun[stream]
        return breaks
    }
}

internal class RecordBins(private val origin: Long, private val interval: Int) {
    private var bin: Long? = null
    private var second: Long? = null

    fun next(second: Long, broken: Boolean): Int {
        val bin = (second - origin).floorDiv(interval.toLong())
        if (!broken && bin == this.bin) return SAME_BIN
        this.bin = bin
        if (second == this.second) return MERGED_BIN
        this.second = second
        return NEW_RECORD
    }

    fun span(through: Long): Long {
        val bin = (through - origin).floorDiv(interval.toLong())
        val records = bin - (this.bin ?: return 0)
        if (records > 0) {
            this.bin = bin
            this.second = origin + bin * interval
        }
        return records
    }
}

internal class RecordEmitter(origin: Long, interval: Int) : WitnessConsumer {
    private val breaks = BinBreaks()
    private val bins = RecordBins(origin, interval)
    private val fields = FieldSums()
    private val queue = mutableListOf<LifecycleItem>()
    private val rows = mutableListOf<SecondRow>()
    private var pending = false
    private var second = 0L
    private var lastStream = -1
    private var lastInterval = 0
    private var lastRun = 0
    private var lastSecond: Long? = null

    override fun witness(item: Witness) {
        if (
            item.stream != lastStream ||
                item.interval != lastInterval ||
                item.run != lastRun ||
                item.second != lastSecond
        ) {
            if (bins.next(item.second, breaks.next(item.stream, item.interval, item.run)) == NEW_RECORD)
                begin(item.second)
            lastStream = item.stream
            lastInterval = item.interval
            lastRun = item.run
            lastSecond = item.second
        }
        fields.add(item)
    }

    override fun tie(tie: WitnessTie) {
        lastStream = -1
        val broken = breaks.tie(tie)
        for ((index, segment) in tie.segments.withIndex()) {
            if (bins.next(tie.second, broken[index]) == NEW_RECORD) begin(tie.second)
            fields.merge(segment)
        }
    }

    override fun lifecycle(item: LifecycleItem) {
        if (pending) queue += item else rows += item.row()
    }

    fun finish() {
        flush()
    }

    fun take(): List<SecondRow> {
        val taken = rows.toList()
        rows.clear()
        return taken
    }

    private fun begin(second: Long) {
        flush()
        fields.reset()
        this.second = second
        pending = true
    }

    private fun flush() {
        if (!pending) return
        rows += SecondRow(SecondKind.record, second, null, null, null, fields.values())
        pending = false
        for (item in queue) rows += item.row()
        queue.clear()
    }
}

internal class WitnessSeconds : WitnessConsumer {
    private var seconds = LongArray(1_024)
    private var flags = BooleanArray(1_024)
    private var count = 0
    private val breaks = BinBreaks()

    override fun witness(item: Witness) {
        add(item.second, breaks.next(item.stream, item.interval, item.run))
    }

    override fun tie(tie: WitnessTie) {
        add(tie.second, breaks.tie(tie).any { it })
    }

    override fun lifecycle(item: LifecycleItem) = Unit

    fun plan(origin: Long, limit: Int, maxInterval: Int): RecordInterval {
        if (count == 0) return RecordInterval.Refused(FailureCode.exportNoRecords)
        var broken = 0
        for (index in 0 until count) if (flags[index]) broken++
        if (broken > limit) return RecordInterval.Refused(FailureCode.exportLimit)
        val starts = LongArray(count)
        val ends = LongArray(count)
        val segmentFlags = BooleanArray(count)
        var segments = 0
        for (index in 0 until count) {
            val second = seconds[index]
            if (segments > 0 && !flags[index] && second == ends[segments - 1] + 1) {
                ends[segments - 1] = second
                continue
            }
            starts[segments] = second
            ends[segments] = second
            segmentFlags[segments] = flags[index]
            segments++
        }
        var interval = (count + limit - 1) / limit
        while (interval <= maxInterval) {
            val bins = RecordBins(origin, interval)
            var records = 0L
            for (segment in 0 until segments) {
                if (bins.next(starts[segment], segmentFlags[segment]) == NEW_RECORD) records++
                if (ends[segment] > starts[segment]) records += bins.span(ends[segment])
            }
            if (records <= limit) return RecordInterval.Planned(interval)
            interval++
        }
        return RecordInterval.Refused(FailureCode.exportLimit)
    }

    private fun add(second: Long, broken: Boolean) {
        val last = count - 1
        if (last >= 0 && seconds[last] == second) {
            if (broken) flags[last] = true
            return
        }
        if (count == seconds.size) {
            seconds = seconds.copyOf(count * 2)
            flags = flags.copyOf(count * 2)
        }
        seconds[count] = second
        flags[count] = broken
        count++
    }
}

private fun HealthSample.heartRate(): Boolean = metric?.let(HealthMetrics::metricId) == MetricId.heartRateBpm

private fun HealthSample.heartRateKind(): HeartRateKind? =
    when (representation) {
        HealthRepresentation.rawSeries -> HeartRateKind.sample
        HealthRepresentation.rawQuantity -> if ((sampleCount ?: 1) > 1) null else HeartRateKind.sample
        HealthRepresentation.builderMostRecent -> HeartRateKind.latest
        else -> null
    }

internal fun rideEnd(ride: Ride): Long = (ride.end ?: ride.checkpoint).elapsedUs

private fun inRange(value: Double, max: Double): Boolean = value >= 0 && value <= max

private fun validAssist(value: Double): Boolean = inRange(value, ASSIST_MAX) && value == floor(value)

private fun float32(value: Double): Boolean = value > -FLOAT32_OVERFLOW && value < FLOAT32_OVERFLOW

private fun encodable(meters: Double): Boolean {
    val scaled = meters * 100
    if (!scaled.isFinite()) return false
    val rounded = if (scaled < 0) -nearest(-scaled) else nearest(scaled)
    return rounded >= 0 && rounded <= UINT32_MAX
}

private fun nearest(x: Double): Double {
    val r = floor(x)
    return if (x - r >= 0.5) r + 1 else r
}

private const val TELEMETRY = 0
private const val GPS = 1
private const val HEART_RATE = 2
private const val DISTANCE = 3
private const val POWER = 0
private const val CADENCE = 1
private const val ASSIST = 2
private const val MOTOR = 3
private const val CHANNELS = 4
private const val CONSUMED_WH = 8
private const val CONSUMED_AH = 9
private const val TELEMETRY_VALUES = 14
private const val POWER_MAX = 32_766.0
private const val CADENCE_MAX = 254.0
private const val ASSIST_MAX = 254.0
private const val UINT32_MAX = 4_294_967_294.0
private const val SAME_BIN = 0
private const val MERGED_BIN = 1
private const val NEW_RECORD = 2
private const val START_SEGMENT = 0
private const val FIRST_SEGMENT = 1
private const val CHANGE_SEGMENT = 2
private const val NO_SEGMENT = -1
private const val HEAD = 0
private const val TRANSITION = 1
private const val FINAL = 2
private const val SECOND_US = 1_000_000L
private const val MAX_RIDE_US = 2_678_400_000_000L

// The largest float32 is 2^128 - 2^104; a double rounds to infinity in float32 from halfway to 2^128.
private val FLOAT32_OVERFLOW = 3.4028235677973366e38

private val DEVELOPER: List<MetricId?> =
    listOf(
        MetricId.motorInputPowerW,
        MetricId.batteryVoltageV,
        MetricId.batteryCurrentA,
        MetricId.motorCurrentA,
        MetricId.motorRpm,
        MetricId.motorTempC,
        MetricId.controllerTempC,
        MetricId.pedalTorqueNm,
        null,
        null,
    )
private val DEVELOPER_NAMES =
    listOf(
        "motorInputPowerW",
        "batteryVoltageV",
        "batteryCurrentA",
        "motorCurrentA",
        "motorRpm",
        "motorTempC",
        "controllerTempC",
        "pedalTorqueNm",
        "consumedWh",
        "consumedAh",
    )
private val FLOAT32 = booleanArrayOf(false, true, true, true, true, false, false, true, true, true)
private val LAST = booleanArrayOf(false, false, false, false, false, false, false, false, true, true)
