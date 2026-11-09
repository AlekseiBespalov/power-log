package app.powerlog.application.slice

import app.powerlog.application.CommitPolicy
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.recording.CaptureBatchPolicy
import app.powerlog.application.recording.TimerView
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.StartPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class RecordingScenarioTest {
    private val full = StoreResult.Failed(StorageError(StorageError.Kind.full))
    private val busy = StoreResult.Failed(StorageError(StorageError.Kind.busy))

    @Test
    fun aPhoneOnlyRideConfirmsWhenItsHoldSucceeds() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val start = async { scenario.api.recording.start(Empty) }
            runCurrent()
            assertEquals(RidePhase.preparing, scenario.recording().phase)
            delay(2_000)
            val confirmedMonoUs = scenario.clock.monoUs()
            scenario.keepAlive.holds.complete(Unit)

            assertEquals(Outcome.Ok(Unit), start.await())
            val running = scenario.recording()
            assertEquals(RidePhase.running, running.phase)
            assertEquals(TimerView(TimerState.running, 0, confirmedMonoUs), running.timer)
            val created = scenario.mutations().filterIsInstance<Mutation.CreateRide>().single().ride
            val confirm =
                scenario.archive.committed.single { Mutation.SetPhase(created.id, RidePhase.running) in it.mutations }
            val event = confirm.mutations.filterIsInstance<Mutation.AppendEvents>().single().events.single()
            assertEquals(RideEventKind.start, event.kind)
            assertEquals(0L, event.elapsedUs)
            assertEquals(created.startedUtcUs, event.utcUs)
        }
    }

    @Test
    fun aConfirmCommitFailingFullAnswersStorageFullAndAFailingFailStartInterruptsTheRide() {
        val failed = Scenario()
        failed.test {
            with(failed) { ready() }
            failed.archive.onCommit = { batch -> full.takeIf { batch.sets(RidePhase.running) } }
            assertEquals(FailureCode.storageFull, failed.api.recording.start(Empty).code())
            assertEquals(listOf(RidePhase.failed), failed.page().map { it.phase })
            assertNull(failed.recording().phase)
        }

        for (interruptFails in listOf(false, true)) {
            val scenario = Scenario()
            scenario.test {
                with(scenario) { ready() }
                scenario.archive.onCommit = { batch ->
                    val refused =
                        batch.sets(RidePhase.running) ||
                            batch.sets(RidePhase.failed) ||
                            (interruptFails && batch.sets(RidePhase.interrupted))
                    full.takeIf { refused }
                }
                assertEquals(FailureCode.storageFull, scenario.api.recording.start(Empty).code())
                val view = scenario.recording { it.phase == RidePhase.interrupted }
                assertEquals(FailureCode.storageFull, view.failure?.code)
                assertEquals(FailureCode.rideActive, scenario.api.recording.start(Empty).code())
                val stored = if (interruptFails) RidePhase.preparing else RidePhase.interrupted
                assertEquals(listOf(stored), scenario.page().map { it.phase }, "interrupt fails: $interruptFails")
            }
        }
    }

    @Test
    fun aStartWhoseHoldFailsAnswersStartInterruptedAndKeepsItsFailedRide() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Failed(PortError(PortErrorKind.failed)) }
            assertEquals(FailureCode.startInterrupted, scenario.api.recording.start(Empty).code())
            assertEquals(listOf(RidePhase.failed), scenario.page().map { it.phase })
            assertNull(scenario.recording().phase)
        }
    }

    @Test
    fun eachActionThroughTheApiFollowsTheDomainsTable() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val start = async { scenario.api.recording.start(Empty) }
            runCurrent()
            val rideId = requireNotNull(scenario.recording().ride).rideId
            for (action in listOf(RideAction.pause, RideAction.resume, RideAction.lap, RideAction.finish)) assertEquals(
                FailureCode.actionPending,
                scenario.control(rideId, action).code(),
                "preparing: $action",
            )
            scenario.keepAlive.holds.complete(Unit)
            assertEquals(Outcome.Ok(Unit), start.await())

            assertEquals(FailureCode.actionUnavailable, scenario.control(rideId, RideAction.resume).code())
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.lap))
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            assertEquals(RidePhase.paused, scenario.recording().phase)
            assertEquals(FailureCode.actionUnavailable, scenario.control(rideId, RideAction.pause).code())
            assertEquals(FailureCode.actionUnavailable, scenario.control(rideId, RideAction.lap).code())
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            assertNull(scenario.recording().phase)
            assertEquals(FailureCode.rideChanged, scenario.control(rideId, RideAction.pause).code())
            val kinds = scenario.mutations().filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }
            assertEquals(
                listOf(
                    RideEventKind.start,
                    RideEventKind.lap,
                    RideEventKind.pause,
                    RideEventKind.resume,
                    RideEventKind.stop,
                ),
                kinds.map { it.kind },
            )
        }
    }

    @Test
    fun readingsAroundAPauseCutoffLandInTheIntervalsAroundIt() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            delay(2_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))

            val events = scenario.mutations().filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }
            val pausedAt = events.single { it.kind == RideEventKind.pause }.elapsedUs
            val resumedAt = events.single { it.kind == RideEventKind.resume }.elapsedUs
            val rows =
                scenario.telemetry().flatMap { append ->
                    (0 until append.rows.rows).map {
                        append.rows.elapsedUs[it].toLong() to append.rows.activeInterval[it]
                    }
                }
            assertEquals(emptyList(), rows.filter { it.first in pausedAt until resumedAt })
            assertEquals(setOf(1), rows.filter { it.first < pausedAt }.map { it.second }.toSet())
            assertEquals(setOf(2), rows.filter { it.first >= resumedAt }.map { it.second }.toSet())
        }
    }

    @Test
    fun aFullInboxDropsTheNewestReadingsAndShowsStorageBusyUntilTheNextCommit() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            val held = scenario.archive.holdNext { it.mutations.first() is Mutation.AppendTelemetry }
            val full = scenario.recording { it.failure != null }
            assertEquals(FailureCode.storageBusy, full.failure?.code)
            assertEquals(RidePhase.running, full.phase)
            delay(10_000)

            val before = scenario.telemetryRows()
            held.complete(Unit)
            runCurrent()
            val committed = scenario.telemetryRows() - before
            assertTrue(
                committed in CaptureBatchPolicy.inboxEntries..CaptureBatchPolicy.inboxEntries + 1,
                "$committed rows",
            )
            assertNull(scenario.recording().failure)
        }
    }

    @Test
    fun batchesCommitAtEightEntriesOrASecondAtMostSixtyFourAndABusyBatchIsResentIdentical() {
        val fast = Scenario()
        fast.test {
            with(fast) { riding() }
            delay(10_000)
            val sizes = fast.telemetry().map { it.rows.rows }
            assertTrue(sizes.size >= 9 && sizes.all { it == CaptureBatchPolicy.dueEntries }, sizes.toString())
        }

        val slow = Scenario()
        slow.test {
            with(slow) { riding(SampleRate.hz2) }
            delay(10_000)
            val commits =
                slow.archive.committed.indices.filter {
                    slow.archive.committed[it].mutations.first() is Mutation.AppendTelemetry
                }
            val sizes = commits.map {
                (slow.archive.committed[it].mutations.first() as Mutation.AppendTelemetry).rows.rows
            }
            assertTrue(sizes.size >= 4 && sizes.all { it in 2..3 }, sizes.toString())
            val gaps = commits.zipWithNext { a, b -> slow.archive.committedAtUs[b] - slow.archive.committedAtUs[a] }
            assertTrue(gaps.all { it in 900_000L..1_100_000L }, gaps.toString())
        }

        val queued = Scenario()
        queued.test {
            with(queued) { riding() }
            val held = queued.archive.holdNext { it.mutations.first() is Mutation.AppendTelemetry }
            delay(20_000)
            val outstanding =
                queued.archive.attempts.count { it.mutations.first() is Mutation.AppendTelemetry } -
                    queued.telemetry().size
            assertEquals(1, outstanding)
            val before = queued.telemetry().size
            held.complete(Unit)
            runCurrent()
            val sizes = queued.telemetry().drop(before).map { it.rows.rows }
            assertEquals(CaptureBatchPolicy.dueEntries, sizes.first())
            assertEquals(CaptureBatchPolicy.maxEntries, sizes.drop(1).maxOrNull(), sizes.toString())
        }

        val retried = Scenario()
        retried.test {
            val rideId = with(retried) { riding() }
            var refusals = 1
            retried.archive.onCommit = { batch ->
                busy.takeIf { batch.mutations.first() is Mutation.AppendTelemetry && refusals-- > 0 }
            }
            delay(5_000)
            val telemetry =
                retried.archive.attempts.indices.filter {
                    retried.archive.attempts[it].mutations.first() is Mutation.AppendTelemetry
                }
            val (refused, resent) = telemetry.take(2)
            assertSame(retried.archive.attempts[refused], retried.archive.attempts[resent])
            assertEquals(
                CommitPolicy.busyRetryUs,
                retried.archive.attemptedAtUs[resent] - retried.archive.attemptedAtUs[refused],
            )
            assertEquals(RidePhase.running, retried.recording().phase)
            assertEquals(Outcome.Ok(Unit), retried.control(rideId, RideAction.finish))
        }
    }

    @Test
    fun aRideCommandBehindABusyBatchAnswersStorageBusyWithoutDeciding() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            scenario.archive.onCommit = { batch -> busy.takeIf { batch.mutations.first() is Mutation.AppendTelemetry } }
            delay(1_500)
            assertEquals(FailureCode.storageBusy, scenario.control(rideId, RideAction.pause).code())
            assertEquals(RidePhase.running, scenario.recording().phase)
            assertTrue(scenario.mutations().none { it is Mutation.SetPhase && it.phase == RidePhase.paused })
        }
    }

    @Test
    fun aRunningRideWithoutBatchesCommitsACheckpointFiveSecondsAfterItsLastCommit() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            delay(3_000)
            scenario.replies = false
            delay(10_000)
            val archive = scenario.archive
            val last = archive.committed.indexOfLast { it.mutations.first() is Mutation.AppendTelemetry }
            val next = archive.committed[last + 1]
            assertEquals(listOf(Mutation.Checkpoint::class), next.mutations.map { it::class })
            assertEquals(CaptureBatchPolicy.checkpointUs, archive.committedAtUs[last + 1] - archive.committedAtUs[last])
            assertEquals(RidePhase.running, scenario.recording().phase)
        }
    }

    @Test
    fun aReconnectThatVerifiesTheControllerAgainOpensANewSession() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            scenario.fake.os.disconnected(Scenario.BIKE)
            delay(5_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))

            val sessions = scenario.mutations().filterIsInstance<Mutation.OpenControllerSession>().map { it.session }
            assertEquals(listOf(1, 2), sessions.map { it.sessionNo })
            val rows =
                scenario.telemetry().flatMap { append ->
                    (0 until append.rows.rows).map {
                        Triple(append.rows.sessionNo[it], append.rows.seq[it], append.rows.run[it])
                    }
                }
            val first = rows.filter { it.first == 1 }
            val second = rows.filter { it.first == 2 }
            assertTrue(first.isNotEmpty() && second.isNotEmpty())
            assertTrue(
                second.first().second < first.last().second,
                "seq restarts: ${first.last()} then ${second.first()}",
            )
            assertTrue(second.minOf { it.third } > first.maxOf { it.third }, "run rises")
        }
    }

    @Test
    fun anOrdinaryFinishWritesNoInterruptedPhaseAndKeepsTheFlagClear() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val finish = scenario.archive.committed.single { batch -> batch.mutations.any { it is Mutation.EndRide } }
            assertTrue(Mutation.SetPhase(rideId, RidePhase.completed) in finish.mutations)
            assertTrue(finish.mutations.none { it is Mutation.SetPhase && it.phase == RidePhase.interrupted })
            val row = scenario.page().single()
            assertEquals(RidePhase.completed, row.phase)
            assertEquals(false, row.interrupted)
        }
    }

    @Test
    fun aRefusedOrFailedStartOrControlShowsInRecordingStateWithAnOccurrence() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(app.powerlog.application.api.LaunchResult.Ready, scenario.launch())
            scenario.indoor()
            assertEquals(FailureCode.bikeRequired, scenario.api.recording.start(Empty).code())
            val refused = requireNotNull(scenario.recording().failure)
            assertEquals(FailureCode.bikeRequired, refused.code)
            assertNotNull(refused.occurrence)

            with(scenario) { connect() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val cancelled = async { scenario.api.recording.start(Empty) }
            runCurrent()
            val preparing = requireNotNull(scenario.recording().ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(preparing, RideAction.discard))
            assertEquals(FailureCode.startCancelled, cancelled.await().code())
            assertNull(scenario.recording().failure)

            scenario.keepAlive.holds.script = { Answer.Ok(Unit) }
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val rideId = requireNotNull(scenario.recording().ride).rideId
            assertEquals(FailureCode.actionUnavailable, scenario.control(rideId, RideAction.resume).code())
            val failed = requireNotNull(scenario.recording().failure)
            assertEquals(FailureCode.actionUnavailable, failed.code)
            assertNotNull(failed.occurrence)
            assertTrue(failed.occurrence != refused.occurrence)
        }
    }

    @Test
    fun eachCommittedPhaseChangeAppendsARidePhaseLine() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            for (action in listOf(RideAction.pause, RideAction.resume, RideAction.lap, RideAction.finish)) assertEquals(
                Outcome.Ok(Unit),
                scenario.control(rideId, action),
            )
            runCurrent()
            val lines =
                scenario.archive.log
                    .filter { it.event == "ridePhase" }
                    .map { line ->
                        line.fields.associate { it.key to (it.value as LogValue.Text).value }
                    }
            assertEquals(
                listOf(
                    mapOf("phase" to "preparing", "issuer" to "rider"),
                    mapOf("phase" to "running"),
                    mapOf("phase" to "paused", "issuer" to "rider"),
                    mapOf("phase" to "running", "issuer" to "rider"),
                    mapOf("phase" to "completed", "issuer" to "rider"),
                ),
                lines,
            )
        }
    }

    @Test
    fun aStartWhoseWindowPassesAnswersStartUnconfirmedAndKeepsItsFailedRide() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val startedAtUs = scenario.clock.monoUs()
            assertEquals(FailureCode.startUnconfirmed, scenario.api.recording.start(Empty).code())
            assertEquals(StartPolicy.windowUs, scenario.clock.monoUs() - startedAtUs)
            assertEquals(listOf(RidePhase.failed), scenario.page().map { it.phase })
            assertNull(scenario.recording().phase)
        }
    }

    @Test
    fun aBusyConfirmIsNotResentOnceTheWindowHasFailedTheStart() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val start = async { scenario.api.recording.start(Empty) }
            delay(StartPolicy.windowUs / 1_000 - 500)
            var refusals = 1
            scenario.archive.onCommit = { batch -> busy.takeIf { batch.sets(RidePhase.running) && refusals-- > 0 } }
            scenario.keepAlive.holds.complete(Unit)
            assertEquals(FailureCode.startUnconfirmed, start.await().code())
            delay(3_000)
            assertEquals(listOf(RidePhase.failed), scenario.page().map { it.phase })
            assertTrue(scenario.mutations().none { it is Mutation.SetPhase && it.phase == RidePhase.running })
        }
    }

    private fun WriteBatch.sets(phase: RidePhase): Boolean = mutations.any {
        it is Mutation.SetPhase && it.phase == phase
    }
}
