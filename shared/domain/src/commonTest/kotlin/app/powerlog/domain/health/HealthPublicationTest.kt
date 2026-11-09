package app.powerlog.domain.health

import app.powerlog.domain.analytics.FixedPoint
import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsEdge
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.core.time.UtcRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthPublicationTest {
    private val rideId = RideId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    private val session =
        ControllerSession(
            rideId,
            1,
            null,
            ControllerCapabilities(setOf(MetricId.humanPowerW, MetricId.cadenceRpm), false),
        )
    private val sessions = mapOf(1 to session, 2 to session.copy(sessionNo = 2))
    private val noRows =
        TelemetryRows(
            0,
            DoubleArray(0),
            IntArray(0),
            DoubleArray(0),
            DoubleArray(0),
            IntArray(0),
            IntArray(0),
            emptyMap(),
        )

    private class Reading(
        val elapsedUs: Long,
        val value: Double?,
        val interval: Int? = 1,
        val run: Int = 1,
        val session: Int = 1,
        val accuracyM: Double? = 2.0,
        val speedAccuracyMps: Double? = null,
    )

    private fun rows(metric: MetricId, readings: List<Reading>, quantum: Long): TelemetryRows =
        TelemetryRows(
            readings.size,
            DoubleArray(readings.size) { readings[it].elapsedUs.toDouble() },
            IntArray(readings.size) { readings[it].session },
            DoubleArray(readings.size) { it + 1.0 },
            DoubleArray(readings.size) { readings[it].elapsedUs.toDouble() },
            IntArray(readings.size) { readings[it].interval ?: 0 },
            IntArray(readings.size) { readings[it].run },
            mapOf(
                metric to
                    DoubleArray(readings.size) {
                        readings[it].value?.let { v -> FixedPoint.quantize(v, quantum).toDouble() } ?: Double.NaN
                    }
            ),
        )

    private fun fixes(readings: List<Reading>): List<LocationFix> = readings.mapIndexed { index, reading ->
        LocationFix(
            rideId,
            index + 1L,
            reading.elapsedUs,
            reading.elapsedUs,
            reading.interval,
            reading.run,
            false,
            40.0,
            -3.5,
            reading.accuracyM,
            600.0,
            AltitudeDatum.msl,
            3.0,
            600.0,
            3.0,
            reading.value,
            reading.speedAccuracyMps,
            90.0,
            5.0,
        )
    }

    private fun second(quantity: HealthQuantity, readings: List<Reading>, n: Long = 0, cutoffUs: Long = 60_000_000) =
        HealthPublication.second(
            rideId,
            HealthPublication.bounds(0, cutoffUs, n),
            quantity,
            when (quantity) {
                HealthQuantity.riderPower -> rows(MetricId.humanPowerW, readings, 1_000)
                HealthQuantity.cyclingCadence -> rows(MetricId.cadenceRpm, readings, 10_000)
                else -> noRows
            },
            sessions,
            if (quantity == HealthQuantity.cyclingSpeed) fixes(readings) else emptyList(),
        )

    @Test
    fun aSecondIsClippedAtTheCutoffAndPublishedOnceItsBufferHasPassed() {
        assertEquals(SecondBounds(2, UtcRange(2_400_000, 3_400_000)), HealthPublication.bounds(400_000, 9_000_000, 2))
        assertEquals(SecondBounds(2, UtcRange(2_400_000, 2_900_000)), HealthPublication.bounds(400_000, 2_900_000, 2))
        val bounds = HealthPublication.bounds(0, 60_000_000, 0)
        assertFalse(HealthPublication.eligible(bounds, 3_900_000, stopped = false))
        assertTrue(HealthPublication.eligible(bounds, 3_900_000, stopped = true))
        assertTrue(HealthPublication.eligible(bounds, 4_000_000, stopped = false))
    }

    @Test
    fun aSecondIsTheTimeWeightedValueOverItsReadings() {
        val readings = listOf(Reading(-1_000_000, 100.0), Reading(400_000, 200.0), Reading(2_000_000, 100.0))
        val plan = assertNotNull(second(HealthQuantity.riderPower, readings))
        assertEquals(183.0358, plan.value)
        assertEquals("powerlog:${rideId.value}:rider-power:second:0", plan.syncId)
        assertEquals(0L to 1_000_000L, plan.startUtcUs to plan.endUtcUs)
        val cadence = listOf(Reading(-500_000, 80.0), Reading(600_000, 95.5), Reading(1_700_000, 90.0))
        assertEquals(92.56365, second(HealthQuantity.cyclingCadence, cadence)?.value)
    }

    @Test
    fun readingsOutsideTheHealthRangesOrAGapBreakTheSecond() {
        val gap = listOf(Reading(-2_600_000, 100.0), Reading(500_000, 200.0), Reading(3_100_000, 100.0))
        assertNull(second(HealthQuantity.riderPower, gap)?.value)
        val tooHigh = listOf(Reading(0, 100.0), Reading(500_000, 5_001.0), Reading(1_000_000, 100.0))
        assertNull(second(HealthQuantity.riderPower, tooHigh))
        val atLimit = listOf(Reading(0, 100.0), Reading(500_000, 5_000.0), Reading(1_000_000, 100.0))
        assertEquals(2_550.0, second(HealthQuantity.riderPower, atLimit)?.value)
        val paused = listOf(Reading(0, 100.0), Reading(500_000, 200.0, interval = null), Reading(1_000_000, 100.0))
        assertNull(second(HealthQuantity.riderPower, paused))
        val reconnect = listOf(Reading(0, 100.0), Reading(600_000, 300.0, run = 2, session = 2))
        assertNull(second(HealthQuantity.riderPower, reconnect))
        assertNull(second(HealthQuantity.riderPower, listOf(Reading(-500_000, 100.0), Reading(1_500_000, 100.0))))
        assertNull(second(HealthQuantity.cyclingDistance, listOf(Reading(0, 1.0), Reading(500_000, 1.0))))
    }

    @Test
    fun aGpsSpeedSecondNeedsAFixWithinFiftyMetres() {
        fun speed(accuracyM: Double) =
            listOf(
                Reading(-500_000, 5.0, accuracyM = 3.0),
                Reading(500_000, 6.0, accuracyM = accuracyM),
                Reading(1_500_000, 5.0, accuracyM = 3.0),
            )
        val valid = assertNotNull(second(HealthQuantity.cyclingSpeed, speed(50.0)))
        assertEquals(5.75, valid.value)
        assertNull(second(HealthQuantity.cyclingSpeed, speed(51.0)))
        val negativeAccuracy =
            listOf(Reading(0, 5.0, speedAccuracyMps = -1.0), Reading(500_000, 5.0, speedAccuracyMps = -1.0))
        assertNull(second(HealthQuantity.cyclingSpeed, negativeAccuracy))
        val noAccuracy = listOf(Reading(0, 5.0, accuracyM = null), Reading(500_000, 5.0, accuracyM = null))
        assertNull(second(HealthQuantity.cyclingSpeed, noAccuracy))
    }

    @Test
    fun theKeptSecondFixturesPublishTheSameValues() {
        val kinds =
            mapOf(
                "rider-power" to HealthQuantity.riderPower,
                "cycling-cadence" to HealthQuantity.cyclingCadence,
                "cycling-speed" to HealthQuantity.cyclingSpeed,
            )
        var compared = 0
        for (case in fixture("health/seconds.json")["cases"].list()) {
            val input = case["input"]
            val expected = case["expected"]
            val quantity = kinds[input["metric"].optionalText()] ?: continue
            val raw = input["readings"].list()
            if (raw.any { it["source"]["source"].text() == "watch" } || expected["samples"] == J.Null) continue
            var run = 0
            var key: List<String?>? = null
            val readings = raw.map { r ->
                val continuity =
                    listOf(
                        r["activeInterval"].optionalText(),
                        r["connectionEpoch"].optionalText(),
                        r["interruptionIndex"].text(),
                        r["clockEpoch"].optionalText(),
                    )
                if (continuity != key) run++
                key = continuity
                Reading(
                    r["collectionUs"].long(),
                    r["value"].optionalDouble(),
                    r["activeInterval"].optionalDouble()?.toInt(),
                    run,
                    if (r["connectionEpoch"].optionalText() == "bike-b") 2 else 1,
                    r["horizontalAccuracyM"].optionalDouble(),
                    r["speedAccuracyMps"].optionalDouble(),
                )
            }
            val end = input["endOffsetUs"].long()
            val samples = expected["samples"].list().associateBy { it["n"].long() }
            for (n in 0 until (end + 999_999) / 1_000_000) {
                val value = second(quantity, readings, n, end)?.value
                assertEquals(samples[n]?.get("value")?.double(), value, "${case["name"].text()} $n")
                compared++
            }
        }
        assertTrue(compared >= 12, "$compared seconds compared")
    }

    @Test
    fun aGpsEdgeIsOneDistanceRecord() {
        fun edge(distanceQ: Long, startUtcUs: Long, endUtcUs: Long) =
            GpsEdge(
                DistanceInterval(
                    TimeRange(startUtcUs, endUtcUs),
                    distanceQ,
                    1,
                    1,
                    OriginalRef(startUtcUs, startUtcUs, 0),
                    OriginalRef(endUtcUs, endUtcUs, distanceQ),
                    null,
                    null,
                ),
                7,
                8,
            )
        assertEquals(
            HealthPlan.Quantity(
                "powerlog:${rideId.value}:cycling-distance:interval:seq:phone:7:seq:phone:8",
                HealthQuantity.cyclingDistance,
                10_000_000,
                11_000_000,
                4.25,
            ),
            HealthPublication.distance(rideId, edge(4_250_000, 10_000_000, 11_000_000)),
        )
        assertNull(HealthPublication.distance(rideId, edge(0, 10_000_000, 11_000_000)))
        assertNull(HealthPublication.distance(rideId, edge(1_000_000, 10_000_000, 10_000_000)))
    }

    @Test
    fun theWorkoutCarriesItsLapsAndPausesInUtc() {
        val start = 1_788_706_200_000_000L
        val events =
            listOf(
                RideEvent(EventId("s"), RideEventKind.start, start, 0),
                RideEvent(EventId("l"), RideEventKind.lap, start + 300_000_000, 300_000_000),
                RideEvent(EventId("p"), RideEventKind.pause, start + 600_000_000, 600_000_000),
                RideEvent(EventId("r"), RideEventKind.resume, start + 900_000_000, 900_000_000),
                RideEvent(EventId("q"), RideEventKind.pause, start + 1_500_000_000, 1_500_000_000),
                RideEvent(EventId("e"), RideEventKind.stop, start + 1_800_000_000, 1_800_000_000),
            )
        val options =
            RideOptions(SampleRate.hz2, true, false, true, false, DistanceSource.controller, BatteryMethod.controller)
        val end = RideTiming(start + 1_800_000_000, 1_800_000_000, 1_200_000_000)
        val ride = Ride(rideId, Device.androidPhone, false, start, options, RidePhase.completed, false, end, end)
        val workout = HealthPublication.workout(ride, ActiveTimeline(1_800_000_000, events))
        assertEquals("powerlog:${rideId.value}:workout", workout.syncId)
        assertEquals(rideId.value, workout.rideId)
        assertEquals(start to start + 1_800_000_000, workout.startUtcUs to workout.endUtcUs)
        assertTrue(workout.indoor)
        assertEquals(
            listOf(UtcRange(start, start + 300_000_000), UtcRange(start + 300_000_000, start + 1_800_000_000)),
            workout.laps,
        )
        assertEquals(
            listOf(
                UtcRange(start + 600_000_000, start + 900_000_000),
                UtcRange(start + 1_500_000_000, start + 1_800_000_000),
            ),
            workout.pauses,
        )
        val route = listOf(RoutePoint(start + 1_000_000, 40.0, -3.5, null, 4.0, null, null, null))
        assertEquals(
            HealthConnectRecordPlan.Session(workout, route),
            HealthPublication.healthConnect(ride, ActiveTimeline(1_800_000_000, events), route),
        )
        val begin =
            HealthPublication.workout(
                ride.copy(phase = RidePhase.preparing, end = null),
                ActiveTimeline(0, emptyList()),
            )
        assertEquals(null, begin.endUtcUs)
        assertEquals(emptyList(), begin.laps)
        assertEquals(emptyList(), begin.pauses)
    }
}
