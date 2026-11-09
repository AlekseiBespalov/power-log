package app.powerlog.domain.charts

import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlotReducerTest {
    private val ride = RideId("00000000-0000-4000-8000-000000000001")
    private val sessions =
        mapOf(
            1 to
                ControllerSession(
                    ride,
                    1,
                    ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3"),
                    ControllerCapabilities(
                        setOf(
                            MetricId.humanPowerW,
                            MetricId.cadenceRpm,
                            MetricId.speedRaw,
                            MetricId.controllerSpeedMps,
                        ),
                        true,
                    ),
                )
        )

    private fun rows(
        vararg readings: Pair<Long, Double?>,
        interval: (Long) -> Pair<Int, Int> = { 1 to 1 },
    ): TelemetryRows =
        TelemetryRows.of(
            readings.mapIndexed { index, (elapsedUs, watts) ->
                TelemetrySample(
                    ride,
                    1,
                    index + 1L,
                    elapsedUs,
                    1_000_000_000_000 + elapsedUs,
                    interval(elapsedUs).first,
                    interval(elapsedUs).second,
                    if (watts == null) mapOf(MetricId.cadenceRpm to 900_000L)
                    else mapOf(MetricId.humanPowerW to (watts * 1000).toLong(), MetricId.cadenceRpm to 900_000L),
                )
            }
        )

    private fun plot(range: TimeRange, slot: Long?, buckets: Int, rows: TelemetryRows): List<PlotPoint> {
        val reducer = PlotReducer(MetricId.humanPowerW, range, slot, buckets)
        reducer.append(rows, 0, rows.rows, sessions)
        return assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result()).value
    }

    private fun ref(elapsedUs: Long, watts: Double) =
        OriginalRef(1_000_000_000_000 + elapsedUs, elapsedUs, (watts * 1000).toLong())

    @Test
    fun bucketsAndSlotsAreTodays() {
        assertEquals(512, PlotSlots.buckets(512, 1))
        assertEquals(510, PlotSlots.buckets(512, 8))
        assertEquals(1, PlotSlots.buckets(5, 16_384))
        assertNull(PlotSlots.alignedSlotUs(TimeRange(0, 59_000_000), 60))
        assertEquals(1_000_000L, PlotSlots.alignedSlotUs(TimeRange(0, 60_000_000), 60))
        assertEquals(2_000_000L, PlotSlots.alignedSlotUs(TimeRange(0, 61_000_000), 60))
        assertEquals(16_000_000L, PlotSlots.alignedSlotUs(TimeRange(0, 8_000_000_000), 512))
    }

    @Test
    fun aBucketKeepsItsFirstMinimumMaximumAndLastOriginals() {
        val points =
            plot(
                TimeRange(0, 4_000_000),
                2_000_000,
                2,
                rows(0L to 200.0, 500_000L to 150.0, 1_000_000L to 300.0, 1_500_000L to 150.0, 2_000_000L to 100.0),
            )
        assertEquals(
            listOf(
                PlotPoint(ref(0, 200.0), ref(500_000, 150.0), ref(1_000_000, 300.0), ref(1_500_000, 150.0), 0),
                PlotPoint(
                    ref(2_000_000, 100.0),
                    ref(2_000_000, 100.0),
                    ref(2_000_000, 100.0),
                    ref(2_000_000, 100.0),
                    0,
                ),
            ),
            points,
        )
    }

    @Test
    fun aGapOrAMissingReadingStartsANewSegmentInsideABucket() {
        val points =
            plot(
                TimeRange(0, 20_000_000),
                null,
                1,
                rows(
                    0L to 1.0,
                    1_000_000L to 2.0,
                    8_000_000L to 3.0,
                    9_000_000L to null,
                    10_000_000L to 4.0,
                ),
            )
        assertEquals(listOf(0, 1, 2), points.map { it.segment })
    }

    @Test
    fun aShortRangeSplitsIntoEqualBucketsWithItsNeighboursOutside() {
        val readings = rows(0L to 10.0, 2_000_000L to 20.0, 3_000_000L to 30.0, 5_000_000L to 40.0, 12_000_000L to 50.0)
        val points = plot(TimeRange(2_500_000, 6_000_000), null, 2, readings)
        assertEquals(listOf(2_000_000L, 3_000_000L, 5_000_000L, 12_000_000L), points.map { it.first.elapsedUs })
        assertEquals(listOf(0, 0, 0, 1), points.map { it.segment })
    }

    @Test
    fun valuesOutsideThePlotRangeStillPlotForRiderPower() {
        assertEquals(1, plot(TimeRange(0, 1_000_000), null, 1, rows(0L to 40_000.0)).size)
    }

    @Test
    fun aPauseShorterThanThePlotGapStillStartsANewSegment() {
        val readings =
            rows(0L to 1.0, 1_000_000L to 2.0, 3_000_000L to 3.0, 4_000_000L to 4.0) {
                if (it < 2_000_000) 1 to 1 else 2 to 2
            }
        assertEquals(listOf(0, 1), plot(TimeRange(0, 10_000_000), null, 1, readings).map { it.segment })
    }

    @Test
    fun aRecordedRidesLineJoinsAcrossAShortReconnect() {
        val readings = rows(0L to 1.0, 1_000_000L to 2.0, 2_000_000L to 3.0) { if (it < 2_000_000) 1 to 1 else 1 to 2 }
        assertEquals(listOf(0), plot(TimeRange(0, 10_000_000), null, 1, readings).map { it.segment })
    }

    @Test
    fun anImportedRidesLineBreaksWhereItsRunChanges() {
        val readings = rows(0L to 1.0, 1_000_000L to 2.0, 2_000_000L to 3.0) { if (it < 2_000_000) 1 to 1 else 1 to 2 }
        val imported = sessions.mapValues { (_, session) -> session.copy(identity = null) }
        val reducer = PlotReducer(MetricId.humanPowerW, TimeRange(0, 10_000_000), null, 1)
        reducer.append(readings, 0, readings.rows, imported)
        assertEquals(listOf(0, 1), assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result()).value.map { it.segment })
    }

    @Test
    fun aWholeRidePlotFromTilesEqualsADirectPass() {
        val times = (0 until 64).map { it * 1_000_000L }.filter { it !in 20_000_000L..27_000_000L }
        val readings =
            rows(*times.map { it to (100.0 + it / 1_000_000 % 7) }.toTypedArray()) {
                if (it < 40_000_000) 1 to 1 else 2 to 2
            }
        val whole = TimeRange(0, 64_000_000)
        val direct = plot(whole, 16_000_000, 4, readings)
        val merged = PlotReducer(MetricId.humanPowerW, whole, 16_000_000, 4)
        for (slot in PlotTiles.parts(whole, 0)) {
            val range = TimeRange(slot * 16_000_000, (slot + 1) * 16_000_000)
            val tile = PlotReducer(MetricId.humanPowerW, range, 16_000_000, 1)
            val first = (0 until readings.rows).firstOrNull { readings.elapsedUs[it] >= range.startUs } ?: readings.rows
            val end = (0 until readings.rows).firstOrNull { readings.elapsedUs[it] >= range.endUs } ?: readings.rows
            tile.append(readings, maxOf(0, first - 1), end, sessions)
            merged.merge(tile.aggregate())
        }
        assertEquals(direct, assertIs<Bounded.Done<List<PlotPoint>>>(merged.result()).value)
    }

    @Test
    fun aTilesStatisticsAreItsBucketsOwnReadings() {
        val readings = rows(15_000_000L to 100.0, 16_000_000L to 200.0, 17_000_000L to 200.0)
        val tile = PlotReducer(MetricId.humanPowerW, TimeRange(16_000_000, 32_000_000), 16_000_000, 1)
        tile.append(readings, 0, readings.rows, sessions)
        val range = MetricReducer(MetricId.humanPowerW, TimeRange(15_000_000, 32_000_000))
        range.append(readings, 0, 1, sessions)
        range.merge(tile.aggregate().statistics)
        val direct = MetricReducer(MetricId.humanPowerW, TimeRange(15_000_000, 32_000_000))
        direct.append(readings, 0, readings.rows, sessions)
        assertEquals(direct.result(), range.result())
    }

    @Test
    fun locationAndHealthMetricsPlotFromFactsAndAnInaccurateSpeedBreaksTheLine() {
        val speed = PlotReducer(MetricId.speedMps, TimeRange(0, 10_000_000), null, 1)
        listOf(0L to 0.5, 1_000_000L to -1.0, 2_000_000L to 0.5).forEachIndexed { index, (atUs, accuracy) ->
            speed.append(Fact.Location(fix(index + 1L, atUs, 5.0 + index, accuracy)))
        }
        assertEquals(listOf(0, 1), assertIs<Bounded.Done<List<PlotPoint>>>(speed.result()).value.map { it.segment })
        val heart = PlotReducer(MetricId.heartRateBpm, TimeRange(0, 60_000_000), null, 1)
        listOf(0L, 29_000_000L, 59_000_000L).forEach { heart.append(Fact.Health(sample(it, 120.0))) }
        val points = assertIs<Bounded.Done<List<PlotPoint>>>(heart.result()).value
        assertEquals(listOf(0, 1), points.map { it.segment })
        assertEquals(120_000L, points.first().max.valueQ)
    }

    @Test
    fun aDistancePlotDrawsTheCumulativeDistanceAndBreaksAtSixSeconds() {
        val readings =
            TelemetryRows.of(
                listOf(0L, 1_000_000L, 2_000_000L, 9_000_000L, 10_000_000L).mapIndexed { index, atUs ->
                    TelemetrySample(
                        ride,
                        1,
                        index + 1L,
                        atUs,
                        1_000_000_000_000 + atUs,
                        1,
                        1,
                        mapOf(MetricId.speedRaw to 1_800L),
                    )
                }
            )
        val intervals = ControllerDistanceReducer(null).append(readings, 0, readings.rows, sessions)
        val reducer = PlotReducer(MetricId.controllerDistanceMeters, TimeRange(0, 20_000_000), null, 20)
        intervals.forEach(reducer::distance)
        val points = assertIs<Bounded.Done<List<PlotPoint>>>(reducer.result()).value
        assertEquals(listOf(0L, 5_000_000L, 10_000_000L, 10_000_000L, 15_000_000L), points.map { it.last.valueQ })
        assertEquals(listOf(0, 0, 0, 1, 1), points.map { it.segment })
    }

    @Test
    fun tilesAreSixteenSecondPowersOfTwoAlignedToTheRideStart() {
        assertEquals(0, PlotTiles.level(16_000_000))
        assertEquals(3, PlotTiles.level(128_000_000))
        assertNull(PlotTiles.level(8_000_000))
        assertNull(PlotTiles.level(48_000_000))
        assertEquals(0L..3L, PlotTiles.parts(TimeRange(0, 64_000_000), 0))
        assertEquals(0L..1L, PlotTiles.parts(TimeRange(20_000_000, 64_000_000), 1))
        assertEquals(1L..3L, PlotTiles.parts(TimeRange(20_000_000, 64_000_000), 0))
        assertTrue(PlotTiles.parts(TimeRange(5, 5), 0).isEmpty())
    }

    private fun fix(seq: Long, atUs: Long, speed: Double, accuracy: Double) =
        LocationFix(
            ride,
            seq,
            atUs,
            1_000_000_000_000 + atUs,
            1,
            1,
            false,
            45.5,
            9.2,
            5.0,
            120.0,
            AltitudeDatum.msl,
            3.0,
            120.0,
            3.0,
            speed,
            accuracy,
            37.0,
            8.0,
        )

    private fun sample(atUs: Long, bpm: Double) =
        HealthSample(
            ride,
            Producer.phone,
            "key-$atUs",
            "app.powerlog.tests",
            null,
            null,
            HealthMetric("heartRateBpm"),
            HealthRepresentation.rawSeries,
            bpm,
            null,
            1_000_000_000_000 + atUs,
            atUs,
            null,
            null,
            1,
        )
}
