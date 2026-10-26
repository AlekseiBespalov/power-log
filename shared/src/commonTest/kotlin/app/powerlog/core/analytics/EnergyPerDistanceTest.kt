package app.powerlog.core.analytics

import app.powerlog.core.protocol.*
import kotlin.test.*

class EnergyPerDistanceTest {

    @Test
    fun smoothingUsesTheSelectedDurationEvenForASinglePointQuery() {
        for (seconds in listOf(5, 30, 60)) {
            val value =
                result(
                    ride(
                        seconds = 120,
                        start = 120.0,
                        speed = { 5.0 },
                        watts = { if (it < 120) 180.0 else 360.0 },
                        smooth = seconds,
                    )
                )
            // 10 Wh/km baseline plus a one-second triangular rise to 20 Wh/km.
            assertEquals(10.0 + 5.0 / seconds, value.latest(rider).obj().required("value").number(), 1e-9)
        }
        assertFailsWith<IllegalArgumentException> { ride(smooth = 10) }
    }

    @Test
    fun minuteSmoothingAtEightHertzMatchesAnIndependentWindowIntegral() {
        val query = EnergyPerDistanceQuery(0, 120_000_000, 64, 120_000_000, true, 60, false)
        fun watts(i: Int) = ((i * 17) % 731).toDouble()
        for (i in 0..960) query.appendTelemetry(i * 125_000L, watts(i), watts(i), 5.0, 0, "fixture", stamp(i / 8))
        var area = 0.0
        for (i in 481..960) area += (watts(i - 1) + watts(i)) / 2 * 0.125
        assertEquals(area / 60 / 18, result(query).latest(rider).obj().required("value").number(), 1e-9)
    }

    private val battery = "batteryWhPerKm"
    private val rider = "riderWhPerKm"

    private fun result(query: EnergyPerDistanceQuery) = JsonCodec.parse(query.resultJson(), arrayLimit = 16384).obj()

    private fun Obj.stats(metric: String) = required("statistics").obj().required(metric).obj()

    private fun Obj.latest(metric: String) = required("points").obj().required(metric)

    private fun stamp(t: Int): String {
        fun digits(value: Int) = value.toString().padStart(2, '0')
        return "2026-01-01T${digits(t / 3600)}:${digits(t / 60 % 60)}:${digits(t % 60)}Z"
    }

    private fun ride(
        seconds: Int = 120,
        start: Double = 0.0,
        end: Double = seconds.toDouble(),
        speed: (Int) -> Double? = { 5.0 },
        watts: (Int) -> Double? = { 180.0 },
        active: (Int) -> Int? = { 0 },
        counter: Boolean = true,
        smooth: Int = 0,
    ): EnergyPerDistanceQuery {
        val query =
            EnergyPerDistanceQuery(
                FixedPoint.timeUs(start),
                FixedPoint.timeUs(end),
                64,
                FixedPoint.timeUs(end - start),
                true,
                smooth,
            )
        query.appendTelemetry(0, watts(0), 1800.0, speed(0), active(0), "fixture", stamp(0))
        for (t in 1..seconds) {
            val a = (t - 1) * 1_000_000L
            val b = t * 1_000_000L
            speed(t)?.let { query.appendDistance(a, b, it, null, null) }
            query.appendBattery(
                a,
                b,
                0.5,
                if (counter) null else 1800.0,
                if (counter) null else 1800.0,
                counter,
                stamp(t),
            )
            query.appendTelemetry(b, watts(t), 1800.0, speed(t), active(t), "fixture", stamp(t))
        }
        return query
    }

    @Test
    fun instantaneousPlotsAndWeightedSummaryUseMatchingDistance() {
        val result = result(ride(speed = { if (it <= 60) 5.0 else 10.0 }))
        assertEquals(60.0 / 0.9, result.stats(battery).required("ratio").number(), 1e-9)
        assertEquals(6.0 / 0.9, result.stats(rider).required("ratio").number(), 1e-9)
        assertEquals(50.0, result.latest(battery).obj().required("value").number(), 1e-9)
        assertEquals(5.0, result.latest(rider).obj().required("value").number(), 1e-9)
        val graph =
            result.required("series").obj().required(battery).array().map { it.obj().required("value").number() }
        assertTrue(graph.any { it > 99.9 })
        assertTrue(graph.any { it < 50.1 })
    }

    @Test
    fun zeroWorkIsValidAndFirstMovingObservationNeedsNoWarmup() {
        assertEquals(0.0, result(ride(watts = { 0.0 })).latest(rider).obj().required("value").number())
        val stopped = result(ride(speed = { 0.0 }))
        assertEquals(Nil, stopped.latest(battery))
        assertNull(stopped.stats(battery)["ratio"])
        assertEquals(100.0, result(ride(seconds = 1)).latest(battery).obj().required("value").number())
    }

    @Test
    fun StoppedOrMissingSampleDoesNotInspectAsANeighboringMovingSample() {
        for (smooth in listOf(0, 5, 30, 60)) {
            for (speed in listOf(0.0, null)) {
                val query = EnergyPerDistanceQuery(2_000_000, 2_000_000, 8, 0, true, smooth)
                query.appendTelemetry(1_000_000, 180.0, 1800.0, 5.0, 0, "fixture", stamp(1))
                query.appendTelemetry(2_000_000, 180.0, 1800.0, speed, 0, "fixture", stamp(2))
                query.appendTelemetry(3_000_000, 180.0, 1800.0, 10.0, 0, "fixture", stamp(3))
                val value = result(query)
                for (metric in listOf(battery, rider)) {
                    assertEquals(Nil, value.latest(metric))
                    for (edge in listOf("start", "end")) assertEquals(
                        Nil,
                        value.required("endpoints").obj().required(edge).obj().required(metric),
                    )
                }
            }
        }
    }

    @Test
    fun MissingDistanceRemovesEnergyFromTheSameInterval() {
        val result = result(ride(speed = { if (it in 40..70) null else 5.0 }))
        assertEquals(100.0, result.stats(battery).required("ratio").number(), 1e-9)
        assertEquals(10.0, result.stats(rider).required("ratio").number(), 1e-9)
        assertEquals(89.0, result.stats(battery).required("coveredSeconds").number())
        assertEquals(Bool(true), result.stats(battery)["partial"])
        assertEquals(100.0, result.latest(battery).obj().required("value").number())
    }

    @Test
    fun RiderMissingAndPausedReadingsDoNotBecomeZeroOrBridgeThePause() {
        val result =
            result(
                ride(
                    watts = { if (it == 20) null else 180.0 },
                    active = { if (it in 40..70) null else if (it > 70) 1 else 0 },
                )
            )
        assertEquals(10.0, result.stats(rider).required("ratio").number(), 1e-9)
        assertEquals(86.0, result.stats(rider).required("coveredSeconds").number())
        assertEquals(120.0, result.stats(battery).required("coveredSeconds").number())
    }

    @Test
    fun CounterBoundariesAreExcludedWhileIntegratedWorkCanBeClipped() {
        val counter = result(ride(start = 10.5, end = 30.5))
        val calculated = result(ride(start = 10.5, end = 30.5, counter = false))
        assertEquals(19.0, counter.stats(battery).required("coveredSeconds").number())
        assertEquals(Bool(true), counter.stats(battery)["unresolvedBoundary"])
        assertEquals(20.0, calculated.stats(battery).required("coveredSeconds").number())
        assertEquals(20.0, counter.stats(rider).required("coveredSeconds").number())
        assertEquals(100.0, counter.stats(battery).required("ratio").number(), 1e-9)
    }

    @Test
    fun GPSIntervalsWithDifferentTimestampsAreJoinedWithoutLosingOrInventingEnergy() {
        val query = EnergyPerDistanceQuery(0, 20_000_000, 20, 20_000_000, false, 0)
        query.appendSpeed(0, 5.0, 0.1, 0)
        query.appendTelemetry(0, 180.0, 1800.0, null, 0, "fixture", stamp(0))
        for (t in 1..20) {
            val end = t * 1_000_000L
            query.appendBattery(end - 1_000_000, end, 0.5, null, null, true, stamp(t))
            query.appendSpeed(end, 5.0, 0.1, 0)
            query.appendTelemetry(end, 180.0, 1800.0, null, 0, "fixture", stamp(t))
            if (t % 2 == 0) query.appendDistance(end - 2_000_000, end, 10.0, null, null)
        }
        val result = result(query)
        assertEquals(100.0, result.stats(battery).required("ratio").number(), 1e-9)
        assertEquals(20.0, result.stats(battery).required("coveredSeconds").number())
        assertEquals(10.0, result.latest(rider).obj().required("value").number(), 1e-9)
    }

    @Test
    fun EightHourRideRetainsFullHistoryWithBoundedOutputAndSameAnswer() {
        val query = ride(seconds = 8 * 3600, smooth = 60)
        val result = result(query)
        assertTrue(result.required("series").obj().required(battery).array().size <= 258)
        assertEquals(100.0, result.stats(battery).required("ratio").number(), 1e-8)
        val graph = result.required("series").obj().required(battery).array()
        assertEquals(0.0, graph.first().obj().required("elapsedSeconds").number())
        assertEquals(28800.0, graph.last().obj().required("elapsedSeconds").number())
        assertEquals(query.resultJson(), query.resultJson())
    }

    @Test
    fun AccelerationPeakAppearsAtOriginalTimeAndSmoothingDoesNotChangeEnergySummary() {
        fun capture(smooth: Int): Obj {
            val query = EnergyPerDistanceQuery(0, 8_000_000, 64, 8_000_000, true, smooth)
            for (i in 0..32) {
                val time = i * 250_000L
                val power = if (i == 8) 9000.0 else 300.0
                if (i > 0) {
                    query.appendDistance(time - 250_000, time, 25.0 / 6, 50.0 / 3, 50.0 / 3)
                    query.appendBattery(time - 250_000, time, 1.0, null, null, true, stamp(i / 4))
                }
                query.appendTelemetry(time, 120.0, power, 50.0 / 3, 0, "fixture", stamp(i / 4))
            }
            return result(query)
        }
        val raw = capture(0)
        val smooth = capture(5)
        val points = raw.required("series").obj().required(battery).array().map { it.obj() }
        assertEquals(33, points.size)
        assertEquals(5.0, points.first().required("value").number(), 1e-12)
        val peak = raw.stats(battery).required("max").obj()
        assertEquals(2.0, peak.required("elapsedSeconds").number())
        assertEquals(150.0, peak.required("value").number(), 1e-12)
        assertTrue(smooth.stats(battery).required("max").obj().required("value").number() < 150)
        assertEquals(raw.stats(battery)["ratio"], smooth.stats(battery)["ratio"])
        assertEquals(raw.required("averages"), smooth.required("averages"))
    }

    @Test
    fun StationaryEnergyCountsInRideAverageButStopsBreakTheGraphAndSmoothing() {
        val query = EnergyPerDistanceQuery(0, 3_000_000, 20, 3_000_000, true, 5)
        for (t in 0..3) {
            val speed = if (t == 2) 0.0 else 5.0
            if (t > 0) {
                query.appendDistance((t - 1) * 1_000_000L, t * 1_000_000L, speed, null, null)
                query.appendBattery((t - 1) * 1_000_000L, t * 1_000_000L, 0.5, null, null, true, stamp(t))
            }
            query.appendTelemetry(t * 1_000_000L, 0.0, if (t == 1) 3600.0 else 1800.0, speed, 0, "fixture", stamp(t))
        }
        val result = result(query)
        assertEquals(150.0, result.stats(battery).required("ratio").number())
        assertEquals(100.0, result.latest(battery).obj().required("value").number())
        val points = result.required("series").obj().required(battery).array().map { it.obj() }
        assertFalse(points.any { it.required("elapsedSeconds").number() == 2.0 })
        assertEquals(Bool(true), points.last()["startsSegment"])
    }

    @Test
    fun GPSSpeedMustBeFreshAndFromTheSameActiveIntervalAndNeverFallsBackToController() {
        val query = EnergyPerDistanceQuery(0, 14_000_000, 30, 14_000_000, false, 0)
        query.appendSpeed(0, 50.0 / 3, 0.1, 0)
        query.appendTelemetry(0, 120.0, 300.0, 5.0, 0, "fixture", stamp(0))
        query.appendTelemetry(10_000_000, 120.0, 300.0, 5.0, 0, "fixture", stamp(10))
        query.appendTelemetry(10_250_000, 120.0, 300.0, 5.0, 0, "fixture", stamp(10))
        query.appendSpeed(11_000_000, 50.0 / 3, -1.0, 0)
        query.appendTelemetry(11_000_000, 120.0, 300.0, 5.0, 0, "fixture", stamp(11))
        query.appendSpeed(12_000_000, 50.0 / 3, 0.1, 0)
        query.appendTelemetry(12_000_000, 120.0, 300.0, 5.0, 1, "fixture", stamp(12))
        query.appendSpeed(13_000_000, 50.0 / 3, 0.1, 1)
        query.appendTelemetry(13_000_000, 120.0, 300.0, 5.0, 1, "fixture", stamp(13))
        query.appendTelemetry(14_000_000, null, null, 5.0, 1, "fixture", stamp(14))
        val result = result(query)
        val points = result.required("series").obj().required(battery).array().map { it.obj() }
        assertEquals(listOf(0.0, 10.0, 13.0), points.map { it.required("elapsedSeconds").number() })
        points.forEach { assertEquals(5.0, it.required("value").number(), 1e-12) }
        assertEquals(Nil, result.latest(battery))
    }
}
