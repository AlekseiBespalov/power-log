package app.powerlog.domain.exports

import app.powerlog.domain.analytics.J
import app.powerlog.domain.core.analysis.StorableRide
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FitPowerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FitSessionGoldensTest {
    @Test
    fun theAndroidRidesSessionValuesAreTheStartingBuilds() {
        for (name in listOf("android-gps-distance", "android-controller-distance")) {
            val ride = StorableRide(name)
            val dump = ride.page("summary")
            val session = session(ride)
            fun first(column: String): Double? =
                dump.getValue(column).first().let { if (it == J.Null) null else it.double() }
            assertEquals(
                dump.getValue("distanceM").map { if (it == J.Null) null else it.double() },
                listOf(session.distanceM) + session.lapDistancesM,
                name,
            )
            assertEquals(first("averageSpeedMps"), session.averageSpeedMps, "$name averageSpeedMps")
            assertEquals(first("maxSpeedMps"), session.maxSpeedMps, "$name maxSpeedMps")
            assertEquals(first("averageHeartRateBpm"), session.averageHeartRateBpm, "$name averageHeartRateBpm")
            assertEquals(first("maxHeartRateBpm"), session.maxHeartRateBpm, "$name maxHeartRateBpm")
            assertEquals(first("averageCadenceRpm"), session.averageCadenceRpm, "$name averageCadenceRpm")
            assertEquals(first("maxCadenceRpm"), session.maxCadenceRpm, "$name maxCadenceRpm")
            assertEquals(first("averagePowerW"), session.averagePowerW, "$name averagePowerW")
            assertEquals(first("maxPowerW"), session.maxPowerW, "$name maxPowerW")
            assertEquals(first("averageMotorPowerW"), session.averageMotorPowerW, "$name averageMotorPowerW")
            assertEquals(first("maxMotorPowerW"), session.maxMotorPowerW, "$name maxMotorPowerW")
            assertEquals(first("workJ"), session.workJ, "$name workJ")
            assertEquals(first("ascentM"), session.ascentM, "$name ascentM")
            assertEquals(first("descentM"), session.descentM, "$name descentM")
        }
    }

    private fun session(ride: StorableRide): FitSession {
        val end = requireNotNull(ride.ride.end).elapsedUs
        val timeline = ActiveTimeline(end, ride.events)
        val planner = FitHealthPlanner(ride.ride)
        ride.samples.forEach(planner::append)
        val reducer = FitSessionReducer(ride.ride, timeline, FitPowerSource.rider, planner.result().heartRate)
        val controller = ControllerDistanceReducer(null)
        val gps = GpsDistanceReducer(null)
        val frozen = ride.ride.options.distanceSource
        val inputs =
            (0 until ride.telemetry.rows).map { ride.telemetry.elapsedUs[it].toLong() to (it as Any) } +
                ride.fixes.map { it.elapsedUs to (Fact.Location(it) as Any) } +
                ride.samples.map { it.elapsedUs to (Fact.Health(it) as Any) } +
                ride.events.map { it.elapsedUs to (Fact.Event(it) as Any) }
        for ((_, input) in inputs.sortedBy { it.first }) {
            when (input) {
                is Int -> {
                    reducer.append(ride.telemetry, input, input + 1, ride.sessions)
                    val distance = controller.append(ride.telemetry, input, input + 1, ride.sessions)
                    if (frozen == DistanceSource.controller) distance.forEach(reducer::distance)
                }
                is Fact -> {
                    reducer.append(input)
                    if (input is Fact.Location) {
                        val edge = gps.append(input.fix)
                        if (frozen == DistanceSource.phoneGps && edge != null) reducer.distance(edge.interval)
                    }
                }
            }
        }
        return assertIs<Bounded.Done<FitSession>>(reducer.result()).value
    }
}
