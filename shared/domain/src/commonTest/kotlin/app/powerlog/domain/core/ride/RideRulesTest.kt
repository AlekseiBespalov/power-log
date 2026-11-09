package app.powerlog.domain.core.ride

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRulesTest {
    private val settings =
        RideSettings(
            sampleRate = SampleRate.hz8,
            distance = DistanceSelection.auto,
            battery = BatteryMethod.calculated,
            indoor = false,
            useWatch = false,
            saveToHealth = true,
            recordGps = null,
        )
    private val phone =
        DeviceCapabilities(
            recording = true,
            bluetooth = true,
            gps = true,
            health = HealthProviderKind.appleHealth,
            watch = true,
            diagnosticLog = true,
        )

    private fun ride(phase: RidePhase): Ride =
        Ride(
            id = RideId("00000000-0000-4000-8000-000000000001"),
            recordedBy = Device.iphone,
            example = false,
            startedUtcUs = 0,
            options =
                RideOptions(
                    SampleRate.hz2,
                    true,
                    false,
                    false,
                    false,
                    DistanceSource.controller,
                    BatteryMethod.controller,
                ),
            phase = phase,
            interrupted = false,
            checkpoint = RideTiming(0, 0, 0),
            end = null,
        )

    @Test
    fun anOutdoorRideRecordsGpsAndMeasuresDistanceByIt() {
        val options = RideOptionsRules.resolve(settings, StartRequest(false, false, true, null), phone)
        assertEquals(
            RideOptions(SampleRate.hz8, false, false, true, true, DistanceSource.phoneGps, BatteryMethod.calculated),
            options,
        )
    }

    @Test
    fun anIndoorRideFollowsTheController() {
        val options = RideOptionsRules.resolve(settings, StartRequest(true, false, false, null), phone)
        assertEquals(
            RideOptions(SampleRate.hz8, true, false, false, false, DistanceSource.controller, BatteryMethod.calculated),
            options,
        )
        val gps = RideOptionsRules.resolve(settings, StartRequest(true, false, false, true), phone)
        assertEquals(DistanceSource.controller, gps?.distanceSource)
        assertTrue(gps!!.recordGps)
    }

    @Test
    fun phoneGpsWithoutGpsIsRefused() {
        val gps = settings.copy(distance = DistanceSelection.phoneGps)
        assertNull(RideOptionsRules.resolve(gps, StartRequest(true, false, false, null), phone))
        assertNull(RideOptionsRules.resolve(gps, StartRequest(false, false, false, null), phone.copy(gps = false)))
        assertEquals(
            DistanceSource.phoneGps,
            RideOptionsRules.resolve(gps, StartRequest(false, false, false, null), phone)?.distanceSource,
        )
        val controller = settings.copy(distance = DistanceSelection.controller)
        assertEquals(
            DistanceSource.controller,
            RideOptionsRules.resolve(controller, StartRequest(false, false, false, null), phone)?.distanceSource,
        )
    }

    @Test
    fun aDeviceThatCantSaveRecordsWithSaveToHealthOff() {
        val web = phone.copy(health = null, watch = false)
        val options = RideOptionsRules.resolve(settings, StartRequest(false, true, true, null), web)!!
        assertFalse(options.useWatch)
        assertFalse(options.saveToHealth)
        val watch =
            RideOptionsRules.resolve(settings, StartRequest(false, true, true, null), phone.copy(health = null))!!
        assertTrue(watch.useWatch)
        assertTrue(watch.saveToHealth)
    }

    @Test
    fun onlyACompletedRideExports() {
        RidePhase.entries.forEach { assertEquals(it == RidePhase.completed, RideRules.exportReady(ride(it)), it.name) }
    }

    @Test
    fun anOpenOrExportingRideCantBeDeleted() {
        for (phase in
            listOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)) assertEquals(
            Availability(false, FailureCode.rideInProgress),
            RideRules.deletable(ride(phase), emptySet()),
        )
        val completed = ride(RidePhase.completed)
        assertEquals(Availability(true, null), RideRules.deletable(completed, emptySet()))
        assertEquals(Availability(true, null), RideRules.deletable(ride(RidePhase.failed), emptySet()))
        assertEquals(
            Availability(false, FailureCode.exportInProgress),
            RideRules.deletable(completed, setOf(completed.id)),
        )
    }
}
