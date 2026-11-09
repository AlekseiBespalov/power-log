package app.powerlog.domain.health

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.Utc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HealthSamplesTest {
    private fun at(time: String): Long = requireNotNull(Utc.parseUs("2026-09-06T${time}Z"))

    private fun ride(end: String?): Ride =
        Ride(
            id = RideId("00000000-0000-4000-8000-000000000001"),
            recordedBy = Device.iphone,
            example = false,
            startedUtcUs = at("10:00:00"),
            options =
                RideOptions(
                    SampleRate.hz2,
                    indoor = false,
                    useWatch = true,
                    saveToHealth = true,
                    recordGps = true,
                    distanceSource = DistanceSource.phoneGps,
                    batteryMethod = BatteryMethod.controller,
                ),
            phase = if (end == null) RidePhase.running else RidePhase.completed,
            interrupted = false,
            checkpoint = RideTiming(at("10:00:00"), 0, 0),
            end = end?.let { RideTiming(at(it), at(it) - at("10:00:00"), at(it) - at("10:00:00")) },
        )

    private val heartRate = HealthMetric("heartRateBpm")

    @Test
    fun aSampleEndsInsideTheRideBothBoundsIncluded() {
        val ride = ride("10:30:00")
        assertTrue(HealthSamples.inRide(ride, heartRate, at("10:29:59"), at("10:30:00")))
        assertFalse(HealthSamples.inRide(ride, heartRate, at("10:30:00"), at("10:30:01")))
        assertTrue(HealthSamples.inRide(ride, heartRate, at("09:59:59"), at("10:00:00")))
        assertFalse(HealthSamples.inRide(ride, heartRate, at("09:59:58"), at("09:59:59")))
        assertTrue(HealthSamples.inRide(ride, HealthMetric("heartRate"), at("10:10:00"), at("10:10:00")))
    }

    @Test
    fun anOpenRideHasNoUpperBound() {
        assertTrue(HealthSamples.inRide(ride(null), heartRate, at("11:00:00"), at("11:00:00")))
    }

    @Test
    fun energyOverlapsTheRide() {
        val ride = ride("10:30:00")
        for (metric in listOf("activeEnergyKcal", "basalEnergyKcal", "activeEnergy").map(::HealthMetric)) {
            assertTrue(HealthSamples.inRide(ride, metric, at("09:59:00"), at("10:01:00")), metric.wire)
            assertFalse(HealthSamples.inRide(ride, metric, at("09:58:00"), at("10:00:00")), metric.wire)
            assertTrue(HealthSamples.inRide(ride, metric, at("10:29:59"), at("10:31:00")), metric.wire)
            assertFalse(HealthSamples.inRide(ride, metric, at("10:30:00"), at("10:31:00")), metric.wire)
            assertFalse(HealthSamples.inRide(ride, metric, at("10:10:00"), at("10:10:00")), metric.wire)
        }
    }

    @Test
    fun aPlainSampleIsKeyedByItsIdAndASeriesMemberByItsHashedContent() {
        val id = "AAAAAAAA-0000-4000-8000-000000000001"
        assertEquals(id.lowercase(), HealthSamples.sampleKey(id, null, at("10:00:00"), at("10:00:01"), 72.0))
        assertEquals(id.lowercase(), HealthSamples.sampleKey(id, 3, at("10:00:00"), at("10:00:01"), null))
        val member = HealthSamples.sampleKey(id, 0, 1_788_706_200_000_000, 1_788_706_201_500_000, 72.0)
        assertEquals("8d70bbae-fd87-d4d3-611a-eb44c3a8fcbf", member)
        assertEquals(
            member,
            HealthSamples.sampleKey(id.lowercase(), 1, 1_788_706_200_000_000, 1_788_706_201_500_000, 72.0),
        )
        assertNotEquals(member, HealthSamples.sampleKey(id, 0, 1_788_706_200_000_000, 1_788_706_201_500_000, 73.0))
    }

    @Test
    fun aSampleIsPlacedAtItsEndOrAnIntervalRecordAtItsStart() {
        val ride = ride("10:30:00")
        val events =
            listOf(
                RideEvent(EventId("s"), RideEventKind.start, at("10:00:00"), 0),
                RideEvent(EventId("p"), RideEventKind.pause, at("10:10:00"), 600_000_000),
                RideEvent(EventId("r"), RideEventKind.resume, at("10:20:00"), 1_200_000_000),
                RideEvent(EventId("e"), RideEventKind.stop, at("10:30:00"), 1_800_000_000),
            )
        val timeline = ActiveTimeline(1_800_000_000, events)
        assertEquals(
            SamplePlace(at("10:05:00"), 300_000_000, 1),
            HealthSamples.place(ride, timeline, at("10:04:59"), at("10:05:00"), HealthRepresentation.rawQuantity),
        )
        assertEquals(
            SamplePlace(at("10:15:00"), 900_000_000, null),
            HealthSamples.place(ride, timeline, null, at("10:15:00"), HealthRepresentation.rawSeries),
        )
        assertEquals(
            SamplePlace(at("09:59:00"), -60_000_000, null),
            HealthSamples.place(ride, timeline, at("09:59:00"), at("10:01:00"), HealthRepresentation.intervalRecord),
        )
        assertEquals(
            SamplePlace(at("10:21:00"), 1_260_000_000, 2),
            HealthSamples.place(ride, timeline, at("10:21:00"), at("10:22:00"), HealthRepresentation.intervalRecord),
        )
    }
}
