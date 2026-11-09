package app.powerlog.domain.recording

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.core.time.RideTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideLifecycleTest {
    private val rideId = RideId("00000000-0000-4000-8000-000000000001")
    private val startEvent = EventId("00000000-0000-4000-8000-0000000000e1")
    private val startUtc = 1_788_706_200_000_000L
    private val options =
        RideOptions(SampleRate.hz8, true, false, false, false, DistanceSource.controller, BatteryMethod.controller)

    private fun at(seconds: Long): ClockReading =
        ClockReading(100_000_000 + seconds * 1_000_000, startUtc + seconds * 1_000_000)

    private fun context(
        options: RideOptions? = this.options,
        ownerForeign: Boolean = false,
        settingsSaveFailed: Boolean = false,
        settingsReadable: Boolean = true,
        bikeVerified: Boolean = true,
        watchInstalled: Boolean = true,
        fromWatch: Boolean = false,
        locationDenied: Boolean = false,
        healthSessionEnding: Boolean = false,
        healthWriteDenied: Boolean = false,
    ) =
        StartContext(
            ownerForeign,
            settingsSaveFailed,
            settingsReadable,
            bikeVerified,
            options,
            watchInstalled,
            fromWatch,
            locationDenied,
            healthSessionEnding,
            healthWriteDenied,
        )

    private val none = LifecycleState(null, null)

    private fun start(state: LifecycleState = none, context: StartContext = context()) =
        RideLifecycle.decide(state, RideCommand.Start(startEvent, rideId, Device.iphone, context), at(0), null)

    private fun accepted(decision: LifecycleDecision) = assertIs<LifecycleDecision.Accepted>(decision)

    private fun preparing(context: StartContext = context()) = accepted(start(context = context)).next

    private fun running() = accepted(RideLifecycle.observe(preparing(), RideSignal.StartConfirmed(rideId), at(2))).next

    private fun control(
        state: LifecycleState,
        action: RideAction,
        issuer: CommandIssuer = CommandIssuer.rider,
        s: Long = 10,
    ) =
        RideLifecycle.decide(
            state,
            RideCommand.Control(EventId("event-$action-$s"), rideId, action, issuer),
            at(s),
            null,
        )

    private fun paused() = accepted(control(running(), RideAction.pause)).next

    private fun interrupted() = accepted(RideLifecycle.observe(running(), RideSignal.ProcessRestarted, at(20))).next

    private val states: Map<String, () -> LifecycleState> =
        mapOf(
            "none" to { none },
            "preparing" to { preparing() },
            "running" to { running() },
            "paused" to { paused() },
            "interrupted" to { interrupted() },
        )

    @Test
    fun everyPhaseActionAndIssuerCellAnswersTheTable() {
        val moved = { transition: RideTransition -> transition }
        val table: Map<String, Map<RideAction, (CommandIssuer) -> Any>> =
            mapOf(
                "none" to RideAction.entries.associateWith { { _: CommandIssuer -> FailureCode.rideChanged } },
                "preparing" to
                    RideAction.entries.associateWith { action ->
                        { _: CommandIssuer ->
                            if (action == RideAction.discard) moved(RideTransition.discarded)
                            else FailureCode.actionPending
                        }
                    },
                "running" to
                    mapOf(
                        RideAction.pause to { _ -> RideTransition.paused },
                        RideAction.resume to { _ -> FailureCode.actionUnavailable },
                        RideAction.lap to { _ -> RideTransition.lapped },
                        RideAction.finish to { _ -> RideTransition.completed },
                        RideAction.discard to { _ -> RideTransition.discarded },
                    ),
                "paused" to
                    mapOf(
                        RideAction.pause to { _ -> FailureCode.actionUnavailable },
                        RideAction.resume to { _ -> RideTransition.resumed },
                        RideAction.lap to
                            { issuer ->
                                if (issuer == CommandIssuer.watch) RideTransition.lapped
                                else FailureCode.actionUnavailable
                            },
                        RideAction.finish to { _ -> RideTransition.completed },
                        RideAction.discard to { _ -> RideTransition.discarded },
                    ),
                "interrupted" to
                    mapOf(
                        RideAction.pause to { _ -> FailureCode.actionUnavailable },
                        RideAction.resume to { _ -> FailureCode.actionUnavailable },
                        RideAction.lap to { _ -> FailureCode.actionUnavailable },
                        RideAction.finish to { _ -> RideTransition.completed },
                        RideAction.discard to { _ -> RideTransition.discarded },
                    ),
            )
        for ((phase, cells) in table) for ((action, expected) in cells) for (issuer in CommandIssuer.entries) {
            val finish = FinishContext(null, emptyList())
            val decision =
                RideLifecycle.decide(
                    states.getValue(phase)(),
                    RideCommand.Control(EventId("e"), rideId, action, issuer),
                    at(30),
                    finish,
                )
            val answer =
                when (decision) {
                    is LifecycleDecision.Accepted -> decision.transition
                    is LifecycleDecision.Refused -> decision.code
                    LifecycleDecision.Unchanged -> LifecycleDecision.Unchanged
                }
            assertEquals(expected(issuer), answer, "$phase $action $issuer")
        }
    }

    @Test
    fun everySignalAnswersItsTransitionOrUnchanged() {
        val signals =
            listOf(
                RideSignal.StartConfirmed(rideId),
                RideSignal.StartFailed(rideId, FailureCode.healthDenied),
                RideSignal.StartTimedOut(rideId),
                RideSignal.ProcessRestarted,
                RideSignal.StorageFailed(rideId),
            )
        val expected =
            mapOf(
                "preparing" to
                    listOf(
                        RideTransition.started,
                        RideTransition.failed,
                        RideTransition.failed,
                        RideTransition.interruptedAtLaunch,
                        RideTransition.interruptedByStorage,
                    ),
                "running" to
                    listOf(null, null, null, RideTransition.interruptedAtLaunch, RideTransition.interruptedByStorage),
                "paused" to
                    listOf(null, null, null, RideTransition.interruptedAtLaunch, RideTransition.interruptedByStorage),
                "interrupted" to listOf(null, null, null, null, null),
                "none" to listOf(null, null, null, null, null),
            )
        for ((phase, transitions) in expected) signals.zip(transitions).forEach { (signal, transition) ->
            val decision = RideLifecycle.observe(states.getValue(phase)(), signal, at(30))
            if (transition == null) assertEquals(LifecycleDecision.Unchanged, decision, "$phase $signal")
            else assertEquals(transition, accepted(decision).transition, "$phase $signal")
        }
        val other = RideId("00000000-0000-4000-8000-000000000002")
        assertEquals(
            LifecycleDecision.Unchanged,
            RideLifecycle.observe(running(), RideSignal.StorageFailed(other), at(30)),
        )
    }

    @Test
    fun aStartWithEveryGuardClearPreparesTheRide() {
        val decision = accepted(start())
        assertEquals(RideTransition.prepared, decision.transition)
        assertEquals(emptyList(), decision.events)
        val ride = decision.saved!!
        assertEquals(RidePhase.preparing, ride.phase)
        assertEquals(startUtc, ride.startedUtcUs)
        assertEquals(options, ride.options)
        assertEquals(startEvent, decision.next.clock!!.startEvent)
        assertEquals(at(15).monoUs, decision.next.clock!!.startDeadlineMonoUs)
    }

    @Test
    fun aControlForAnotherRideIsRideChanged() {
        val other =
            RideCommand.Control(
                EventId("e"),
                RideId("00000000-0000-4000-8000-000000000009"),
                RideAction.pause,
                CommandIssuer.rider,
            )
        assertEquals(
            LifecycleDecision.Refused(FailureCode.rideChanged),
            RideLifecycle.decide(running(), other, at(5), null),
        )
    }

    @Test
    fun discardingAPreparingRideDeletesIt() {
        for (issuer in CommandIssuer.entries) {
            val decision = accepted(control(preparing(), RideAction.discard, issuer))
            assertEquals(RideTransition.discarded, decision.transition)
            assertEquals(rideId, decision.deleted)
            assertNull(decision.saved)
            assertNull(decision.next.open)
        }
    }

    @Test
    fun confirmingRunsTheRideFromTheStartEventAtElapsedZero() {
        val decision = accepted(RideLifecycle.observe(preparing(), RideSignal.StartConfirmed(rideId), at(2)))
        assertEquals(listOf(RideEvent(startEvent, RideEventKind.start, startUtc, 0)), decision.events)
        assertEquals(RidePhase.running, decision.saved!!.phase)
        assertEquals(RideTiming(at(2).utcUs, 2_000_000, 0), decision.saved!!.checkpoint)
        assertEquals(1, decision.next.clock!!.activeInterval)
        assertEquals(0L, decision.next.clock!!.intervalStartUs)
        assertEquals(RideTiming(at(7).utcUs, 7_000_000, 5_000_000), RideLifecycle.timing(decision.next, at(7)))
    }

    @Test
    fun aFailedStartKeepsTheRideAndAStorageFailureInterruptsIt() {
        val failed =
            accepted(RideLifecycle.observe(preparing(), RideSignal.StartFailed(rideId, FailureCode.storageFull), at(3)))
        assertEquals(RideTransition.failed, failed.transition)
        assertEquals(RidePhase.failed, failed.saved!!.phase)
        assertEquals(emptyList(), failed.events)
        val storage = accepted(RideLifecycle.observe(preparing(), RideSignal.StorageFailed(rideId), at(3)))
        assertEquals(RideTransition.interruptedByStorage, storage.transition)
        assertTrue(storage.saved!!.interrupted)
        assertEquals(RidePhase.interrupted, storage.saved!!.phase)
    }

    @Test
    fun aTimedOutStartFails() {
        val watch = options.copy(useWatch = true)
        val decision =
            accepted(RideLifecycle.observe(preparing(context(watch)), RideSignal.StartTimedOut(rideId), at(15)))
        assertEquals(RideTransition.failed, decision.transition)
        assertEquals(RidePhase.failed, decision.saved!!.phase)
    }

    @Test
    fun pausingFreezesTheTimerAndClosesTheInterval() {
        val decision = accepted(control(running(), RideAction.pause, CommandIssuer.watch, s = 12))
        assertEquals(RidePhase.paused, decision.saved!!.phase)
        assertEquals(
            listOf(RideEvent(EventId("event-pause-12"), RideEventKind.pause, at(12).utcUs, 12_000_000)),
            decision.events,
        )
        assertNull(decision.next.clock!!.activeInterval)
        assertNull(decision.next.clock!!.intervalStartUs)
        assertEquals(10_000_000, RideLifecycle.timing(decision.next, at(40)).timerUs)
    }

    @Test
    fun aRiderCantLapWhilePausedButTheWatchCan() {
        assertEquals(LifecycleDecision.Refused(FailureCode.actionUnavailable), control(paused(), RideAction.lap))
        assertEquals(RideTransition.lapped, accepted(control(paused(), RideAction.lap, CommandIssuer.watch)).transition)
    }

    @Test
    fun resumingOpensTheNextInterval() {
        val decision = accepted(control(paused(), RideAction.resume, s = 30))
        assertEquals(RidePhase.running, decision.saved!!.phase)
        assertEquals(2, decision.next.clock!!.activeInterval)
        assertEquals(30_000_000L, decision.next.clock!!.intervalStartUs)
        val again =
            accepted(
                control(accepted(control(decision.next, RideAction.pause, s = 40)).next, RideAction.resume, s = 50)
            )
        assertEquals(3, again.next.clock!!.activeInterval)
    }

    @Test
    fun anInterruptedRideFinishesAtItsBoundaryOrIsDeleted() {
        val events = listOf(RideEvent(startEvent, RideEventKind.start, startUtc, 0))
        val finish = FinishContext(RetainedEnd(startUtc + 17_000_000, 17_000_000), events)
        val decision =
            accepted(
                RideLifecycle.decide(
                    interrupted(),
                    RideCommand.Control(EventId("stop"), rideId, RideAction.finish, CommandIssuer.watch),
                    at(60),
                    finish,
                )
            )
        val end = RideTiming(startUtc + 17_000_000, 17_000_000, 17_000_000)
        assertEquals(end, decision.saved!!.end)
        assertEquals(end, decision.saved!!.checkpoint)
        assertEquals(listOf(RideEvent(EventId("stop"), RideEventKind.stop, end.utcUs, end.elapsedUs)), decision.events)
        assertEquals(
            RideTransition.discarded,
            accepted(control(interrupted(), RideAction.discard, CommandIssuer.watch)).transition,
        )
    }

    @Test
    fun anInterruptedRideOffersOnlyFinishAndDiscard() {
        for (action in listOf(RideAction.pause, RideAction.resume, RideAction.lap)) assertEquals(
            LifecycleDecision.Refused(FailureCode.actionUnavailable),
            control(interrupted(), action),
        )
    }

    @Test
    fun aRestartInterruptsARecordingRideAndKeepsItsCheckpoint() {
        for (state in listOf(running(), paused())) {
            val decision = accepted(RideLifecycle.observe(state, RideSignal.ProcessRestarted, at(90)))
            assertEquals(RideTransition.interruptedAtLaunch, decision.transition)
            assertTrue(decision.saved!!.interrupted)
            assertEquals(state.open!!.checkpoint, decision.saved!!.checkpoint)
            assertEquals(emptyList(), decision.events)
            assertNull(decision.next.clock)
            assertEquals(state.open!!.checkpoint, RideLifecycle.timing(decision.next, at(95)))
        }
    }

    @Test
    fun aRunningRideFinishesNow() {
        val decision = accepted(control(running(), RideAction.finish, s = 62))
        val end = RideTiming(at(62).utcUs, 62_000_000, 60_000_000)
        assertEquals(end, decision.saved!!.end)
        assertEquals(end, decision.saved!!.checkpoint)
        assertEquals(RidePhase.completed, decision.saved!!.phase)
        assertEquals(RideEventKind.stop, decision.events.single().kind)
        assertNull(decision.next.open)
    }

    @Test
    fun theStartGuardsRefuseInOrderAndAvailableAgrees() {
        val gpsRide = options.copy(recordGps = true)
        val phoneWriter = options.copy(saveToHealth = true)
        val watchRide = options.copy(useWatch = true, saveToHealth = true)
        val rows: List<Triple<LifecycleState, StartContext, FailureCode?>> =
            listOf(
                Triple(none, context(ownerForeign = true, settingsSaveFailed = true), FailureCode.ownerForeign),
                Triple(preparing(), context(settingsSaveFailed = true), FailureCode.actionPending),
                Triple(running(), context(settingsSaveFailed = true), FailureCode.rideActive),
                Triple(interrupted(), context(), FailureCode.rideActive),
                Triple(
                    none,
                    context(phoneWriter, healthSessionEnding = true, settingsSaveFailed = true),
                    FailureCode.actionPending,
                ),
                Triple(none, context(watchRide, healthSessionEnding = true), null),
                Triple(none, context(healthSessionEnding = true), null),
                Triple(
                    none,
                    context(settingsSaveFailed = true, settingsReadable = false),
                    FailureCode.settingsSaveFailed,
                ),
                Triple(none, context(settingsReadable = false, bikeVerified = false), FailureCode.settingsUnreadable),
                Triple(none, context(bikeVerified = false, options = null), FailureCode.bikeRequired),
                Triple(none, context(options = null), FailureCode.gpsRequired),
                Triple(none, context(watchRide, watchInstalled = false), FailureCode.watchNotInstalled),
                Triple(none, context(watchRide, watchInstalled = false, fromWatch = true), null),
                Triple(none, context(gpsRide, locationDenied = true), FailureCode.locationDenied),
                Triple(none, context(locationDenied = true), null),
                Triple(none, context(phoneWriter, healthWriteDenied = true), FailureCode.healthDenied),
                Triple(none, context(watchRide, healthWriteDenied = true), null),
            )
        rows.forEachIndexed { index, (state, context, code) ->
            val decision = start(state, context)
            if (code == null) assertIs<LifecycleDecision.Accepted>(decision, "row $index")
            else assertEquals(LifecycleDecision.Refused(code), decision, "row $index")
            assertEquals(Availability(code == null, code), RideLifecycle.available(state, context).start, "row $index")
        }
    }

    @Test
    fun anOwnerForeignTabHasEveryControlDisabled() {
        val allowed = RideLifecycle.available(running(), context(ownerForeign = true))
        val foreign = Availability(false, FailureCode.ownerForeign)
        assertEquals(RideAllowed(foreign, foreign, foreign, foreign, foreign, foreign), allowed)
    }

    @Test
    fun theRidersControlsFollowTheirCells() {
        val allowed = RideLifecycle.available(paused(), context())
        assertEquals(Availability(false, FailureCode.rideActive), allowed.start)
        assertEquals(Availability(false, FailureCode.actionUnavailable), allowed.pause)
        assertEquals(Availability(true, null), allowed.resume)
        assertEquals(Availability(false, FailureCode.actionUnavailable), allowed.lap)
        assertEquals(Availability(true, null), allowed.finish)
        assertEquals(Availability(true, null), allowed.discard)
        assertEquals(Availability(false, FailureCode.rideChanged), RideLifecycle.available(none, context()).finish)
    }

    @Test
    fun aRestoredRideHasNoClock() {
        val ride = interrupted().open!!
        assertEquals(LifecycleState(ride, null), RideLifecycle.restored(ride))
        assertEquals(none, RideLifecycle.restored(null))
        assertEquals(none, RideLifecycle.restored(ride.copy(phase = RidePhase.completed)))
    }
}
