package app.powerlog.domain.exports

import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistancePolicy
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsAltitude
import app.powerlog.domain.core.analysis.distance.GpsTrack
import app.powerlog.domain.core.analysis.distance.validSpeed
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.timeAverage
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.time.TimeRange

public data class FitSession(
    public val distanceM: Double?,
    public val lapDistancesM: List<Double?>,
    public val averageSpeedMps: Double?,
    public val maxSpeedMps: Double?,
    public val averageHeartRateBpm: Double?,
    public val maxHeartRateBpm: Double?,
    public val averageCadenceRpm: Double?,
    public val maxCadenceRpm: Double?,
    public val averagePowerW: Double?,
    public val maxPowerW: Double?,
    public val averageMotorPowerW: Double?,
    public val maxMotorPowerW: Double?,
    public val workJ: Double?,
    public val ascentM: Double?,
    public val descentM: Double?,
)

public class FitSessionReducer(
    ride: Ride,
    private val timeline: ActiveTimeline,
    private val power: FitPowerSource,
    private val heartRate: HeartRateSource?,
) : TelemetryConsumer {
    private val range = TimeRange(0, rideEnd(ride) + 1)
    private val controller = ride.options.distanceSource == DistanceSource.controller
    private val powerStatistics = MetricReducer(MetricId.humanPowerW, range)
    private val cadence = MetricReducer(MetricId.cadenceRpm, range)
    private val motor = MetricReducer(MetricId.motorInputPowerW, range)
    private val heart = MetricReducer(MetricId.heartRateBpm, range)
    private val speed = MetricReducer(MetricId.speedMps, range)
    private val track = GpsTrack(null)
    private val altitude = GpsAltitude()
    private val total = DistanceReducer(range, null)
    private val laps = timeline.laps.map { DistanceReducer(it.range, null) }
    private var lap = 0
    private val speeds = RecentSpeeds()
    private var latestUs = Long.MIN_VALUE
    private var activity = 0
    private var started = false
    private var previousEnd = 0L
    private var previousSegment = 0

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        if (power == FitPowerSource.rider) powerStatistics.append(rows, from, to, sessions)
        cadence.append(rows, from, to, sessions)
        motor.append(rows, from, to, sessions)
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            if (power == FitPowerSource.battery) {
                val watts =
                    if (MetricId.motorInputPowerW in session.capabilities.metrics)
                        rows.value(MetricId.motorInputPowerW, row, session)
                    else null
                powerStatistics.reading(
                    elapsedUs,
                    rows.utcUs[row].toLong(),
                    watts,
                    true,
                    rows.activeInterval[row],
                    rows.run[row],
                )
            }
            rows.value(MetricId.controllerSpeedMps, row, session)?.let { speeds.put(elapsedUs, it) }
            if (elapsedUs > latestUs) latestUs = elapsedUs
        }
        speeds.dropBefore(latestUs - 2 * DistancePolicy.gpsEdgeUs)
    }

    public fun append(fact: Fact) {
        when (fact) {
            is Fact.Location -> {
                val fix = fact.fix
                val step = track.append(fix)
                altitude.append(fix, step)
                if (!controller && step.accepted)
                    speed.reading(
                        fix.elapsedUs,
                        fix.utcUs,
                        fix.speedMps.takeIf { validSpeed(it, fix.speedAccuracyMps) },
                        true,
                        fix.activeInterval ?: 0,
                        0,
                    )
            }
            is Fact.Health -> if (heartRate?.matches(fact.sample) == true) heart.append(fact)
            is Fact.Event -> Unit
        }
    }

    public fun distance(interval: DistanceInterval) {
        total.append(interval)
        while (lap < laps.size && timeline.laps[lap].range.endUs <= interval.range.startUs) lap++
        var index = lap
        while (index < laps.size && timeline.laps[index].range.startUs < interval.range.endUs) {
            laps[index].append(interval)
            index++
        }
        if (!controller) return
        val start = interval.range.startUs
        val end = interval.range.endUs
        val inside = containing(start, end)
        val startSpeed = speeds[start] ?: interval.speed0Q?.let { it / SPEED_QUANTUM }
        val endSpeed = speeds[end] ?: interval.speed1Q?.let { it / SPEED_QUANTUM }
        if (!started || interval.segment != previousSegment || start != previousEnd)
            point(start, interval.start.utcUs, startSpeed, inside)
        if (startSpeed != null && endSpeed != null && end - start > SECOND_US) {
            var at = start + SECOND_US
            while (at < end) {
                val share = (at - start).toDouble() / (end - start).toDouble()
                point(at, interval.start.utcUs + (at - start), startSpeed + (endSpeed - startSpeed) * share, inside)
                at += SECOND_US
            }
        }
        point(end, interval.end.utcUs, endSpeed, inside)
        started = true
        previousEnd = end
        previousSegment = interval.segment
    }

    public fun result(): Bounded<FitSession> {
        val powers = done(powerStatistics.result()) ?: return Bounded.Capacity
        val cadences = done(cadence.result()) ?: return Bounded.Capacity
        val motors = done(motor.result()) ?: return Bounded.Capacity
        val hearts = done(heart.result()) ?: return Bounded.Capacity
        if (speed.result() is Bounded.Capacity) return Bounded.Capacity
        val session = done(total.result(timeline.timerUs)) ?: return Bounded.Capacity
        val lapTotals = laps.mapIndexed { index, reducer -> done(reducer.result(timeline.laps[index].timerUs)) }
        if (lapTotals.any { it == null }) return Bounded.Capacity
        return Bounded.Done(
            FitSession(
                distanceM = meters(session),
                lapDistancesM = lapTotals.map { meters(requireNotNull(it)) },
                averageSpeedMps =
                    if (session.coveredUs == 0L) null else FixedPoint.divide(session.distanceQ, session.coveredUs),
                maxSpeedMps = speed.maximumValue(),
                averageHeartRateBpm = hearts.timeAverage(MetricId.heartRateBpm),
                maxHeartRateBpm = heart.maximumValue(),
                averageCadenceRpm = cadences.timeAverage(MetricId.cadenceRpm),
                maxCadenceRpm = cadence.maximumValue(),
                averagePowerW = powers.timeAverage(MetricId.humanPowerW),
                maxPowerW = powerStatistics.maximumValue(),
                averageMotorPowerW = motors.timeAverage(MetricId.motorInputPowerW)?.takeIf { it >= 0 },
                maxMotorPowerW = motor.maximumValue()?.takeIf { it >= 0 },
                workJ =
                    powers.integralQUs
                        ?.takeIf { powers.coveredUs > 0 }
                        ?.let { FixedPoint.divide(it, MetricCatalog.of(MetricId.humanPowerW).quantum * SECOND_US) },
                ascentM = altitude.ascentM(),
                descentM = altitude.descentM(),
            )
        )
    }

    private fun point(elapsedUs: Long, utcUs: Long, value: Double?, inside: Int) {
        if (value != null) speed.reading(elapsedUs, utcUs, value, true, inside, 0)
    }

    private fun containing(start: Long, end: Long): Int {
        val intervals = timeline.intervals
        while (activity < intervals.size && intervals[activity].endUs < end) activity++
        val candidate = intervals.getOrNull(activity) ?: return 0
        return if (candidate.startUs <= start && end <= candidate.endUs) activity + 1 else 0
    }

    private fun <T> done(result: Bounded<T>): T? = (result as? Bounded.Done)?.value

    private fun meters(totals: DistanceTotals): Double? =
        if (totals.coveredUs > 0 && !totals.unresolvedBoundary) totals.distanceQ / METERS_QUANTUM else null

    private companion object {
        const val SECOND_US = 1_000_000L
        const val SPEED_QUANTUM = 1e6
        const val METERS_QUANTUM = 1e6
    }
}

internal class RecentSpeeds {
    private val speeds = LinkedHashMap<Long, Double>()
    private var lastUs = Long.MIN_VALUE
    private var ordered = true

    operator fun get(elapsedUs: Long): Double? = speeds[elapsedUs]

    fun put(elapsedUs: Long, mps: Double) {
        if (elapsedUs < lastUs) ordered = false else lastUs = elapsedUs
        speeds[elapsedUs] = mps
    }

    // While keys arrive in order, insertion order is key order and the keys before the cutoff are a prefix.
    fun dropBefore(cutoffUs: Long) {
        if (!ordered) {
            speeds.keys.removeAll { it < cutoffUs }
            return
        }
        val keys = speeds.keys.iterator()
        while (keys.hasNext() && keys.next() < cutoffUs) keys.remove()
    }
}
