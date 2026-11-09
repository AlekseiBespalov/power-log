package app.powerlog.domain.exports

import app.powerlog.domain.core.analysis.StorableRide
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitPlansTest {
    private val rideId = RideId("5a1e0001-0000-4000-8000-0000000000cc")
    private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
    private val sessions =
        mapOf(
            1 to
                ControllerSession(
                    rideId,
                    1,
                    identity,
                    requireNotNull(ControllerDecoders.of(identity.decoder)).capabilities(identity),
                )
        )

    private fun ride(endUs: Long, useWatch: Boolean = false, recordedBy: Device = Device.iphone) =
        Ride(
            rideId,
            recordedBy,
            false,
            START_UTC_US,
            RideOptions(
                SampleRate.hz8,
                false,
                useWatch,
                true,
                false,
                DistanceSource.controller,
                BatteryMethod.controller,
            ),
            RidePhase.completed,
            false,
            RideTiming(START_UTC_US + endUs, endUs, endUs),
            RideTiming(START_UTC_US + endUs, endUs, endUs),
        )

    private fun events(endUs: Long) =
        listOf(
            RideEvent(EventId("00000000-0000-4000-8000-000000000001"), RideEventKind.start, START_UTC_US, 0),
            RideEvent(EventId("00000000-0000-4000-8000-000000000002"), RideEventKind.stop, START_UTC_US + endUs, endUs),
        )

    private fun seconds(count: Int, interval: Int = 1) =
        TelemetryRows.of(
            List(count) {
                val atUs = it * SECOND_US
                TelemetrySample(
                    rideId,
                    1,
                    it + 1L,
                    atUs,
                    START_UTC_US + atUs,
                    interval,
                    1,
                    mapOf(MetricId.humanPowerW to 150_000L),
                )
            }
        )

    @Test
    fun aRideWithinTheRecordLimitNeedsNoPlanAndRecordsEverySecond() {
        val ride = StorableRide("android-controller-distance")
        val plan =
            FitRecordPlan(
                ride.ride,
                ActiveTimeline(requireNotNull(ride.ride.end).elapsedUs, ride.events),
                FitPowerSource.rider,
                null,
            )
        assertFalse(plan.needed)
        assertEquals(RecordInterval.Planned(1), plan.result())
    }

    @Test
    fun aLongRideTakesTheFirstIntervalWhoseRecordsFit() {
        val endUs = 120_000 * SECOND_US
        val plan = FitRecordPlan(ride(endUs), ActiveTimeline(endUs, events(endUs)), FitPowerSource.rider, null)
        assertTrue(plan.needed)
        val rows = seconds(120_000)
        plan.append(rows, 0, rows.rows, sessions)
        assertEquals(RecordInterval.Planned(2), plan.result())
    }

    @Test
    fun aLongRideWithoutAWitnessHasNoRecords() {
        val endUs = 120_000 * SECOND_US
        val plan = FitRecordPlan(ride(endUs), ActiveTimeline(endUs, events(endUs)), FitPowerSource.rider, null)
        val rows = seconds(1_000, interval = 0)
        plan.append(rows, 0, rows.rows, sessions)
        assertEquals(RecordInterval.Refused(FailureCode.exportNoRecords), plan.result())
    }

    @Test
    fun tooManyBreaksOrNoFittingIntervalIsTheExportLimit() {
        fun witnesses(seconds: List<Long>, interval: (Int) -> Int): WitnessSeconds {
            val witnesses = WitnessSeconds()
            seconds.forEachIndexed { index, second ->
                witnesses.witness(Witness(0, second * SECOND_US, second, interval(index), 1, DoubleArray(0)))
            }
            return witnesses
        }
        assertEquals(RecordInterval.Planned(2), witnesses((0L until 10).toList()) { 1 }.plan(0, 5, 3))
        assertEquals(
            RecordInterval.Refused(FailureCode.exportLimit),
            witnesses((0L until 10).toList()) { it }.plan(0, 5, 3),
        )
        assertEquals(
            RecordInterval.Refused(FailureCode.exportLimit),
            witnesses((0L until 40).toList()) { 1 }.plan(0, 5, 3),
        )
    }

    @Test
    fun theHeartRatePlanTakesTheOwnersRawSamplesFromTheBestCoveredOrigin() {
        val endUs = 600 * SECOND_US
        val planner = FitHealthPlanner(ride(endUs, useWatch = true))
        (0 until 60).forEach {
            planner.append(heartRate(Producer.phone, "phone.app", HealthRepresentation.rawSeries, it * 10L))
        }
        (0 until 10).forEach {
            planner.append(heartRate(Producer.watch, "watch.short", HealthRepresentation.rawSeries, it * 5L))
        }
        (0 until 60).forEach {
            planner.append(heartRate(Producer.watch, "watch.long", HealthRepresentation.rawSeries, it * 5L))
        }
        (0 until 60).forEach {
            planner.append(heartRate(Producer.watch, "watch.long", HealthRepresentation.builderMostRecent, it * 5L))
        }
        assertEquals(HeartRateSource(Producer.watch, HeartRateKind.sample, "watch.long"), planner.result().heartRate)
    }

    @Test
    fun withOnlyLatestSamplesTheHeartRatePlanTakesThem() {
        val planner = FitHealthPlanner(ride(600 * SECOND_US))
        (0 until 10).forEach {
            planner.append(heartRate(Producer.phone, "", HealthRepresentation.builderMostRecent, it * 5L))
        }
        val source = requireNotNull(planner.result().heartRate)
        assertEquals(HeartRateSource(Producer.phone, HeartRateKind.latest, null), source)
        assertTrue(source.matches(heartRate(Producer.phone, "", HealthRepresentation.builderMostRecent, 5)))
        assertFalse(source.matches(heartRate(Producer.phone, "", HealthRepresentation.rawSeries, 5)))
    }

    @Test
    fun caloriesAreTheLatestFinalTotalElseTheClippedIntervalsElseTheLargestSnapshot() {
        val endUs = 600 * SECOND_US
        fun calories(vararg samples: HealthSample): Double? {
            val planner = FitHealthPlanner(ride(endUs))
            samples.forEach(planner::append)
            return planner.result().caloriesKcal
        }
        val snapshots =
            arrayOf(
                energy(HealthRepresentation.cumulativeWorkoutTotal, 10.0, 100),
                energy(HealthRepresentation.cumulativeWorkoutTotal, 30.0, 200),
            )
        assertEquals(30.0, calories(*snapshots))
        val interval = energy(HealthRepresentation.intervalRecord, 20.0, 700, startS = 500)
        assertEquals(10.0, calories(*snapshots, interval))
        assertEquals(42.0, calories(*snapshots, interval, energy(HealthRepresentation.finalWorkoutTotal, 42.0, 600)))
        assertNull(calories())
    }

    @Test
    fun anIntervalRecordStartingBeforeTheRideCountsTheShareInsideIt() {
        val planner = FitHealthPlanner(ride(10 * SECOND_US))
        planner.append(energy(HealthRepresentation.intervalRecord, 20.0, 15, startS = -5, placedS = -5))
        assertEquals(10.0, planner.result().caloriesKcal)
    }

    private fun heartRate(producer: Producer, origin: String, representation: HealthRepresentation, atS: Long) =
        HealthSample(
            rideId,
            producer,
            "$producer-$origin-$representation-$atS",
            origin,
            null,
            null,
            HealthMetric("heartRateBpm"),
            representation,
            120.0,
            null,
            START_UTC_US + atS * SECOND_US,
            atS * SECOND_US,
            null,
            null,
            1,
        )

    private fun energy(
        representation: HealthRepresentation,
        kcal: Double,
        atS: Long,
        startS: Long = 0,
        placedS: Long = atS,
    ) =
        HealthSample(
            rideId,
            Producer.phone,
            "energy-$representation-$atS",
            "app.powerlog.tests",
            null,
            null,
            HealthMetric("activeEnergyKcal"),
            representation,
            kcal,
            null,
            START_UTC_US + placedS * SECOND_US,
            placedS * SECOND_US,
            START_UTC_US + startS * SECOND_US,
            START_UTC_US + atS * SECOND_US,
            1,
        )

    private companion object {
        const val SECOND_US = 1_000_000L
        const val START_UTC_US = 1_772_355_600_000_000L
    }
}
