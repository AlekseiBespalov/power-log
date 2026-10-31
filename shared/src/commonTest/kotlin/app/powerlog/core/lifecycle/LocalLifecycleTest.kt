package app.powerlog.core.lifecycle

import app.powerlog.core.presentation.*
import app.powerlog.core.protocol.*
import kotlin.test.*

class LocalLifecycleTest {
    private fun id(n: Int) = "aaaaaaaa-aaaa-4aaa-8aaa-${n.toString().padStart(12, '0')}"

    private fun clock(seconds: Int, epoch: Int = 99) =
        LifecycleClock(
            Timestamps.parse("2026-01-01T00:00:${seconds.toString().padStart(2, '0')}Z"),
            id(epoch),
            seconds * 1_000_000L,
        )

    private fun start(state: LifecycleState = LifecycleState(), watch: Boolean = false): LifecycleTransition =
        RideLifecycle.start(
            state,
            LifecycleStart(
                id(1),
                id(2),
                clock(0),
                RequestedRideOptions(useWatch = watch, saveToHealth = watch),
                PlatformCapabilities.iphone(true),
            ),
        )

    private fun receipt(state: LifecycleState, at: Int): LifecycleTransition {
        val intent = state.ride!!.pending ?: state.ride!!.terminal!!
        return RideLifecycle.receipt(state, LifecycleReceipt(intent.key, intent.commandId, intent.effect, clock(at)))
    }

    private fun running(watch: Boolean = false): LifecycleState =
        receipt(start(watch = watch).afterCommit(true).state, 0).afterCommit(true).state

    private fun action(state: LifecycleState, action: RideAction, at: Int): LifecycleTransition =
        RideLifecycle.request(state, LifecycleRequest(state.ride!!.key, id(at + 10), action, clock(at)))

    @Test
    fun effectsRequireCommittedIntentAndRejectSupersededGeneration() {
        val pending = start()
        assertEquals(LifecycleState(), pending.afterCommit(false).state)
        assertTrue(pending.afterCommit(false).effects.isEmpty())
        val committed = pending.afterCommit(true)
        assertTrue(RideLifecycle.canExecute(committed.state, committed.effects.single()))
        assertFalse(RideLifecycle.canExecute(RideLifecycle.fenced(committed.state), committed.effects.single()))
        val old = committed.state.ride!!.pending!!
        val fenced = RideLifecycle.fenced(committed.state)
        assertEquals(
            fenced,
            RideLifecycle.receipt(
                    fenced,
                    LifecycleReceipt(old.key, old.commandId, old.effect, clock(1)),
                )
                .afterCommit(true)
                .state,
        )
    }

    @Test
    fun watchHealthDoesNotOwnThePhoneArchiveOrPreventLocalFinish() {
        val state = running(watch = true)
        assertEquals(Role.phone, state.ride!!.key.owner)
        assertEquals("watch", state.ride!!.options.healthWriter)
        val finished = action(state, RideAction.stop, 10).afterCommit(true)
        assertEquals(LifecyclePhase.completed, finished.state.ride!!.phase)
        assertNull(finished.state.ride!!.pending)
        assertEquals("pending", finished.state.ride!!.healthOutcome)
        assertEquals(10.0, finished.state.ride!!.timing.elapsedSeconds)
        assertEquals(LifecycleEffectKind.stopOwner, finished.effects.single().intent.effect)
        assertNull(start(finished.state).rejection)
    }

    @Test
    fun pauseResumePreserveActiveTimeAndLapUsesItsRequestedTimestamp() {
        val state = running()
        val paused = receipt(action(state, RideAction.pause, 5).afterCommit(true).state, 5).afterCommit(true).state
        assertEquals(LifecyclePhase.paused, paused.ride!!.phase)
        assertEquals(5.0, RideLifecycle.timing(paused.ride!!, clock(10)).timerSeconds)
        val resumed = receipt(action(paused, RideAction.resume, 10).afterCommit(true).state, 10).afterCommit(true).state
        val lap = receipt(action(resumed, RideAction.lap, 12).afterCommit(true).state, 13)
        assertEquals(clock(12).timestamp, lap.mutation!!.events.single().timing.timestamp)
        val finished = action(lap.afterCommit(true).state, RideAction.stop, 15).afterCommit(true).state
        assertEquals(15.0, finished.ride!!.timing.elapsedSeconds)
        assertEquals(10.0, finished.ride!!.timing.timerSeconds)
    }

    @Test
    fun restartKeepsCommittedTimeAndCannotResumeOrReplayACommand() {
        val captured = RideLifecycle.checkpoint(running(), clock(8)).afterCommit(true).state
        val pending = action(captured, RideAction.pause, 10).afterCommit(true).state
        val restarted = RideLifecycle.restart(pending, clock(30, 100), id(44)).afterCommit(true)
        assertEquals(LifecyclePhase.interrupted, restarted.state.ride!!.phase)
        assertEquals(8.0, restarted.state.ride!!.timing.elapsedSeconds)
        assertNull(restarted.state.ride!!.pending)
        assertTrue(restarted.effects.isEmpty())
        assertNotNull(action(restarted.state, RideAction.recover, 31).rejection)
        assertNotNull(action(restarted.state, RideAction.resume, 31).rejection)
        val finished = action(restarted.state, RideAction.stop, 32).afterCommit(true).state
        assertEquals(clock(8).timestamp, finished.ride!!.timing.timestamp)
        assertEquals(8.0, finished.ride!!.timing.timerSeconds)
    }

    @Test
    fun discardRequiresLocalDeletionButNoNativeAcknowledgement() {
        val interrupted = RideLifecycle.restart(running(true), clock(20), id(40)).afterCommit(true).state
        val discard = action(interrupted, RideAction.discard, 21).afterCommit(true)
        assertEquals(LifecycleEffectKind.deleteRecording, discard.effects.single().intent.effect)
        assertEquals(discard.state, receipt(discard.state, 22).afterCommit(false).state)
        assertNull(receipt(discard.state, 22).afterCommit(true).state.ride)
    }

    @Test
    fun persistedCheckpointRoundTripsResolvedOptionsAndRejectsUnknownShape() {
        val state = running(true)
        assertEquals(state, LifecycleCodec.decodeState(LifecycleCodec.encodeState(state)))
        assertEquals("gps:phone", state.ride!!.options.distanceSource)
        assertFailsWith<IllegalArgumentException> {
            LifecycleCodec.decodeState(LifecycleCodec.encodeState(state).replace("\"gps:phone\"", "\"auto\""))
        }
    }

    @Test
    fun captureCommitKeepsFailedWritesAndChecksTheCommittedPrefix() {
        val original =
            CaptureOriginal(
                id(1),
                "controller",
                1,
                clock(1).timestamp,
                id(99),
                1_000_000,
                null,
                null,
                CaptureMembership(id(2), 1.0, 1),
                "{}",
            )
        val fault = CaptureFault(id(3), null, clock(1).timestamp, null, "storageFailed")
        val batch = LifecycleCapture.admit(CaptureBatch(), original, fault).batch
        assertEquals(1, LifecycleCapture.committed(batch, batch.originals, false).batch.originals.size)
        val changed = original.copy(content = "{\"value\":2}")
        assertEquals("staleCommit", LifecycleCapture.committed(batch, listOf(changed), true).outcome)
        assertTrue(LifecycleCapture.committed(batch, listOf(original), true).batch.originals.isEmpty())
        val full = CaptureBatch((1..64).map { original.copy(observationId = id(it + 100), sequence = it.toLong()) })
        val overflow = LifecycleCapture.admit(full, original, fault)
        assertFalse(overflow.admitted)
        assertEquals(64, overflow.batch.originals.size)
        assertEquals(fault, overflow.batch.fault)
    }

    @Test
    fun captureFlushesAtEightFramesOrOneSecondAfterTheOldest() {
        assertEquals(0.0, CaptureBatch.flushDelaySeconds(8, 0.0))
        assertEquals(0.75, CaptureBatch.flushDelaySeconds(1, 0.25))
        assertEquals(0.0, CaptureBatch.flushDelaySeconds(1, 1.0))
        assertEquals(0.0, CaptureBatch.flushDelaySeconds(7, 3.0))
    }

    @Test
    fun onlyPreparingRunningAndPausedRidesRecord() {
        assertEquals(
            listOf("preparing", "running", "paused"),
            LifecyclePhase.entries.map { it.name }.filter(app.powerlog.core.RideActions::recording),
        )
    }

    @Test
    fun backwardWallClockDoesNotBlockLocalControlsOrFinish() {
        val state = running()
        val shifted = clock(10).copy(timestamp = Timestamps.parse("2025-12-31T23:59:00Z"))
        val stopped = RideLifecycle.request(state, LifecycleRequest(state.ride!!.key, id(30), RideAction.stop, shifted))
        assertNull(stopped.rejection)
        val ride = stopped.afterCommit(true).state.ride!!
        assertEquals(LifecyclePhase.completed, ride.phase)
        assertEquals(shifted.timestamp, ride.timing.timestamp)
        assertEquals(10.0, ride.timing.timerSeconds)
    }

    @Test
    fun delayedStorageFailureKeepsTheLastCommittedSampleBoundary() {
        val state = RideLifecycle.checkpoint(running(), clock(8)).afterCommit(true).state
        val fault = CaptureFault(id(30), state.ride!!.key, clock(10).timestamp, null, "storageFailed")
        val failed = LifecycleStorage.failed(state, CaptureBatch(), fault, LifecycleStorageError.sqlite, clock(30))
        assertEquals(LifecyclePhase.interrupted, failed.state.ride!!.phase)
        assertEquals(clock(8).timestamp, failed.state.ride!!.timing.timestamp)
        assertEquals(8.0, failed.state.ride!!.timing.timerSeconds)
        for (action in listOf(RideAction.stop, RideAction.discard)) {
            assertEquals(RideFailure.storageBusy.name, action(failed.state, action, 31).rejection)
            val restored = LifecycleCodec.decodeState(LifecycleCodec.encodeState(failed.state))
            val restarted = RideLifecycle.restart(restored, clock(32, 100), id(45)).afterCommit(true).state
            assertFalse(restarted.ride!!.storageInterrupted)
            assertEquals(LifecyclePhase.interrupted, restarted.ride!!.phase)
            assertEquals(failed.state.ride!!.timing, restarted.ride!!.timing)
            assertNull(action(restarted, action, 33).rejection)
        }
    }
}
