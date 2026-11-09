package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.Anchor
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.InspectInput
import app.powerlog.application.charts.InspectView
import app.powerlog.application.charts.LatestInput
import app.powerlog.application.charts.LatestView
import app.powerlog.application.charts.Original
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.StatisticsInput
import app.powerlog.application.charts.StatisticsView
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartQueriesScenarioTest {
    private val quarterSeconds = { from: Long, until: Long -> (from until until step 250_000L).toList() }

    @Test
    fun latestOfAStoredRideReadsTheLastRowReportingEachMetricAndTheSummaryTotals() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 120_000_000, readingsUs = quarterSeconds(0, 60_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val metrics = listOf(MetricId.humanPowerW, MetricId.cadenceRpm, MetricId.controllerDistanceMeters)
            val latest =
                assertIs<Outcome.Ok<LatestView>>(
                        scenario.api.charts.latest(LatestInput(ChartSource.Ride(seeded.ride.id), metrics, "latest", 0))
                    )
                    .value
            val power = latest.readings[0]
            assertEquals(seededValue(MetricId.humanPowerW, 59_750_000), power.value)
            assertEquals(59_750_000, power.elapsedUs)
            assertNull(power.freshUntilMonoUs)
            assertEquals(80.0, latest.readings[1].value)
            val distance = latest.readings[2]
            assertEquals(fullPassMeters(seeded, TimeRange(0, 120_000_001), 120_000_000), distance.value)
            assertNull(distance.freshUntilMonoUs)
        }
    }

    @Test
    fun inspectReportsTheExactNearestOriginalAndNeverCrossesAGap() {
        val scenario = Scenario()
        scenario.test {
            val readings = quarterSeconds(0, 10_000_000) + quarterSeconds(40_000_000, 50_000_000)
            val seeded = scenario.seedRide(endUs = 50_000_000, readingsUs = readings)
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            suspend fun inspect(atUs: Long, anchor: Anchor? = null): InspectView =
                assertIs<Outcome.Ok<InspectView>>(
                        scenario.api.charts.inspect(
                            InspectInput(source, listOf(MetricId.humanPowerW), "cursor", atUs, anchor, 0)
                        )
                    )
                    .value

            val near = inspect(5_100_000).values.single()
            assertEquals(original(5_000_000), near.original)
            assertFalse(near.gap)
            val gap = inspect(25_000_000).values.single()
            assertNull(gap.original)
            assertTrue(gap.gap)
            val anchored = inspect(5_250_000, Anchor(MetricId.humanPowerW, 5_250_000)).values.single()
            assertEquals(original(5_250_000), anchored.original)
        }
    }

    @Test
    fun statisticsOfARangeAreItsOriginalsAndDistanceAndARangePastTheEndIsClamped() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 50_000_000, readingsUs = quarterSeconds(0, 50_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            val metrics = listOf(MetricId.humanPowerW, MetricId.controllerDistanceMeters)
            suspend fun statistics(range: TimeRange): StatisticsView =
                assertIs<Outcome.Ok<StatisticsView>>(
                        scenario.api.charts.statistics(StatisticsInput(source, metrics, "range", range, 0, false))
                    )
                    .value

            val range = statistics(TimeRange(2_000_000, 4_000_000))
            val power = range.statistics[0]
            assertEquals(original(3_500_000), power.min)
            assertEquals(original(3_250_000), power.max)
            assertEquals(177.5, power.average)
            assertNull(power.total)
            assertNull(range.nextUs)
            val distance = range.statistics[1]
            assertEquals(fullPassMeters(seeded, TimeRange(2_000_000, 4_000_000), 2_000_000), distance.total)

            val clamped = statistics(TimeRange(40_000_000, 90_000_000)).statistics[0]
            val tail = seeded.samples.filter { it.elapsedUs >= 40_000_000 }.map { it.elapsedUs }
            assertEquals(tail.map { seededValue(MetricId.humanPowerW, it)!! }.max(), clamped.max?.value)
            val plot =
                assertIs<Outcome.Ok<app.powerlog.application.charts.PlotView>>(
                        scenario.api.charts.plot(
                            PlotInput(
                                source,
                                listOf(MetricId.humanPowerW),
                                "plot",
                                TimeRange(40_000_000, 90_000_000),
                                8,
                                0,
                            )
                        )
                    )
                    .value
            assertEquals(TimeRange(40_000_000, 50_000_000), plot.rangeUs)
        }
    }

    @Test
    fun everyQueryOfADeletedRideAnswersRideDeleted() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 10_000_000, readingsUs = quarterSeconds(0, 10_000_000))
            scenario.archive.seed(
                WriteBatch(listOf(Mutation.MarkRideDeleted(seeded.ride.id, Scenario.START_UTC_US + 60_000_000)))
            )
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            val metrics = listOf(MetricId.humanPowerW)
            val charts = scenario.api.charts
            assertEquals(FailureCode.rideDeleted, charts.latest(LatestInput(source, metrics, "a", 0)).code())
            assertEquals(
                FailureCode.rideDeleted,
                charts.inspect(InspectInput(source, metrics, "b", 1_000_000, null, 0)).code(),
            )
            assertEquals(
                FailureCode.rideDeleted,
                charts.statistics(StatisticsInput(source, metrics, "c", TimeRange(0, 1), 0, false)).code(),
            )
            assertEquals(FailureCode.rideDeleted, charts.plot(PlotInput(source, metrics, "d", null, 8, 0)).code())
            val head = charts.head.next(ChartHeadInput(source), null, "e")
            assertEquals(FailureCode.rideDeleted, assertIs<StreamAnswer.Failed>(head).failure.code)
        }
    }

    @Test
    fun aWholeRidePlotOverTilesEqualsAFullPassAndASecondPlotReadsOnlyTheCache() {
        val scenario = Scenario()
        scenario.test {
            val readings = quarterSeconds(0, 600_000_000) + quarterSeconds(700_000_000, 1_200_000_000)
            val seeded = scenario.seedRide(endUs = 1_200_000_000, readingsUs = readings)
            assertEquals(LaunchResult.Ready, scenario.launch())
            val input = PlotInput(ChartSource.Ride(seeded.ride.id), listOf(MetricId.humanPowerW), "whole", null, 64, 0)
            val plot =
                assertIs<Outcome.Ok<app.powerlog.application.charts.PlotView>>(scenario.api.charts.plot(input)).value
            val slot = requireNotNull(plot.slotUs)
            assertTrue(slot >= app.powerlog.domain.charts.PlotTiles.baseSlotUs)
            val sessions =
                mapOf(
                    1 to
                        app.powerlog.domain.core.measurement.ControllerSession(
                            seeded.ride.id,
                            1,
                            seededIdentity,
                            app.powerlog.domain.core.controller.ControllerCapabilities(seededColumns, true),
                        )
                )
            val full = app.powerlog.domain.charts.PlotReducer(MetricId.humanPowerW, plot.rangeUs, slot, 64)
            val rows = TelemetryRows.of(seeded.samples)
            full.append(rows, 0, rows.rows, sessions)
            val points =
                assertIs<app.powerlog.domain.core.measurement.Bounded.Done<List<app.powerlog.domain.charts.PlotPoint>>>(
                        full.result()
                    )
                    .value
            val series = plot.series.single()
            assertEquals(points.map { it.first.elapsedUs }, series.firstUs)
            assertEquals(points.map { it.max.elapsedUs }, series.maxUs)
            assertEquals(points.map { it.segment }, series.segment)

            scenario.archive.telemetryReads = 0
            val again =
                assertIs<Outcome.Ok<app.powerlog.application.charts.PlotView>>(scenario.api.charts.plot(input)).value
            assertEquals(plot, again)
            assertEquals(0, scenario.archive.telemetryReads)
        }
    }

    @Test
    fun derivedRangeReadoutsEqualAFullPassAndResumeFromTheTilesAnEarlierPassPut() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 1_200_000_000, readingsUs = quarterSeconds(0, 1_200_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            val metrics = listOf(MetricId.controllerDistanceMeters, MetricId.controllerEnergyWh)
            suspend fun statistics(range: TimeRange): StatisticsView =
                assertIs<Outcome.Ok<StatisticsView>>(
                        scenario.api.charts.statistics(StatisticsInput(source, metrics, "ab", range, 0, false))
                    )
                    .value

            val early = TimeRange(600_000_000, 700_000_000)
            val first = statistics(early)
            assertEquals(fullPassMeters(seeded, early, 100_000_000), first.statistics[0].total)
            assertEquals(fullPassWh(seeded, early), requireNotNull(first.statistics[1].total), 1e-9)

            scenario.archive.telemetryRanges.clear()
            val late = TimeRange(900_000_000, 1_000_000_000)
            val second = statistics(late)
            assertEquals(fullPassMeters(seeded, late, 100_000_000), second.statistics[0].total)
            assertEquals(fullPassWh(seeded, late), requireNotNull(second.statistics[1].total), 1e-9)
            val read = scenario.archive.telemetryRanges.minOf { it.startUs }
            assertTrue(read >= 600_000_000, "the second pass read from $read")
        }
    }

    private fun fullPassWh(seeded: SeededRide, range: TimeRange): Double {
        val sessions =
            mapOf(
                1 to
                    app.powerlog.domain.core.measurement.ControllerSession(
                        seeded.ride.id,
                        1,
                        seededIdentity,
                        app.powerlog.domain.core.controller.ControllerCapabilities(seededColumns, true),
                    )
            )
        val rows = TelemetryRows.of(seeded.samples)
        val lane =
            app.powerlog.domain.core.analysis.battery.ConsumptionReducer(
                app.powerlog.domain.core.analysis.battery.ConsumptionMetric.controllerEnergyWh,
                range,
                null,
            )
        app.powerlog.domain.core.analysis.battery
            .BatteryConsumptionReducer(null)
            .append(rows, 0, rows.rows, sessions)
            .forEach(lane::append)
        return lane.result(range.endUs - range.startUs).amount
    }

    @Test
    fun aWholeRideWhPerKmPlotContinuesItsSeriesAndAThirdSmoothingDropsTheLeastRecentlyUsed() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 1_200_000_000, readingsUs = quarterSeconds(0, 1_200_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            suspend fun plot(smoothingUs: Long) =
                assertIs<Outcome.Ok<app.powerlog.application.charts.PlotView>>(
                        scenario.api.charts.plot(
                            PlotInput(source, listOf(MetricId.riderWhPerKm), "wh", null, 64, smoothingUs)
                        )
                    )
                    .value

            val first = plot(5_000_000)
            assertTrue(first.series.single().firstUs.isNotEmpty())
            scenario.archive.telemetryRanges.clear()
            val second = plot(5_000_000)
            assertEquals(first, second)
            assertTrue(
                scenario.archive.telemetryRanges.all { it.startUs > 600_000_000 },
                "${scenario.archive.telemetryRanges}",
            )

            plot(30_000_000)
            plot(60_000_000)
            scenario.archive.telemetryRanges.clear()
            plot(5_000_000)
            assertTrue(scenario.archive.telemetryRanges.any { it.startUs == 0L })
        }
    }

    @Test
    fun rangeStatisticsMergeTheCachedTilesInsideTheRangeAndEqualAFullPass() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedRide(endUs = 1_200_000_000, readingsUs = quarterSeconds(0, 1_200_000_000))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val source = ChartSource.Ride(seeded.ride.id)
            scenario.api.charts.plot(PlotInput(source, listOf(MetricId.humanPowerW), "whole", null, 64, 0))
            scenario.archive.telemetryRanges.clear()
            val range = TimeRange(100_000_000, 900_000_000)
            val view =
                assertIs<Outcome.Ok<StatisticsView>>(
                        scenario.api.charts.statistics(
                            StatisticsInput(source, listOf(MetricId.humanPowerW), "range", range, 0, false)
                        )
                    )
                    .value
            val inside = seeded.samples.filter { it.elapsedUs >= range.startUs && it.elapsedUs < range.endUs }
            val values = inside.map { requireNotNull(seededValue(MetricId.humanPowerW, it.elapsedUs)) }
            val statistics = view.statistics.single()
            assertEquals(values.average(), requireNotNull(statistics.average), 1e-9)
            assertEquals(values.max(), statistics.max?.value)
            assertEquals(values.min(), statistics.min?.value)
            val read = scenario.archive.telemetryRanges.sumOf { it.endUs - it.startUs }
            assertTrue(read < 100_000_000, "read ${scenario.archive.telemetryRanges}")
        }
    }

    private fun original(elapsedUs: Long): Original =
        Original(
            requireNotNull(seededValue(MetricId.humanPowerW, elapsedUs)),
            Scenario.START_UTC_US + elapsedUs,
            elapsedUs,
        )

    private fun fullPassMeters(seeded: SeededRide, range: TimeRange, timerUs: Long): Double {
        val sessions =
            mapOf(
                1 to
                    app.powerlog.domain.core.measurement.ControllerSession(
                        seeded.ride.id,
                        1,
                        seededIdentity,
                        app.powerlog.domain.core.controller.ControllerCapabilities(seededColumns, true),
                    )
            )
        val rows = TelemetryRows.of(seeded.samples)
        val totals = DistanceReducer(range, null)
        ControllerDistanceReducer(null).append(rows, 0, rows.rows, sessions).forEach(totals::append)
        val distanceQ = assertIs<Bounded.Done<DistanceTotals>>(totals.result(timerUs)).value.distanceQ
        return distanceQ / MetricCatalog.of(MetricId.controllerDistanceMeters).quantum.toDouble()
    }

    @Test
    fun riderWhPerKmCountsEverySecondOfReadingsASecondApart() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(
                    endUs = 120_000_000,
                    readingsUs = (0..120).map { it * 1_000_000L },
                    columns = setOf(MetricId.humanPowerW, MetricId.speedRaw),
                ) { metric, elapsedUs ->
                    when (metric) {
                        MetricId.humanPowerW -> 180.0
                        MetricId.speedRaw -> if (elapsedUs <= 60_000_000) 18.0 else 36.0
                        else -> null
                    }
                }
            assertEquals(LaunchResult.Ready, scenario.launch())
            val metrics = listOf(MetricId.riderWhPerKm, MetricId.controllerDistanceMeters)
            val input =
                StatisticsInput(ChartSource.Ride(seeded.ride.id), metrics, "k", TimeRange(0, 120_000_000), 0, true)
            val view = assertIs<Outcome.Ok<StatisticsView>>(scenario.api.charts.statistics(input)).value
            assertEquals(897.5, view.statistics[1].total)
            assertEquals(6_000 / 897.5, view.statistics[0].ratio!!, 1e-9)
        }
    }
}
