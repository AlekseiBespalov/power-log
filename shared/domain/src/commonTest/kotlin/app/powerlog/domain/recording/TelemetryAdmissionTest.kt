package app.powerlog.domain.recording

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryAdmissionTest {
    private val rideId = RideId("00000000-0000-4000-8000-000000000001")
    private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")
    private val capabilities = ControllerCapabilities(setOf(MetricId.humanPowerW), true)
    private val one = ControllerSession(rideId, 1, identity, capabilities)
    private val two = ControllerSession(rideId, 2, identity, capabilities)
    private val empty = TelemetryCursor(0, null, 0, null)
    private val options =
        RideOptions(SampleRate.hz8, true, false, false, false, DistanceSource.controller, BatteryMethod.controller)

    private fun at(us: Long): ClockReading = ClockReading(100_000_000 + us, 1_788_706_200_000_000 + us)

    private val preparing =
        assertIs<LifecycleDecision.Accepted>(
                RideLifecycle.decide(
                    LifecycleState(null, null),
                    RideCommand.Start(
                        EventId("start"),
                        rideId,
                        Device.iphone,
                        StartContext(false, false, true, true, options, false, false, false, false, false, false),
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
                    RideCommand.Control(EventId("$action"), rideId, action, CommandIssuer.rider),
                    at(us),
                    null,
                )
            )
            .next

    private val power = mapOf(MetricId.humanPowerW to 250.0, MetricId.controllerSpeedMps to 7.0)

    private fun admitted(decision: TelemetryDecision) = assertIs<TelemetryDecision.Admitted>(decision)

    @Test
    fun aRideOnlyAdmitsWhileRunning() {
        val paused = control(running, RideAction.pause, 5_000_000)
        for (state in listOf(preparing, paused)) assertEquals(
            TelemetryDecision.Dropped(DropReason.notRunning),
            TelemetryAdmission.admit(power, one, at(6_000_000), state, empty),
        )
    }

    @Test
    fun theFirstReadingOpensSeqAndRunAtOne() {
        val decision = admitted(TelemetryAdmission.admit(power, one, at(3_000_000), running, empty))
        val sample = decision.sample
        assertEquals(1, sample.seq)
        assertEquals(1, sample.run)
        assertEquals(3_000_000, sample.elapsedUs)
        assertEquals(at(3_000_000).utcUs, sample.utcUs)
        assertEquals(1, sample.activeInterval)
        assertEquals(mapOf(MetricId.humanPowerW to 250_000L), sample.quanta)
        assertEquals(TelemetryCursor(1, at(3_000_000).monoUs, 1, RunKey(1, 1)), decision.next)
    }

    @Test
    fun aReadingOlderThanTheLastIsDropped() {
        val cursor = TelemetryCursor(4, at(10_000_000).monoUs, 1, RunKey(1, 1))
        assertEquals(
            TelemetryDecision.Dropped(DropReason.older),
            TelemetryAdmission.admit(power, one, at(9_000_000), running, cursor),
        )
    }

    @Test
    fun aNewControllerSessionRestartsSeqAndRaisesRun() {
        val cursor = TelemetryCursor(40, at(10_000_000).monoUs, 1, RunKey(1, 1))
        val sample = admitted(TelemetryAdmission.admit(power, two, at(11_000_000), running, cursor)).sample
        assertEquals(1, sample.seq)
        assertEquals(2, sample.run)
        assertEquals(41, admitted(TelemetryAdmission.admit(power, one, at(11_000_000), running, cursor)).sample.seq)
    }

    @Test
    fun aResumeRaisesRunAndInterval() {
        val first = admitted(TelemetryAdmission.admit(power, one, at(3_000_000), running, empty))
        val resumed = control(control(running, RideAction.pause, 4_000_000), RideAction.resume, 8_000_000)
        val sample = admitted(TelemetryAdmission.admit(power, one, at(9_000_000), resumed, first.next)).sample
        assertEquals(2, sample.run)
        assertEquals(2, sample.activeInterval)
        assertEquals(2, sample.seq)
    }

    @Test
    fun aValueOutOfRangeIsStoredAsRead() {
        val sample =
            admitted(
                TelemetryAdmission.admit(mapOf(MetricId.humanPowerW to 40_000.0), one, at(2_000_000), running, empty)
            )
        assertEquals(40_000_000L, sample.sample.quanta[MetricId.humanPowerW])
        assertEquals(
            TelemetryDecision.Dropped(DropReason.invalid),
            TelemetryAdmission.admit(mapOf(MetricId.humanPowerW to Double.NaN), one, at(2_000_000), running, empty),
        )
    }

    @Test
    fun thePreviewCountsFromItsOpeningWithNoInterval() {
        val previewId = RideId("00000000-0000-4000-8000-0000000000aa")
        val session = ControllerSession(previewId, 1, identity, capabilities)
        val sample =
            admitted(TelemetryAdmission.preview(power, session, ClockReading(103_000_000, 5), 100_000_000, empty))
                .sample
        assertEquals(3_000_000, sample.elapsedUs)
        assertEquals(null, sample.activeInterval)
        assertEquals(previewId, sample.rideId)
    }

    @Test
    fun theStartWindowEndsFifteenSecondsOn() {
        assertEquals(115_000_000L, StartPolicy.deadline(100_000_000))
        assertEquals(false, StartPolicy.expired(115_000_000, 114_999_999))
        assertEquals(true, StartPolicy.expired(115_000_000, 115_000_000))
        assertEquals(FailureCode.startUnconfirmed.retryable, true)
    }
}
