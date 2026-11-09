package app.powerlog.domain.core.analysis.summary

import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionTotals
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsAltitude
import app.powerlog.domain.core.analysis.distance.GpsTrack
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceTotals
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.timeAverage
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthSourceSelection
import app.powerlog.domain.core.health.OriginCoverage
import app.powerlog.domain.core.ids.RideId
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
import app.powerlog.domain.core.numeric.CheckedSum
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.FinishBoundary
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.TimeRange
import kotlinx.serialization.Serializable

public class RideSummaryReducer(private val ride: Ride, private val timeline: ActiveTimeline) : TelemetryConsumer {
    private val endUs: Long? =
        when (ride.phase) {
            RidePhase.completed -> ride.end?.elapsedUs ?: ride.checkpoint.elapsedUs
            RidePhase.interrupted -> null
            RidePhase.failed -> ride.checkpoint.elapsedUs
            RidePhase.preparing,
            RidePhase.running,
            RidePhase.paused -> throw IllegalArgumentException("a ${ride.phase} ride has no summary")
        }
    private val range = TimeRange(0, endUs?.let { it + 1 } ?: Long.MAX_VALUE)
    private val power = MetricReducer(MetricId.humanPowerW, range)
    private val cadence = MetricReducer(MetricId.cadenceRpm, range)
    private val owner = if (ride.options.useWatch) Producer.watch else Producer.phone
    private val byOrigin = ride.recordedBy == Device.androidPhone
    private val heartRate = mutableMapOf<HeartRateKey, MetricReducer>()
    private val heartRateProducers = mutableSetOf<Producer>()
    private val rawHeartRate = mutableSetOf<HeartRateKey>()
    private val energy = mutableMapOf<Pair<Producer, String?>, HealthEnergy>()
    private val energyProducers = mutableSetOf<Producer>()
    private val energyIntervals = mutableMapOf<Pair<Producer, String>, MutableList<TimeRange>>()
    private val track = GpsTrack(null)
    private val altitude = GpsAltitude()
    private val route = RoutePoints()
    private var goodFixes = 0L
    private val events = mutableListOf<RideEvent>()
    private var last: RetainedEnd? = null
    private var distance: Map<DistanceSource, DistanceTotals> = emptyMap()
    private var battery: Map<ConsumptionMetric, ConsumptionTotals> = emptyMap()
    private var energyPerDistance: Map<MetricId, EnergyPerDistanceTotals> = emptyMap()

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        power.append(rows, from, to, sessions)
        cadence.append(rows, from, to, sessions)
        for (row in from until to) retain(rows.utcUs[row].toLong(), rows.elapsedUs[row].toLong())
    }

    public fun append(fact: Fact) {
        when (fact) {
            is Fact.Location -> location(fact.fix)
            is Fact.Health -> health(fact.sample)
            is Fact.Event -> events += fact.event
        }
    }

    public fun distance(totals: Map<DistanceSource, DistanceTotals>) {
        distance = totals
    }

    public fun battery(totals: Map<ConsumptionMetric, ConsumptionTotals>) {
        battery = totals
    }

    public fun energyPerDistance(totals: Map<MetricId, EnergyPerDistanceTotals>) {
        energyPerDistance = totals
    }

    public fun result(): Bounded<RideSummary> {
        val power = (power.result() as? Bounded.Done)?.value ?: return Bounded.Capacity
        val cadence = (cadence.result() as? Bounded.Done)?.value ?: return Bounded.Capacity
        val heartRate =
            when (val selected = heartRate()) {
                null -> null
                else -> (selected.result() as? Bounded.Done)?.value ?: return Bounded.Capacity
            }
        val averageHeartRate = heartRate?.timeAverage(MetricId.heartRateBpm)
        val activeEnergy = activeEnergy()
        val frozen = distance[ride.options.distanceSource]
        return Bounded.Done(
            RideSummary(
                rideId = ride.id,
                end = end(),
                distance = distance,
                averageSpeedMps =
                    frozen?.takeIf { it.coveredUs > 0 }?.let { FixedPoint.divide(it.distanceQ, it.coveredUs) },
                battery = battery,
                averagePowerW = power.timeAverage(MetricId.humanPowerW),
                averageCadenceRpm = cadence.timeAverage(MetricId.cadenceRpm),
                averageHeartRateBpm = averageHeartRate,
                ascentM = altitude.ascentM(),
                activeEnergyKcal = activeEnergy,
                physiologyBackfilled = byOrigin && (averageHeartRate != null || activeEnergy != null),
                energyPerDistance = energyPerDistance,
                route = route.outline(goodFixes),
            )
        )
    }

    private fun end(): RideTiming =
        when (ride.phase) {
            RidePhase.completed -> {
                val end = ride.end ?: ride.checkpoint
                RideTiming(end.utcUs, end.elapsedUs, timeline.timerUs)
            }
            RidePhase.interrupted -> FinishBoundary.of(ride.startedUtcUs, last, events).copy(timerUs = timeline.timerUs)
            else -> RideTiming(ride.checkpoint.utcUs, ride.checkpoint.elapsedUs, timeline.timerUs)
        }

    private fun retain(utcUs: Long, elapsedUs: Long) {
        val old = last
        if (old == null || elapsedUs > old.elapsedUs) last = RetainedEnd(utcUs, elapsedUs)
    }

    private fun location(fix: LocationFix) {
        retain(fix.utcUs, fix.elapsedUs)
        if (!available(fix.elapsedUs)) return
        val accuracy = fix.horizontalAccuracyM
        if (accuracy != null && accuracy.isFinite() && horizontalAccuracy?.contains(accuracy) != false) goodFixes++
        val step = track.append(fix)
        altitude.append(fix, step)
        if (step.accepted) route.add(fix.latitude, fix.longitude, fix.elapsedUs, track.segment)
    }

    private fun health(sample: HealthSample) {
        retain(sample.utcUs, sample.elapsedUs)
        if (!available(sample.elapsedUs) || sample.producer !in sources) return
        val origin = sample.dataOrigin.takeIf { byOrigin }
        when (sample.metric?.let(HealthMetrics::metricId)) {
            MetricId.heartRateBpm -> heartRate(sample, origin)
            MetricId.activeEnergyKcal -> activeEnergy(sample, origin)
            else -> Unit
        }
    }

    private fun heartRate(sample: HealthSample, origin: String?) {
        val value = sample.value
        val valid = value != null && value.isFinite() && heartRateRange?.contains(value) != false
        val raw = sample.representation in rawRepresentations
        val all = HeartRateKey(sample.producer, origin, false)
        if (valid) {
            heartRateProducers += sample.producer
            if (raw) rawHeartRate += all
        }
        reducer(all).append(Fact.Health(sample))
        if (raw) reducer(all.copy(raw = true)).append(Fact.Health(sample))
    }

    private fun reducer(key: HeartRateKey): MetricReducer =
        heartRate.getOrPut(key) { MetricReducer(MetricId.heartRateBpm, range) }

    private fun heartRate(): MetricReducer? {
        val producer = preference.firstOrNull { it in heartRateProducers } ?: return null
        val origin =
            if (!byOrigin) null
            else {
                val coverage =
                    heartRate
                        .filterKeys { it.producer == producer && !it.raw }
                        .mapNotNull { (key, reducer) ->
                            val covered = (reducer.result() as? Bounded.Done)?.value?.coveredUs ?: 0L
                            key.origin?.let { OriginCoverage(it, covered) }
                        }
                HealthSourceSelection.select(coverage)?.dataOrigin
            }
        val all = HeartRateKey(producer, origin, false)
        return heartRate[if (all in rawHeartRate) all.copy(raw = true) else all]
    }

    private fun activeEnergy(sample: HealthSample, origin: String?) {
        val evidence = HealthEnergy.evidence(sample, ride.startedUtcUs) ?: return
        energyProducers += sample.producer
        energy.getOrPut(sample.producer to null) { HealthEnergy(endUs) }.append(evidence)
        if (origin != null) energy.getOrPut(sample.producer to origin) { HealthEnergy(endUs) }.append(evidence)
        if (origin != null && evidence is EnergyReading.Interval)
            energyIntervals.getOrPut(sample.producer to origin) { mutableListOf() } += evidence.range
    }

    private fun activeEnergy(): Double? {
        val producer = preference.firstOrNull { it in energyProducers } ?: return null
        val valueQ =
            if (!byOrigin) energy[producer to null]?.valueQ()
            else {
                val coverage =
                    energyIntervals
                        .filterKeys { it.first == producer }
                        .map { (key, ranges) -> OriginCoverage(key.second, union(ranges)) }
                val origin = HealthSourceSelection.select(coverage)?.dataOrigin
                energy[producer to origin]?.valueQ()
            }
        return valueQ?.let { FixedPoint.divide(it, ENERGY_QUANTUM) }
    }

    private fun union(ranges: List<TimeRange>): Long {
        val end = endUs ?: Long.MAX_VALUE
        var covered = 0L
        var reached = Long.MIN_VALUE
        for (range in ranges.sortedWith(compareBy<TimeRange> { it.startUs }.thenBy { it.endUs })) {
            val a = maxOf(range.startUs, 0L, reached)
            val b = minOf(range.endUs, end)
            if (b > a) covered += b - a
            reached = maxOf(reached, minOf(range.endUs, end))
        }
        return covered
    }

    private fun available(elapsedUs: Long): Boolean = elapsedUs >= -SECOND_US && elapsedUs <= MAX_ELAPSED_US

    private val preference = listOf(owner, Producer.phone, Producer.watch).distinct()

    private data class HeartRateKey(val producer: Producer, val origin: String?, val raw: Boolean)

    private companion object {
        val sources = setOf(Producer.phone, Producer.watch)
        val rawRepresentations = setOf(HealthRepresentation.rawQuantity, HealthRepresentation.rawSeries)
        val heartRateRange = MetricCatalog.of(MetricId.heartRateBpm).validRange
        val horizontalAccuracy = MetricCatalog.of(MetricId.horizontalAccuracyM).validRange
        const val SECOND_US = 1_000_000L
        const val MAX_ELAPSED_US = 2_678_400_000_000L
        const val ENERGY_QUANTUM = 1_000_000L
    }
}

internal sealed interface EnergyReading {
    val valueQ: Long

    data class Interval(val range: TimeRange, override val valueQ: Long) : EnergyReading

    data class Snapshot(val elapsedUs: Long, val value: Double, override val valueQ: Long) : EnergyReading

    data class Final(val elapsedUs: Long, override val valueQ: Long) : EnergyReading
}

internal class HealthEnergy(private val endUs: Long?) {
    private val intervalsQ = CheckedSum()
    private var hasInterval = false
    private var snapshot: EnergyReading.Snapshot? = null
    private var final: EnergyReading.Final? = null

    fun append(evidence: EnergyReading) {
        when (evidence) {
            is EnergyReading.Interval -> {
                val range = evidence.range
                val a = maxOf(0L, range.startUs)
                val b = minOf(endUs ?: Long.MAX_VALUE, range.endUs)
                if (b > a) {
                    intervalsQ.add(FixedPoint.share(evidence.valueQ, b - a, range.endUs - range.startUs))
                    hasInterval = true
                }
            }
            is EnergyReading.Snapshot -> {
                val kept = snapshot
                if (evidence.elapsedUs <= (endUs ?: Long.MAX_VALUE) && (kept == null || evidence.value > kept.value))
                    snapshot = evidence
            }
            is EnergyReading.Final -> {
                val kept = final
                if (kept == null || evidence.elapsedUs >= kept.elapsedUs) final = evidence
            }
        }
    }

    fun valueQ(): Long? = final?.valueQ ?: (if (hasInterval) intervalsQ.exact() else null) ?: snapshot?.valueQ

    companion object {
        fun evidence(sample: HealthSample, startedUtcUs: Long): EnergyReading? {
            val value = sample.value ?: return null
            if (!value.isFinite() || value < 0) return null
            val valueQ =
                try {
                    FixedPoint.quantize(value, ENERGY_QUANTUM)
                } catch (_: ArithmeticException) {
                    return null
                }
            return when (sample.representation) {
                HealthRepresentation.intervalRecord,
                HealthRepresentation.rawQuantity -> {
                    val start = sample.startUtcUs ?: return null
                    val end = sample.endUtcUs ?: return null
                    if (end <= start) return null
                    EnergyReading.Interval(TimeRange(start - startedUtcUs, end - startedUtcUs), valueQ)
                }
                HealthRepresentation.cumulativeWorkoutTotal -> EnergyReading.Snapshot(sample.elapsedUs, value, valueQ)
                HealthRepresentation.finalWorkoutTotal -> EnergyReading.Final(sample.elapsedUs, valueQ)
                else -> null
            }
        }

        private const val ENERGY_QUANTUM = 1_000_000L
    }
}

private class RoutePoints {
    private var latitudes = DoubleArray(INITIAL)
    private var longitudes = DoubleArray(INITIAL)
    private var elapsed = LongArray(INITIAL)
    private var segments = IntArray(INITIAL)
    private var count = 0

    fun add(latitude: Double, longitude: Double, elapsedUs: Long, segment: Int) {
        if (count == latitudes.size) {
            latitudes = latitudes.copyOf(count * 2)
            longitudes = longitudes.copyOf(count * 2)
            elapsed = elapsed.copyOf(count * 2)
            segments = segments.copyOf(count * 2)
        }
        latitudes[count] = latitude
        longitudes[count] = longitude
        elapsed[count] = elapsedUs
        segments[count] = segment
        count++
    }

    fun outline(good: Long): List<List<LatLon>> {
        if (count == 0) return emptyList()
        val stride = maxOf(1L, (good + STRIDE_POINTS - 1) / STRIDE_POINTS)
        val chosen = mutableListOf<Int>()
        for (index in 0 until count) if (index % stride == 0L && chosen.size < MAX_POINTS) chosen += index
        val lastIndex = count - 1
        if (elapsed[chosen.last()] != elapsed[lastIndex]) chosen += lastIndex
        val lines = mutableListOf<MutableList<LatLon>>()
        var segment: Int? = null
        for (index in chosen) {
            if (segments[index] != segment) lines += mutableListOf<LatLon>()
            segment = segments[index]
            lines.last() += LatLon(latitudes[index], longitudes[index])
        }
        return lines
    }

    private companion object {
        const val INITIAL = 256
        const val STRIDE_POINTS = 254L
        const val MAX_POINTS = 255
    }
}

@Serializable public data class LatLon(public val latitude: Double, public val longitude: Double)

@Serializable
public data class RideSummary(
    public val rideId: RideId,
    public val end: RideTiming,
    public val distance: Map<DistanceSource, DistanceTotals>,
    public val averageSpeedMps: Double?,
    public val battery: Map<ConsumptionMetric, ConsumptionTotals>,
    public val averagePowerW: Double?,
    public val averageCadenceRpm: Double?,
    public val averageHeartRateBpm: Double?,
    public val ascentM: Double?,
    public val activeEnergyKcal: Double?,
    public val physiologyBackfilled: Boolean,
    public val energyPerDistance: Map<MetricId, EnergyPerDistanceTotals>,
    public val route: List<List<LatLon>>,
)
