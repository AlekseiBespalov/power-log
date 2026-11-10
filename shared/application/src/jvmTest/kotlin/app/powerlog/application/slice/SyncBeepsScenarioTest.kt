package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.recording.CueView
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.recording.CueAction
import app.powerlog.domain.recording.SyncBeeps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class SyncBeepsScenarioTest {
    private suspend fun ScenarioScope.cued(scenario: Scenario) {
        with(scenario) { ready(SampleRate.hz2) }
        val on = SettingsChange.Toggle(RideToggle.syncBeeps, true)
        assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(on))
    }

    private fun Scenario.elapsed(): List<Long> =
        telemetry().flatMap { batch -> (0 until batch.rows.rows).map { batch.rows.elapsedUs[it].toLong() } }

    private fun Scenario.beepLines() =
        archive.log.filter { it.event == "syncBeep" }.map { line -> line.fields.associate { it.key to it.value } }

    @Test
    fun aCuedStartCreatesTheRideOnAWholeSecondBeepWithAReadingInItsFirstSecond() {
        val scenario = Scenario()
        scenario.test {
            cued(scenario)
            delay(137)
            val beep = SyncBeeps.startAt(scenario.clock.read())
            val start = async { scenario.api.recording.start(Empty) }
            runCurrent()
            assertEquals(CueView(CueAction.start, beep.monoUs), scenario.recording().cue)
            assertTrue(scenario.mutations().none { it is Mutation.CreateRide })
            assertEquals(
                listOf(beep.monoUs - 3_000_000, beep.monoUs - 2_000_000, beep.monoUs - 1_000_000, beep.monoUs),
                scenario.tonePlayer.plays.received.single().map { it.atMonoUs },
            )

            assertEquals(Outcome.Ok(Unit), start.await())
            val ride = scenario.mutations().filterIsInstance<Mutation.CreateRide>().single().ride
            assertEquals(beep.utcUs, ride.startedUtcUs)
            assertEquals(0L, ride.startedUtcUs % 1_000_000)
            val running = scenario.recording { it.phase == RidePhase.running }
            assertNull(running.cue)
            assertEquals(beep.monoUs, running.timer.originMonoUs)
            delay(3_000)
            val elapsed = scenario.elapsed()
            assertTrue(elapsed.first() in 0..Scenario.REPLY_MS * 1_000, "$elapsed")
            assertEquals(LogValue.Text("played"), scenario.beepLines().single()["outcome"])
        }
    }

    @Test
    fun cancellingTheCountdownStopsTheTonesAndLeavesNoRide() {
        val scenario = Scenario()
        scenario.test {
            cued(scenario)
            scenario.tonePlayer.plays.script = { Answer.Held }
            val start = async { scenario.api.recording.start(Empty) }
            runCurrent()
            delay(1_000)
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.cancelStart(Empty))
            assertEquals(FailureCode.startCancelled, start.await().code())
            runCurrent()
            assertTrue(scenario.mutations().none { it is Mutation.CreateRide })
            assertEquals(1, scenario.tonePlayer.stops)
            val view = scenario.recording()
            assertNull(view.cue)
            assertNull(view.phase)
            assertNull(view.failure)
            assertEquals(FailureCode.actionUnavailable, scenario.api.recording.cancelStart(Empty).code())
            assertNull(scenario.recording().failure)
            assertEquals(LogValue.Text("cancelled"), scenario.beepLines().single()["outcome"])
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
        }
    }

    @Test
    fun aCuedResumeAndFinishLandOnWholeRideSecondsWithReadingsOnTheRightSide() {
        val scenario = Scenario()
        scenario.test {
            cued(scenario)
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val running = scenario.recording { it.phase == RidePhase.running }
            val rideId = assertNotNull(running.ride).rideId
            val origin = assertNotNull(running.timer.originMonoUs)
            delay(2_300)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            delay(4_100)
            val resumePress = scenario.clock.monoUs()
            val resume = async { scenario.control(rideId, RideAction.resume) }
            runCurrent()
            val resumeCue = assertNotNull(scenario.recording().cue)
            assertEquals(CueAction.resume, resumeCue.action)
            assertEquals(RidePhase.paused, scenario.recording().phase)
            assertEquals(0L, (resumeCue.atMonoUs - origin) % 1_000_000)
            assertTrue(resumeCue.atMonoUs - resumePress in 1_000_000 until 2_000_000)
            assertEquals(Outcome.Ok(Unit), resume.await())
            delay(3_200)
            val finish = async { scenario.control(rideId, RideAction.finish) }
            runCurrent()
            val finishCue = assertNotNull(scenario.recording().cue)
            assertEquals(CueAction.finish, finishCue.action)
            assertEquals(Outcome.Ok(Unit), finish.await())

            val events = scenario.mutations().filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }
            val paused = events.single { it.kind == RideEventKind.pause }.elapsedUs
            val resumed = events.single { it.kind == RideEventKind.resume }.elapsedUs
            val stopped = events.single { it.kind == RideEventKind.stop }.elapsedUs
            assertEquals(resumeCue.atMonoUs - origin, resumed)
            assertEquals(finishCue.atMonoUs - origin, stopped)
            assertEquals(0L, stopped % 1_000_000)
            val elapsed = scenario.elapsed()
            assertTrue(elapsed.none { it in paused until resumed }, "$elapsed")
            assertTrue(elapsed.first { it >= resumed } < resumed + 500_000, "$elapsed")
            assertTrue(elapsed.any { it in stopped - 500_000 until stopped }, "$elapsed")
            assertTrue(elapsed.all { it <= stopped }, "$elapsed")
            assertEquals(listOf(4, 1, 1), scenario.tonePlayer.plays.received.map { it.size })
        }
    }

    @Test
    fun aToneThatFailsBeforeTheBeepIsStillShownAfterTheRideMoves() {
        val scenario = Scenario()
        scenario.test {
            cued(scenario)
            scenario.tonePlayer.plays.script = { Answer.Failed(PortError(PortErrorKind.busy)) }
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val running = scenario.recording { it.phase == RidePhase.running && it.failure != null }
            assertEquals(FailureCode.syncBeepFailed, running.failure?.code)
            val rideId = assertNotNull(running.ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            assertNull(scenario.recording().failure)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            val resumed = scenario.recording { it.phase == RidePhase.running && it.failure != null }
            assertEquals(FailureCode.syncBeepFailed, resumed.failure?.code)
            val failed = scenario.beepLines().filter { it["outcome"] == LogValue.Text("failed") }
            assertEquals(listOf(LogValue.Text("busy"), LogValue.Text("busy")), failed.map { it["error"] })
        }
    }

    @Test
    fun withTheSettingOffNoTonePlaysAndTheRideStartsAtOnce() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready(SampleRate.hz2) }
            val pressed = scenario.clock.read()
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val ride = scenario.mutations().filterIsInstance<Mutation.CreateRide>().single().ride
            assertEquals(pressed.utcUs, ride.startedUtcUs)
            val rideId = assertNotNull(scenario.recording { it.phase == RidePhase.running }.ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            assertTrue(scenario.tonePlayer.plays.received.isEmpty())
            assertTrue(scenario.beepLines().isEmpty())
        }
    }

    @Test
    fun theTestBeepPlaysTheSyncToneSoonAndAnswersItsOutcome() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready(SampleRate.hz2) }
            val now = scenario.clock.monoUs()
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.testBeep(Empty))
            val tone = scenario.tonePlayer.plays.received.single().single()
            assertEquals(now + SyncBeeps.testLeadUs, tone.atMonoUs)
            scenario.tonePlayer.plays.script = { Answer.Failed(PortError(PortErrorKind.busy)) }
            assertEquals(FailureCode.syncBeepFailed, scenario.api.recording.testBeep(Empty).code())
            assertEquals(
                listOf(LogValue.Text("test"), LogValue.Text("test")),
                scenario.beepLines().map { it["action"] },
            )
        }
    }
}
