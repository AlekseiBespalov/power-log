package app.powerlog.domain.core.analysis

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.battery.ConsumptionTotals
import app.powerlog.domain.core.analysis.battery.SelectedConsumption
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistancePolicy
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceReducer
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistanceTotals
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducersTest {
    private val ride = RideId("5a1e0001-0000-4000-8000-0000000000bb")
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

    private fun rows(count: Int, stepUs: Long, interval: (Int) -> Int = { 1 }, quanta: (Int) -> Map<MetricId, Long>) =
        TelemetryRows.of(
            List(count) { index ->
                TelemetrySample(
                    ride,
                    1,
                    index + 1L,
                    index * stepUs,
                    START_UTC_US + index * stepUs,
                    interval(index),
                    interval(index),
                    quanta(index),
                )
            }
        )

    private fun fix(
        seq: Long,
        atUs: Long,
        latitude: Double,
        interval: Int? = 1,
        barrier: Boolean = false,
        speed: Double? = null,
    ) =
        LocationFix(
            ride,
            seq,
            atUs,
            START_UTC_US + atUs,
            interval,
            1,
            barrier,
            latitude,
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

    private fun statistics(reducer: MetricReducer): MetricStatistics =
        assertIs<Bounded.Done<MetricStatistics>>(reducer.result()).value

    @Test
    fun tileStatisticsMergedIntoARangeEqualADirectPass() {
        val power = rows(41, SECOND_US) { mapOf(MetricId.humanPowerW to (100L + it * 7 % 23) * 1_000) }
        val range = TimeRange(5 * SECOND_US, 37 * SECOND_US)
        val direct = MetricReducer(MetricId.humanPowerW, range).also { it.append(power, 0, power.rows, sessions) }
        val tile = MetricReducer(MetricId.humanPowerW, TimeRange(16 * SECOND_US, 32 * SECOND_US), true)
        tile.append(power, 15, 32, sessions)
        val merged = MetricReducer(MetricId.humanPowerW, range)
        merged.append(power, 4, 16, sessions)
        merged.merge(tile.state())
        merged.append(power, 32, 38, sessions)
        assertEquals(statistics(direct), statistics(merged))
    }

    @Test
    fun aTileWhoseLeadingEdgeStartsBeforeTheRangeCountsOnlyItsPartInside() {
        val power = rows(33, SECOND_US) { mapOf(MetricId.humanPowerW to (100L + it) * 1_000) }
        val range = TimeRange(15 * SECOND_US + 500_000, 32 * SECOND_US)
        val direct = MetricReducer(MetricId.humanPowerW, range).also { it.append(power, 0, power.rows, sessions) }
        val tile = MetricReducer(MetricId.humanPowerW, TimeRange(16 * SECOND_US, 32 * SECOND_US), true)
        tile.append(power, 15, 32, sessions)
        val merged = MetricReducer(MetricId.humanPowerW, range)
        merged.append(power, 15, 16, sessions)
        merged.merge(tile.state())
        merged.append(power, 32, 33, sessions)
        assertEquals(statistics(direct), statistics(merged))
    }

    @Test
    fun aRangeOwnsItsStartButNotItsEndAndClipsTheEdgesAcrossThem() {
        val power = rows(3, SECOND_US) { mapOf(MetricId.humanPowerW to 100_000L) }
        val result =
            statistics(
                MetricReducer(MetricId.humanPowerW, TimeRange(500_000, 2 * SECOND_US)).also {
                    it.append(power, 0, 3, sessions)
                }
            )
        assertEquals(1L, result.count)
        assertEquals(1_500_000L, result.coveredUs)
        assertEquals(100_000L * 1_500_000, result.integralQUs)
        assertEquals(100.0, result.average)
    }

    @Test
    fun anInvalidReadingOrOneOutsideAnIntervalBreaksTheIntegral() {
        val power =
            rows(5, SECOND_US, interval = { if (it == 3) 0 else 1 }) {
                mapOf(MetricId.humanPowerW to if (it == 1) 40_000_000L else 100_000L)
            }
        val result =
            statistics(
                MetricReducer(MetricId.humanPowerW, TimeRange(0, 10 * SECOND_US)).also {
                    it.append(power, 0, 5, sessions)
                }
            )
        assertEquals(3L, result.count)
        assertEquals(0L, result.coveredUs)
        assertEquals(OriginalRef(START_UTC_US, 0, 100_000), result.minimum)
    }

    @Test
    fun aCourseOfAFullTurnIsNoReadingAndCourseHasNoMean() {
        val reducer = MetricReducer(MetricId.courseDegrees, TimeRange(0, 10 * SECOND_US))
        reducer.append(Fact.Location(fix(1, 0, 45.5).copy(courseDeg = 360.0)))
        reducer.append(Fact.Location(fix(2, SECOND_US, 45.5).copy(courseDeg = 90.0)))
        val result = statistics(reducer)
        assertEquals(1L, result.count)
        assertNull(result.average)
        assertNull(result.integralQUs)
    }

    @Test
    fun emptyStatesAreEqual() {
        val range = TimeRange(0, 16_000_000)
        assertEquals(
            MetricReducer(MetricId.humanPowerW, range).state(),
            MetricReducer(MetricId.humanPowerW, range).state(),
        )
    }

    @Test
    fun aRangeCuttingAControllerIntervalInterpolatesItsSpeed() {
        val interval =
            DistanceInterval(
                TimeRange(0, 2 * SECOND_US),
                2_000_000,
                0,
                1,
                OriginalRef(START_UTC_US, 0, 0),
                OriginalRef(START_UTC_US + 2 * SECOND_US, 2 * SECOND_US, 2_000_000),
                0,
                2_000_000,
            )
        val totals =
            assertIs<Bounded.Done<DistanceTotals>>(
                    DistanceReducer(TimeRange(0, SECOND_US), null).also { it.append(interval) }.result(SECOND_US)
                )
                .value
        assertEquals(500_000L, totals.distanceQ)
        assertEquals(SECOND_US, totals.coveredUs)
        assertEquals(false, totals.partial)
    }

    @Test
    fun controllerIntervalsIntegrateTheSpeedTrapezoidAndCarryTheRunningTotal() {
        val speed = rows(3, SECOND_US) { mapOf(MetricId.speedRaw to 1_800L + it * 360) }
        val intervals = ControllerDistanceReducer(null).append(speed, 0, 3, sessions)
        assertEquals(listOf(5_500_000L, 6_500_000L), intervals.map { it.distanceQ })
        assertEquals(listOf(0L, 5_500_000L), intervals.map { it.start.valueQ })
        assertEquals(12_000_000L, intervals.last().end.valueQ)
    }

    @Test
    fun gpsEdgesNeedBothFixesInOneIntervalWithinTenSecondsAndFortyMetresASecond() {
        fun edges(vararg fixes: LocationFix): Int {
            val reducer = GpsDistanceReducer(null)
            return fixes.count { reducer.append(it) != null }
        }
        assertEquals(1, edges(fix(1, 0, 45.5), fix(2, SECOND_US, 45.5001)))
        assertEquals(0, edges(fix(1, 0, 45.5), fix(2, 11 * SECOND_US, 45.5001)))
        assertEquals(0, edges(fix(1, 0, 45.5), fix(2, SECOND_US, 45.501)))
        assertEquals(0, edges(fix(1, 0, 45.5), fix(2, SECOND_US, 45.5001, interval = 2)))
        assertEquals(0, edges(fix(1, 0, 45.5), fix(2, SECOND_US, 45.5001, barrier = true)))
        assertEquals(
            1,
            edges(
                fix(1, 0, 45.5),
                fix(2, SECOND_US, 45.5001, barrier = true),
                fix(3, 2 * SECOND_US, 45.5002),
                fix(4, 3 * SECOND_US, 45.5003),
            ),
        )
    }

    @Test
    fun aStationaryGpsEdgeCoversTimeWithNoDistance() {
        val reducer = GpsDistanceReducer(null)
        reducer.append(fix(1, 0, 45.5, speed = 0.2))
        val edge = requireNotNull(reducer.append(fix(2, SECOND_US, 45.50001, speed = 0.3)))
        assertEquals(0L, edge.interval.distanceQ)
        assertEquals(1L to 2L, edge.firstSeq to edge.lastSeq)
    }

    @Test
    fun aDistanceTotalPastInt64IsCapacity() {
        val reducer = DistanceReducer(TimeRange(0, 3 * SECOND_US), null)
        for (index in 0L until 2L) {
            val start = OriginalRef(START_UTC_US + index * SECOND_US, index * SECOND_US, 0)
            val end = OriginalRef(START_UTC_US + (index + 1) * SECOND_US, (index + 1) * SECOND_US, 0)
            reducer.append(
                DistanceInterval(
                    TimeRange(start.elapsedUs, end.elapsedUs),
                    Long.MAX_VALUE / 2 + 1,
                    0,
                    1,
                    start,
                    end,
                    null,
                    null,
                )
            )
        }
        assertEquals(Bounded.Capacity, reducer.result(2 * SECOND_US))
    }

    @Test
    fun whPerKmIsMatchedEnergyOverMatchedDistanceAndARangeClipsBoth() {
        val telemetry = rows(11, SECOND_US) { mapOf(MetricId.humanPowerW to 360_000L, MetricId.speedRaw to 1_800L) }
        val distance = ControllerDistanceReducer(null).append(telemetry, 0, telemetry.rows, sessions)
        fun reduce(range: TimeRange?): Map<MetricId, EnergyPerDistanceTotals> {
            val reducer = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller, range)
            reducer.append(telemetry, 0, telemetry.rows, sessions)
            distance.forEach(reducer::distance)
            return assertIs<Bounded.Done<Map<MetricId, EnergyPerDistanceTotals>>>(reducer.result()).value
        }
        val ride = reduce(null).getValue(MetricId.riderWhPerKm)
        assertTrue(abs(ride.energyWh - 1.0) < 1e-12)
        assertEquals(50.0, ride.distanceM)
        assertTrue(abs(requireNotNull(ride.whPerKm) - 20.0) < 1e-9)
        val half = reduce(TimeRange(2 * SECOND_US + 500_000, 7 * SECOND_US + 500_000)).getValue(MetricId.riderWhPerKm)
        assertTrue(abs(half.energyWh - 0.5) < 1e-12)
        assertTrue(abs(half.distanceM - 25.0) < 1e-9)
        assertNull(reduce(null).getValue(MetricId.batteryWhPerKm).whPerKm)
    }

    @Test
    fun whPerKmWaitsForTheDistanceOfEdgesFedBeforeIt() {
        val telemetry = rows(11, SECOND_US) { mapOf(MetricId.humanPowerW to 360_000L, MetricId.speedRaw to 1_800L) }
        val distance = ControllerDistanceReducer(null).append(telemetry, 0, telemetry.rows, sessions)
        val after = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller)
        distance.forEach(after::distance)
        after.append(telemetry, 0, telemetry.rows, sessions)
        val before = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller)
        before.append(telemetry, 0, telemetry.rows, sessions)
        distance.forEach(before::distance)
        assertEquals(after.result(), before.result())
    }

    @Test
    fun whPerKmRefusesMoreThan1024PendingEdges() {
        fun reduce(count: Int): Bounded<Map<MetricId, EnergyPerDistanceTotals>> {
            val telemetry = rows(count, 5_000) { mapOf(MetricId.humanPowerW to 200_000L) }
            val reducer = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller)
            reducer.append(telemetry, 0, telemetry.rows, sessions)
            return reducer.result()
        }
        assertIs<Bounded.Done<Map<MetricId, EnergyPerDistanceTotals>>>(reduce(1_025))
        assertEquals(Bounded.Capacity, reduce(1_026))
    }

    @Test
    fun whPerKmKeepsTenSecondsOfRiderEnergyAnHourWithoutDistance() {
        val telemetry = rows(HOUR_AT_8_HZ, 125_000) { mapOf(MetricId.humanPowerW to 200_000L) }
        val reducer = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller)
        reducer.append(telemetry, 0, telemetry.rows, sessions)
        assertIs<Bounded.Done<Map<MetricId, EnergyPerDistanceTotals>>>(reducer.result())
        assertTrue(reducer.state().rider.pending.size <= 81, "${reducer.state().rider.pending.size} pending")
    }

    @Test
    fun whPerKmKeepsTwentySecondsOfDistanceAnHourWithoutBatteryEnergy() {
        val telemetry = rows(HOUR_AT_8_HZ, 125_000) { mapOf(MetricId.speedRaw to 1_800L) }
        val distance = ControllerDistanceReducer(null)
        val reducer = EnergyPerDistanceReducer(null, BatteryMethod.controller, DistanceSource.controller)
        for (row in 0 until telemetry.rows) {
            reducer.append(telemetry, row, row + 1, sessions)
            distance.append(telemetry, row, row + 1, sessions).forEach(reducer::distance)
        }
        assertTrue(reducer.state().distance.size <= 161, "${reducer.state().distance.size} distance edges")
    }

    @Test
    fun whPerKmPolicyValuesAreTodays() {
        assertEquals(2_000_000L, EnergyPerDistancePolicy.totalRefreshUs(59 * SECOND_US))
        assertEquals(5_000_000L, EnergyPerDistancePolicy.totalRefreshUs(60 * SECOND_US))
        assertEquals(15_000_000L, EnergyPerDistancePolicy.totalRefreshUs(600 * SECOND_US))
        assertEquals(30_000_000L, EnergyPerDistancePolicy.totalRefreshUs(3_600 * SECOND_US))
        assertEquals(18_000_000L, EnergyPerDistancePolicy.contextUs(0))
        assertEquals(73_000_000L, EnergyPerDistancePolicy.contextUs(60_000_000))
    }

    @Test
    fun theSelectedConsumptionIsTheMethodsTwoLanes() {
        val totals =
            ConsumptionMetric.entries.associateWith { ConsumptionTotals(it.ordinal.toDouble(), 0, true, false, false) }
        assertEquals(
            SelectedConsumption(
                totals[ConsumptionMetric.calculatedEnergyWh],
                totals[ConsumptionMetric.calculatedChargeAh],
            ),
            SelectedConsumption.of(BatteryMethod.calculated, totals),
        )
        assertEquals(
            SelectedConsumption(
                totals[ConsumptionMetric.controllerEnergyWh],
                totals[ConsumptionMetric.controllerChargeAh],
            ),
            SelectedConsumption.of(BatteryMethod.controller, totals),
        )
    }

    private companion object {
        const val SECOND_US = 1_000_000L
        const val HOUR_AT_8_HZ = 3_600 * 8
        const val START_UTC_US = 1_772_355_600_000_000L
    }
}
