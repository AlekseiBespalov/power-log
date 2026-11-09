package app.powerlog.domain.core.analysis

import app.powerlog.domain.core.analysis.battery.BatteryConsumptionReducer
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionReducer
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.analysis.statistics.timeAverage
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.analysis.summary.RideSummaryReducer
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ReducerGoldensTest {
    private val android =
        mapOf(
            "ios-gps-distance" to "android-gps-distance",
            "ios-controller-distance" to "android-controller-distance",
            "android-gps-distance" to "android-gps-distance",
            "android-controller-distance" to "android-controller-distance",
        )

    @Test
    fun distanceTotalsPerLapAndSessionAreTheStartingBuildsAndroidOnes() {
        for (name in StorableRide.names) {
            val ride = StorableRide(name)
            val dump = StorableRide(android.getValue(name)).page("summary")
            val end = requireNotNull(ride.ride.end).elapsedUs
            val timeline = ActiveTimeline(end, ride.events)
            val intervals = intervals(ride)
            val session = totals(TimeRange(0, end + 1), intervals, timeline.timerUs)
            val laps = timeline.laps.map { totals(it.range, intervals, it.timerUs) }
            assertEquals(
                dump.getValue("distanceM").doubles(),
                listOf(session, *laps.toTypedArray()).map(::meters),
                name,
            )
            assertEquals(
                dump.getValue("averageSpeedMps").first().double(),
                session.distanceQ.toDouble() / session.coveredUs,
                name,
            )
        }
    }

    @Test
    fun consumptionLanesEndAtEachRowWithTheStartingBuildsValues() {
        for (name in StorableRide.names) {
            val ride = StorableRide(name)
            val page = ride.page("consumption")
            val reducer = BatteryConsumptionReducer(null)
            val values = ConsumptionMetric.entries.associateWith { MutableList<Double?>(ride.telemetry.rows) { null } }
            for (row in 0 until ride.telemetry.rows) {
                for (interval in reducer.append(ride.telemetry, row, row + 1, ride.sessions)) values
                    .getValue(interval.metric)[row] = interval.endValue
            }
            for (metric in ConsumptionMetric.entries) assertEquals(
                page.getValue(metric.name).doubles(),
                values.getValue(metric),
                "$name ${metric.name}",
            )
        }
    }

    @Test
    fun consumptionTotalsCoverTheRideOnce() {
        val ride = StorableRide("android-controller-distance")
        val end = requireNotNull(ride.ride.end).elapsedUs
        val intervals = BatteryConsumptionReducer(null).append(ride.telemetry, 0, ride.telemetry.rows, ride.sessions)
        val totals =
            ConsumptionMetric.entries.associateWith { metric ->
                ConsumptionReducer(metric, TimeRange(0, end + 1), null)
                    .also { reducer -> intervals.forEach(reducer::append) }
                    .result(ActiveTimeline(end, ride.events).timerUs)
            }
        val last = ride.page("consumption").getValue("controllerEnergyWh").doubles().last()
        assertEquals(last, totals.getValue(ConsumptionMetric.controllerEnergyWh).amount)
    }

    @Test
    fun fitSessionStatisticsAreTheStartingBuilds() {
        for (name in StorableRide.names) {
            val ride = StorableRide(name)
            val dump = ride.page("summary")
            val end = requireNotNull(ride.ride.end).elapsedUs
            val range = TimeRange(0, end + 1)
            fun telemetry(metric: MetricId): MetricStatistics =
                done(
                    MetricReducer(metric, range)
                        .also { it.append(ride.telemetry, 0, ride.telemetry.rows, ride.sessions) }
                        .result()
                )
            val power = telemetry(MetricId.humanPowerW)
            val cadence = telemetry(MetricId.cadenceRpm)
            val motor = telemetry(MetricId.motorInputPowerW)
            val heart = MetricReducer(MetricId.heartRateBpm, range)
            ride.samples
                .filter { it.metric?.wire in setOf("heartRateBpm", "heartRate") }
                .forEach { heart.append(Fact.Health(it)) }
            val heartRate = done(heart.result())
            assertEquals(dump.getValue("averagePowerW").first().double(), power.timeAverage(MetricId.humanPowerW), name)
            assertEquals(
                dump.getValue("maxPowerW").first().double(),
                requireNotNull(power.maximum).valueQ / 1_000.0,
                name,
            )
            assertEquals(dump.getValue("workJ").first().double(), requireNotNull(power.integralQUs) / 1e9, name)
            assertEquals(
                dump.getValue("averageCadenceRpm").first().double(),
                cadence.timeAverage(MetricId.cadenceRpm),
                name,
            )
            assertEquals(
                dump.getValue("maxCadenceRpm").first().double(),
                requireNotNull(cadence.maximum).valueQ / 10_000.0,
                name,
            )
            assertEquals(
                dump.getValue("averageMotorPowerW").first().double(),
                motor.timeAverage(MetricId.motorInputPowerW),
                name,
            )
            assertEquals(
                dump.getValue("maxMotorPowerW").first().double(),
                requireNotNull(motor.maximum).valueQ / 1_000.0,
                name,
            )
            assertEquals(
                dump.getValue("averageHeartRateBpm").first().double(),
                heartRate.timeAverage(MetricId.heartRateBpm),
                name,
            )
            assertEquals(
                dump.getValue("maxHeartRateBpm").first().double(),
                requireNotNull(heartRate.maximum).valueQ / 1_000.0,
                name,
            )
        }
    }

    @Test
    fun theRideSummaryAgreesWithTheStartingBuildsFitSession() {
        for (name in StorableRide.names) {
            val ride = StorableRide(name)
            val dump = ride.page("summary")
            val twin = StorableRide(android.getValue(name)).page("summary")
            val summary = summary(ride)
            assertEquals(dump.getValue("averagePowerW").first().double(), summary.averagePowerW, name)
            assertEquals(dump.getValue("averageCadenceRpm").first().double(), summary.averageCadenceRpm, name)
            assertEquals(dump.getValue("averageHeartRateBpm").first().double(), summary.averageHeartRateBpm, name)
            assertEquals(dump.getValue("ascentM").first().double(), summary.ascentM, name)
            assertEquals(dump.getValue("caloriesKcal").first().double(), summary.activeEnergyKcal, name)
            assertEquals(twin.getValue("averageSpeedMps").first().double(), summary.averageSpeedMps, name)
            assertEquals(name.startsWith("android"), summary.physiologyBackfilled, name)
            assertEquals(ride.ride.end?.timerUs, summary.end.timerUs, name)
        }
    }

    @Test
    fun aRideStillOpenHasNoSummary() {
        val ride = StorableRide("android-gps-distance").ride
        val timeline = ActiveTimeline(0, emptyList())
        for (phase in listOf(RidePhase.preparing, RidePhase.running, RidePhase.paused)) {
            val open = ride.copy(phase = phase)
            assertFailsWith<IllegalArgumentException>(phase.name) { RideSummaryReducer(open, timeline) }
        }
    }

    @Test
    fun theRouteOutlineSplitsAtThePause() {
        val summary = summary(StorableRide("android-gps-distance"))
        assertEquals(listOf(21, 34), summary.route.map { it.size })
    }

    private fun summary(ride: StorableRide): RideSummary {
        val end = requireNotNull(ride.ride.end).elapsedUs
        val timeline = ActiveTimeline(end, ride.events)
        val reducer = RideSummaryReducer(ride.ride, timeline)
        reducer.append(ride.telemetry, 0, ride.telemetry.rows, ride.sessions)
        ride.events.forEach { reducer.append(Fact.Event(it)) }
        ride.fixes.forEach { reducer.append(Fact.Location(it)) }
        ride.samples.forEach { reducer.append(Fact.Health(it)) }
        val range = TimeRange(0, end + 1)
        reducer.distance(
            mapOf(
                DistanceSource.controller to totals(range, controller(ride), timeline.timerUs),
                DistanceSource.phoneGps to totals(range, gps(ride), timeline.timerUs),
            )
        )
        return assertIs<Bounded.Done<RideSummary>>(reducer.result()).value
    }

    private fun intervals(ride: StorableRide): List<DistanceInterval> =
        if (ride.ride.options.distanceSource == DistanceSource.controller) controller(ride) else gps(ride)

    private fun controller(ride: StorableRide): List<DistanceInterval> =
        ControllerDistanceReducer(null).append(ride.telemetry, 0, ride.telemetry.rows, ride.sessions)

    private fun gps(ride: StorableRide): List<DistanceInterval> {
        val reducer = GpsDistanceReducer(null)
        return ride.fixes.mapNotNull { reducer.append(it)?.interval }
    }

    private fun totals(range: TimeRange, intervals: List<DistanceInterval>, timerUs: Long): DistanceTotals =
        assertIs<Bounded.Done<DistanceTotals>>(
                DistanceReducer(range, null).also { reducer -> intervals.forEach(reducer::append) }.result(timerUs)
            )
            .value

    private fun meters(totals: DistanceTotals): Double? =
        if (totals.coveredUs > 0 && !totals.unresolvedBoundary) totals.distanceQ / 1e6 else null

    private fun done(result: Bounded<MetricStatistics>): MetricStatistics =
        assertIs<Bounded.Done<MetricStatistics>>(result).value
}
