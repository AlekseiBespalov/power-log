package app.powerlog.domain.recording

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationAdmissionTest {
    private val rideId = RideId("00000000-0000-4000-8000-000000000001")
    private val options =
        RideOptions(SampleRate.hz2, false, false, false, true, DistanceSource.phoneGps, BatteryMethod.controller)
    private val start = GpsClock(null, null, null, false, 0, 0)
    private val monoBase = 100_000_000L
    private val utcBase = 1_788_706_200_000_000L

    private fun at(us: Long, utcShiftUs: Long = 0): ClockReading =
        ClockReading(monoBase + us, utcBase + us + utcShiftUs)

    private val preparing =
        assertIs<LifecycleDecision.Accepted>(
                RideLifecycle.decide(
                    LifecycleState(null, null),
                    RideCommand.Start(
                        EventId("start"),
                        rideId,
                        Device.androidPhone,
                        StartContext(false, false, true, true, options, false, false, false, false, false),
                    ),
                    at(0),
                    null,
                )
            )
            .next

    private val running =
        assertIs<LifecycleDecision.Accepted>(
                RideLifecycle.observe(preparing, RideSignal.StartConfirmed(rideId), at(1_000_000))
            )
            .next

    private fun control(state: LifecycleState, action: RideAction, us: Long): LifecycleState =
        assertIs<LifecycleDecision.Accepted>(
                RideLifecycle.decide(
                    state,
                    RideCommand.Control(EventId("$action$us"), rideId, action, CommandIssuer.rider),
                    at(us),
                    null,
                )
            )
            .next

    private fun original(
        received: ClockReading,
        ageUs: Long,
        android: Boolean = true,
        latitude: Double = 40.0,
    ): LocationOriginal =
        LocationOriginal(
            utcUs = received.utcUs - ageUs,
            acquiredMonoUs = if (android) received.monoUs - ageUs else null,
            latitude = latitude,
            longitude = -3.5,
            horizontalAccuracyM = 5.0,
            altitudeM = 600.0,
            altitudeDatum = AltitudeDatum.msl,
            verticalAccuracyM = 3.0,
            mslM = 600.0,
            mslVerticalAccuracyM = 3.0,
            speedMps = 6.0,
            speedAccuracyMps = 0.5,
            courseDeg = 90.0,
            courseAccuracyDeg = 5.0,
        )

    private fun admit(
        received: ClockReading,
        ageUs: Long,
        clock: GpsClock,
        state: LifecycleState = running,
        android: Boolean = true,
        latitude: Double = 40.0,
    ): LocationDecision = LocationAdmission.admit(original(received, ageUs, android, latitude), received, state, clock)

    private fun admitted(decision: LocationDecision) = assertIs<LocationDecision.Admitted>(decision)

    private fun dropped(decision: LocationDecision) = assertIs<LocationDecision.Dropped>(decision).reason

    @Test
    fun onlyARunningRideAdmitsFixes() {
        assertEquals(DropReason.notRunning, dropped(admit(at(500_000), 0, start, preparing)))
        val paused = control(running, RideAction.pause, 5_000_000)
        assertEquals(DropReason.notRunning, dropped(admit(at(6_000_000), 0, start, paused)))
    }

    @Test
    fun anAndroidFixIsPlacedAtItsAcquisitionWithItsOwnUtcTime() {
        val received = at(10_000_000)
        val first = admitted(admit(received, 3_000_000, start))
        assertEquals(7_000_000, first.fix.elapsedUs)
        assertEquals(received.utcUs - 3_000_000, first.fix.utcUs)
        assertEquals(1, first.fix.seq)
        assertEquals(1, first.fix.run)
        assertEquals(1, first.fix.activeInterval)
        assertFalse(first.fix.barrier)
        val second = admitted(admit(at(11_000_000), 0, first.next))
        assertEquals(2, second.fix.seq)
        assertEquals(1, second.fix.run)
        assertEquals(11_000_000, second.fix.elapsedUs)
    }

    @Test
    fun oldFutureAndPreStartFixesAreDroppedAndMakeTheNextABarrier() {
        assertEquals(DropReason.tooOld, dropped(admit(at(30_000_000), 16_000_000, start)))
        assertEquals(DropReason.future, dropped(admit(at(30_000_000), -1_000_000, start)))
        val beforeStart = admit(at(2_000_000), 3_000_000, start)
        assertEquals(DropReason.tooOld, dropped(beforeStart))
        assertTrue(beforeStart.next().barrierPending)
        val next = admitted(admit(at(3_000_000), 0, beforeStart.next()))
        assertTrue(next.fix.barrier)
        assertFalse(next.next.barrierPending)
        assertEquals(15_000_000, admitted(admit(at(30_000_000), 15_000_000, start)).fix.elapsedUs)
    }

    @Test
    fun aFixAcquiredInThePauseBeforeAResumeHasNoInterval() {
        val resumed = control(control(running, RideAction.pause, 30_000_000), RideAction.resume, 60_000_000)
        val fix = admitted(admit(at(61_000_000), 3_000_000, start, resumed)).fix
        assertEquals(58_000_000, fix.elapsedUs)
        assertNull(fix.activeInterval)
        assertEquals(2, admitted(admit(at(61_000_000), 0, start, resumed)).fix.activeInterval)
    }

    @Test
    fun anInvalidPositionIsDroppedAndTheNextFixStartsABarrierRun() {
        val first = admitted(admit(at(5_000_000), 0, start))
        val invalid = admit(at(6_000_000), 0, first.next, latitude = 91.0)
        assertEquals(DropReason.invalid, dropped(invalid))
        val next = admitted(admit(at(7_000_000), 0, invalid.next()))
        assertTrue(next.fix.barrier)
        assertEquals(2, next.fix.run)
    }

    @Test
    fun aBarrierFixIsARunOfItsOwn() {
        val first = admitted(admit(at(5_000_000), 0, start))
        val barrier = admitted(admit(at(6_000_000), 0, first.next.copy(barrierPending = true)))
        val third = admitted(admit(at(7_000_000), 0, barrier.next))
        assertEquals(listOf(1, 2, 3), listOf(first.fix.run, barrier.fix.run, third.fix.run))
        assertEquals(listOf(false, true, false), listOf(first.fix.barrier, barrier.fix.barrier, third.fix.barrier))
    }

    @Test
    fun aResumeStartsANewRun() {
        val first = admitted(admit(at(5_000_000), 0, start))
        val resumed = control(control(running, RideAction.pause, 10_000_000), RideAction.resume, 20_000_000)
        val next = admitted(admit(at(21_000_000), 0, first.next, resumed))
        assertEquals(2, next.fix.activeInterval)
        assertEquals(2, next.fix.run)
    }

    @Test
    fun aUtcJumpAgainstTheMonotonicClockMakesTheNextFifteenSecondsBarriers() {
        var clock = admitted(admit(at(5_000_000), 0, start)).next
        val barriers = mutableListOf<Boolean>()
        for (second in 6..25) {
            val received = at(second * 1_000_000L, 2_000_000)
            val decision = admitted(admit(received, 0, clock))
            barriers += decision.fix.barrier
            clock = decision.next
        }
        assertEquals(List(16) { true } + List(4) { false }, barriers)
    }

    @Test
    fun aUtcStepBackOnTheIphoneKeepsAcquisitionOrderAndMarksTheFirstFifteenSeconds() {
        var clock = admitted(admit(at(5_000_000), 0, start, android = false)).next
        val decisions =
            (6..65).map { second ->
                val decision = admitted(admit(at(second * 1_000_000L, -60_000_000), 500_000, clock, android = false))
                clock = decision.next
                decision.fix
            }
        assertEquals((2L..61L).toList(), decisions.map { it.seq })
        assertEquals(decisions.map { it.elapsedUs }.sorted(), decisions.map { it.elapsedUs })
        assertEquals(List(16) { true } + List(44) { false }, decisions.map { it.barrier })
    }

    @Test
    fun aFixNoLaterThanTheLastAcquisitionIsOutOfOrder() {
        val first = admitted(admit(at(10_000_000), 0, start))
        assertEquals(DropReason.outOfOrder, dropped(admit(at(11_000_000), 999_999, first.next)))
        assertEquals(DropReason.outOfOrder, dropped(admit(at(11_000_000), 1_000_000, first.next)))
        assertEquals(2, admitted(admit(at(11_000_000), 999_998, first.next)).fix.seq)
    }

    private fun LocationDecision.next(): GpsClock =
        when (this) {
            is LocationDecision.Admitted -> next
            is LocationDecision.Dropped -> next
        }
}
