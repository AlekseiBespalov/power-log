package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.fakes.Answer
import app.powerlog.application.history.HistoryListView
import app.powerlog.application.history.RideDetailView
import app.powerlog.application.history.SummaryState
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class HistoryScenarioTest {
    @Test
    fun anOpenRidesRowCannotOpenWhileItRecordsAndItsSummaryIsPending() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.keepAlive.holds.script = { Answer.Held }
            val start = async { scenario.api.recording.start(Empty) }
            runCurrent()
            val rideId = requireNotNull(scenario.recording().ride).rideId
            scenario.assertOpen(rideId, RidePhase.preparing)

            scenario.keepAlive.holds.complete(Unit)
            assertEquals(Outcome.Ok(Unit), start.await())
            scenario.assertOpen(rideId, RidePhase.running)

            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            scenario.assertOpen(rideId, RidePhase.paused)
        }
    }

    @Test
    fun theHistoryListMovesWithCommitsThatChangeHistoryButNotWithTelemetry() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            val list = scenario.api.history.list
            val launched = assertIs<StreamAnswer.Value<HistoryListView>>(list.next(Empty, null, "list"))
            val created = async { list.next(Empty, launched.revision, "created") }
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val afterStart = assertIs<StreamAnswer.Value<HistoryListView>>(created.await())
            assertTrue(afterStart.value.revision > launched.value.revision)
            val rideId = requireNotNull(scenario.recording().ride).rideId
            runCurrent()

            val running = assertIs<StreamAnswer.Value<HistoryListView>>(list.next(Empty, null, "running"))
            val quiet = async { list.next(Empty, running.revision, "quiet") }
            delay(5_000)
            assertTrue(scenario.telemetry().isNotEmpty())
            assertFalse(quiet.isCompleted)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val finished = assertIs<StreamAnswer.Value<HistoryListView>>(quiet.await())
            assertTrue(finished.value.revision > running.value.revision)
        }
    }

    @Test
    fun aCompletedRidesSummaryIsPendingOnAMissThenComputedPutAndReadFromTheCache() {
        val scenario = Scenario()
        scenario.test {
            val seeded =
                scenario.seedRide(endUs = 30_000_000, readingsUs = (0L until 30_000_000L step 250_000L).toList())
            assertEquals(LaunchResult.Ready, scenario.launch())
            val input = RideInput(seeded.ride.id)
            val first = assertIs<StreamAnswer.Value<RideDetailView>>(scenario.api.history.ride.next(input, null, "a"))
            assertEquals(SummaryState.pending, first.value.summaryState)
            assertNull(first.value.summary)
            val ready =
                assertIs<StreamAnswer.Value<RideDetailView>>(scenario.api.history.ride.next(input, first.revision, "a"))
            assertEquals(SummaryState.ready, ready.value.summaryState)
            val summary = requireNotNull(ready.value.summary)
            assertEquals(30_000_000, summary.timerUs)
            assertEquals(80.0, summary.averageCadenceRpm)
            assertTrue(requireNotNull(summary.distanceM) > 0)

            scenario.archive.telemetryReads = 0
            val cached = assertIs<StreamAnswer.Value<RideDetailView>>(scenario.api.history.ride.next(input, null, "b"))
            assertEquals(ready.value, cached.value)
            assertEquals(0, scenario.archive.telemetryReads)
        }
    }

    @Test
    fun aRideThatTurnsCompletedHasItsSummaryWarmedBeforeItOpens() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(1_000)
            scenario.archive.telemetryReads = 0
            val detail =
                assertIs<StreamAnswer.Value<RideDetailView>>(
                    scenario.api.history.ride.next(RideInput(rideId), null, "r")
                )
            assertEquals(SummaryState.ready, detail.value.summaryState)
            assertEquals(0, scenario.archive.telemetryReads)
        }
    }

    private suspend fun Scenario.assertOpen(rideId: RideId, phase: RidePhase) {
        val row = page().single()
        assertEquals(phase, row.phase)
        assertEquals(Availability(false, FailureCode.rideActive), row.open)
        val detail =
            assertIs<StreamAnswer.Value<RideDetailView>>(api.history.ride.next(RideInput(rideId), null, "ride $phase"))
        assertEquals(SummaryState.pending, detail.value.summaryState, phase.name)
        assertNull(detail.value.summary, phase.name)
    }
}
