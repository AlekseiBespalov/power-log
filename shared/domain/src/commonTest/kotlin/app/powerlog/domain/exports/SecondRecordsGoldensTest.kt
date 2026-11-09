package app.powerlog.domain.exports

import app.powerlog.domain.analytics.J
import app.powerlog.domain.core.analysis.StorableRide
import app.powerlog.domain.core.analysis.battery.BatteryConsumptionReducer
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FitPowerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SecondRecordsGoldensTest {
    @Test
    fun theAndroidRidesSecondsAreTheStartingBuilds() {
        for (name in listOf("android-gps-distance", "android-controller-distance")) {
            val ride = StorableRide(name)
            assertEquals(dumped(ride), seconds(ride), name)
        }
    }

    @Test
    fun theIphoneRidesSecondsDifferOnlyWhereTheirPauseInstantReadingsHadNoInterval() {
        for ((name, twin) in
            listOf(
                "ios-gps-distance" to "android-gps-distance",
                "ios-controller-distance" to "android-controller-distance",
            )) {
            val ride = StorableRide(name)
            val expected = dumped(ride)
            val actual = seconds(ride)
            val twinDistance = dumped(StorableRide(twin)).getValue("distance")
            for ((column, values) in expected) {
                if (column == "distance") assertEquals(twinDistance, actual.getValue(column), "$name distance")
                else if (column != "speed" || name.contains("gps"))
                    assertEquals(values, actual.getValue(column), "$name $column")
            }
        }
    }

    private fun seconds(ride: StorableRide): Map<String, List<Any?>> {
        val end = requireNotNull(ride.ride.end).elapsedUs
        val timeline = ActiveTimeline(end, ride.events)
        val planner = FitHealthPlanner(ride.ride)
        ride.samples.forEach(planner::append)
        val records = SecondRecords(ride.ride, timeline, 1, FitPowerSource.rider, planner.result().heartRate)
        val battery = BatteryConsumptionReducer(null)
        val controller = ControllerDistanceReducer(null)
        val gps = GpsDistanceReducer(null)
        val frozen = ride.ride.options.distanceSource
        val inputs =
            (0 until ride.telemetry.rows).map { ride.telemetry.elapsedUs[it].toLong() to Input.Row(it) } +
                ride.fixes.map { it.elapsedUs to Input.Other(Fact.Location(it)) } +
                ride.samples.map { it.elapsedUs to Input.Other(Fact.Health(it)) } +
                ride.events.map { it.elapsedUs to Input.Other(Fact.Event(it)) }
        val rows = mutableListOf<SecondRow>()
        for ((_, input) in inputs.sortedBy { it.first }) {
            when (input) {
                is Input.Row -> {
                    records.append(ride.telemetry, input.row, input.row + 1, ride.sessions)
                    battery.append(ride.telemetry, input.row, input.row + 1, ride.sessions).forEach(records::battery)
                    val distance = controller.append(ride.telemetry, input.row, input.row + 1, ride.sessions)
                    if (frozen == DistanceSource.controller) distance.forEach(records::distance)
                }
                is Input.Other -> {
                    records.append(input.fact)
                    val fix = (input.fact as? Fact.Location)?.fix
                    if (fix != null) {
                        val edge = gps.append(fix)
                        if (frozen == DistanceSource.phoneGps && edge != null) records.distance(edge.interval)
                    }
                }
            }
            rows += records.take()
        }
        rows += records.finish()
        return columns(rows)
    }

    private fun columns(rows: List<SecondRow>): Map<String, List<Any?>> {
        val names =
            listOf("kind", "second", "elapsedSeconds", "interval", "lap") +
                rows.first { it.kind == SecondKind.record }.values.keys
        return names.associateWith { name ->
            rows.map { row ->
                when (name) {
                    "kind" -> row.kind.name
                    "second" -> row.second?.toDouble()
                    "elapsedSeconds" -> row.elapsedSeconds
                    "interval" -> row.interval?.toDouble()
                    "lap" -> row.lap?.toDouble()
                    else -> row.values[name]?.takeIf { !it.isNaN() }
                }
            }
        }
    }

    private fun dumped(ride: StorableRide): Map<String, List<Any?>> =
        ride.page("seconds").mapValues { (name, values) ->
            values.map { if (it == J.Null) null else if (name == "kind") it.text() else it.double() }
        }

    private sealed interface Input {
        class Row(val row: Int) : Input

        class Other(val fact: Fact) : Input
    }
}
