package app.powerlog.domain.charts

import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnergyPerDistancePlotTest {
    private val ride = RideId("5a1e0001-0000-4000-8000-0000000000dd")
    private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
    private val sessions =
        mapOf(
            1 to
                ControllerSession(
                    ride,
                    1,
                    identity,
                    requireNotNull(ControllerDecoders.of(identity.decoder)).capabilities(identity),
                )
        )

    private fun rows(vararg readings: Triple<Long, Long, Long>, interval: (Long) -> Int = { 1 }) =
        TelemetryRows.of(
            readings.mapIndexed { index, (atUs, wattsQ, speedRaw) ->
                TelemetrySample(
                    ride,
                    1,
                    index + 1L,
                    atUs,
                    START_UTC_US + atUs,
                    interval(atUs),
                    interval(atUs),
                    mapOf(
                        MetricId.humanPowerW to wattsQ,
                        MetricId.speedRaw to speedRaw,
                        MetricId.motorInputPowerW to wattsQ,
                    ),
                )
            }
        )

    private fun values(points: Bounded<List<PlotPoint>>, quantum: Double = 1e6): List<Double> =
        assertIs<Bounded.Done<List<PlotPoint>>>(points).value.map { it.last.valueQ / quantum }

    @Test
    fun aWhPerKmPointIsPowerOverTheControllersSpeedAtEachReading() {
        val telemetry = rows(*(0L..4L).map { Triple(it * SECOND_US, 360_000L, 1_800L) }.toTypedArray())
        val plot =
            EnergyPerDistancePlotReducer(
                MetricId.riderWhPerKm,
                DistanceSource.controller,
                TimeRange(0, 10 * SECOND_US),
                10,
                SECOND_US,
                0,
            )
        plot.append(telemetry, 0, telemetry.rows, sessions)
        assertEquals(List(5) { 20.0 }, values(plot.result()))
    }

    @Test
    fun smoothingIsTheTrapezoidMeanOverItsWindowAndRestartsAfterAGap() {
        val telemetry =
            rows(
                Triple(0L, 360_000L, 1_800L),
                Triple(SECOND_US, 720_000L, 1_800L),
                Triple(2 * SECOND_US, 360_000L, 1_800L),
                Triple(6 * SECOND_US, 360_000L, 1_800L),
            )
        val plot =
            EnergyPerDistancePlotReducer(
                MetricId.riderWhPerKm,
                DistanceSource.controller,
                TimeRange(0, 10 * SECOND_US),
                10,
                SECOND_US,
                5_000_000,
            )
        plot.append(telemetry, 0, telemetry.rows, sessions)
        val smoothed = values(plot.result())
        assertEquals(listOf(20.0, 30.0, 30.0, 20.0), smoothed)
        assertEquals(
            listOf(0, 0, 0, 0),
            assertIs<Bounded.Done<List<PlotPoint>>>(plot.result()).value.map { it.segment },
        )
    }

    @Test
    fun gpsSpeedCountsWithinItsHoldInTheReadingsInterval() {
        val plot =
            EnergyPerDistancePlotReducer(
                MetricId.batteryWhPerKm,
                DistanceSource.phoneGps,
                TimeRange(0, 30 * SECOND_US),
                30,
                SECOND_US,
                0,
            )
        plot.append(Fact.Location(fix(0, 5.0)))
        val telemetry = rows(Triple(SECOND_US, 900_000L, 0L), Triple(11 * SECOND_US, 900_000L, 0L))
        plot.append(telemetry, 0, telemetry.rows, sessions)
        val points = assertIs<Bounded.Done<List<PlotPoint>>>(plot.result()).value
        assertEquals(1, points.size)
        assertEquals(50.0, points.single().last.valueQ / 1e6)
    }

    @Test
    fun aSeriesContinuedAcrossReadsDrawsWhatADirectPassDraws() {
        val readings =
            (0L until 1_200L).map { second ->
                Triple(second * 500_000L, (300_000L + second * 37 % 400 * 1_000), 1_500L + second * 13 % 300)
            }
        val telemetry = rows(*readings.toTypedArray()) { if (it in 200_000_000L..230_000_000L) 0 else 1 }
        val endUs = 600 * SECOND_US
        val buckets = 64
        val slot = PlotSlots.alignedSlotUs(TimeRange(0, endUs), buckets)
        val direct =
            EnergyPerDistancePlotReducer(
                MetricId.riderWhPerKm,
                DistanceSource.controller,
                TimeRange(0, endUs),
                buckets,
                slot,
                30_000_000,
            )
        direct.append(telemetry, 0, telemetry.rows, sessions)
        val series = EnergyPerDistanceSeries(MetricId.riderWhPerKm, DistanceSource.controller, 30_000_000)
        val firstEnd = 300 * SECOND_US
        val first = series.begin(firstEnd, buckets, PlotSlots.alignedSlotUs(TimeRange(0, firstEnd), buckets))
        val split = (0 until telemetry.rows).first { telemetry.elapsedUs[it] >= firstEnd }
        first.append(telemetry, 0, split, sessions)
        first.result()
        assertEquals(297 * SECOND_US, series.throughUs)
        val from = (0 until telemetry.rows).first { telemetry.elapsedUs[it] > series.throughUs }
        val second = series.begin(endUs, buckets, slot)
        second.append(telemetry, from, telemetry.rows, sessions)
        assertEquals(direct.result(), second.result())
        assertEquals(597 * SECOND_US, series.throughUs)
    }

    private fun fix(atUs: Long, speed: Double) =
        LocationFix(
            ride,
            1,
            atUs,
            START_UTC_US + atUs,
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
            0.5,
            37.0,
            8.0,
        )

    private companion object {
        const val SECOND_US = 1_000_000L
        const val START_UTC_US = 1_772_355_600_000_000L
    }
}
