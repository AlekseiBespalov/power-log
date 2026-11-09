package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.port.HoldKind
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.FreshnessPolicy
import app.powerlog.domain.recording.RideCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class RideCardScenarioTest {
    @Test
    fun aStaleTokenAnswersRideChangedPauseRotatesTheTokenAndFinishPostsARequest() {
        val scenario = Scenario()
        scenario.rideCard = scenario.card
        scenario.test {
            val rideId = with(scenario) { riding() }
            val running = requireNotNull(scenario.card.shown)
            assertEquals(RideCardState.running, running.state)
            assertEquals(listOf(RideAction.pause, RideAction.finish), running.controls)
            assertEquals(TimerState.running, running.timer.state)

            val stale = RideCardIntent(rideId.value, "stale", RideCardState.running, RideAction.pause)
            assertEquals(FailureCode.rideChanged, scenario.application.host.rideCardIntent(stale).code())

            val pause = RideCardIntent(rideId.value, running.token, RideCardState.running, RideAction.pause)
            assertEquals(Outcome.Ok(Unit), scenario.application.host.rideCardIntent(pause))
            val paused = requireNotNull(scenario.card.shown)
            assertEquals(RideCardState.paused, paused.state)
            assertNotEquals(running.token, paused.token)
            assertEquals(listOf(RideAction.resume, RideAction.finish), paused.controls)
            assertEquals(RidePhase.paused, scenario.recording().phase)

            val finish = RideCardIntent(rideId.value, paused.token, RideCardState.paused, RideAction.finish)
            assertEquals(Outcome.Ok(Unit), scenario.application.host.rideCardIntent(finish))
            assertEquals(rideId, scenario.recording { it.finishRequest != null }.finishRequest?.rideId)
            assertEquals(RidePhase.paused, scenario.recording().phase)

            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            assertEquals(null, scenario.recording { it.phase == null }.finishRequest)
            assertEquals(1, scenario.card.ends)
        }
    }

    @Test
    fun readingsRefreshTheCardAtMostEveryFifteenSecondsAndChangesAtOnce() {
        val scenario = Scenario()
        scenario.rideCard = scenario.card
        scenario.test {
            val rideId = with(scenario) { riding() }
            val from = scenario.card.published.size
            delay(40_000)
            val readings = scenario.card.published.drop(from)
            assertTrue(readings.size in 2..3, "${readings.size} publishes")
            assertTrue(readings.all { it.state == RideCardState.running && it.powerW != null })
            assertTrue(readings.all { requireNotNull(it.bikeFreshUntilUtcUs) > scenario.clock.utcUs() - 40_000_000 })

            val before = scenario.card.published.size
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            val changes = scenario.card.published.drop(before)
            assertEquals(listOf(RideCardState.updating, RideCardState.paused), changes.map { it.state }.distinct())
            assertTrue(FreshnessPolicy.rideCardRefreshUs > 0)
        }
    }

    @Test
    fun theCardShowsStartingBeforeTheRideHoldIsAsked() {
        val scenario = Scenario(app.powerlog.domain.core.ride.Platform.android)
        scenario.rideCard = scenario.card
        scenario.keepAlive.holds.script = { Answer.Held }
        scenario.test {
            with(scenario) { ready() }
            val start = async { scenario.api.recording.start(Empty) }
            while (scenario.keepAlive.held.isEmpty()) delay(1)
            assertEquals(setOf(HoldKind.ride), scenario.keepAlive.held)
            assertEquals(RideCardState.starting, scenario.card.shown?.state)
            assertTrue(scenario.card.shown?.controls.orEmpty().isEmpty())
            scenario.keepAlive.holds.complete(Unit)
            assertEquals(Outcome.Ok(Unit), start.await())
            assertEquals(RideCardState.running, scenario.card.shown?.state)
        }
    }

    @Test
    fun aRideOpenAtLaunchShowsItsCardInterruptedWithoutControls() {
        val scenario = Scenario()
        scenario.rideCard = scenario.card
        scenario.test {
            scenario.seedOpenRide(RidePhase.running)
            assertEquals(LaunchResult.Ready, scenario.launch())
            val shown = requireNotNull(scenario.card.shown)
            assertEquals(RideCardState.interrupted, shown.state)
            assertEquals(emptyList(), shown.controls)
            assertEquals(TimerState.frozen, shown.timer.state)
        }
    }
}
