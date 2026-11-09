package app.powerlog.application.slice

import app.powerlog.application.api.DeviceInfoView
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeHealthProvider
import app.powerlog.application.fakes.FakeStoragePersistence
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.RideCard
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.settings.PersistenceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class RecorderRowsScenarioTest {
    @Test
    fun finishEndsTheCardBeforeReleasingTheRideHold() {
        val scenario = Scenario(Platform.android)
        val heldAtEnd = mutableListOf<Boolean>()
        scenario.rideCard =
            object : RideCard by scenario.card {
                override fun end() {
                    heldAtEnd += HoldKind.ride in scenario.keepAlive.held
                    scenario.card.end()
                }
            }
        scenario.test {
            val rideId = with(scenario) { riding() }
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            assertEquals(listOf(true), heldAtEnd)
            assertTrue(HoldKind.ride !in scenario.keepAlive.held)
        }
    }

    @Test
    fun aSecondPreparingDiscardAnswersActionPending() {
        val scenario = Scenario()
        scenario.keepAlive.holds.script = { Answer.Held }
        scenario.archive.holdNext { batch ->
            batch.mutations.any { it is app.powerlog.application.store.Mutation.MarkRideDeleted }
        }
        scenario.test {
            with(scenario) { ready() }
            val start = async { scenario.api.recording.start(Empty) }
            val rideId = requireNotNull(scenario.recording { it.phase == RidePhase.preparing }.ride).rideId
            val first = async { scenario.control(rideId, RideAction.discard) }
            delay(1)
            assertEquals(FailureCode.actionPending, scenario.control(rideId, RideAction.discard).code())
            assertEquals(RideAction.discard, scenario.recording().pending)
            scenario.archive.onCommit = { null }
            first.cancel()
            start.cancel()
        }
    }

    @Test
    fun aPhoneWriterStartWaitsForTheSessionAndTheNextHealthStartWaitsForItsEnd() {
        val scenario = Scenario()
        val provider = FakeHealthProvider.apple(scenario.clock, scenario.post)
        scenario.health = provider
        val session = requireNotNull(provider.session)
        session.begins.script = { Answer.Held }
        session.finishes.script = { Answer.Held }
        scenario.test {
            with(scenario) { ready() }
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.saveToHealth, true)),
            )
            val start = async { scenario.api.recording.start(Empty) }
            scenario.recording { it.phase == RidePhase.preparing }
            delay(2_000)
            assertEquals(RidePhase.preparing, scenario.recording().phase)
            session.begins.complete(Unit)
            assertEquals(Outcome.Ok(Unit), start.await())
            val rideId = requireNotNull(scenario.recording().ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(100)
            assertEquals(FailureCode.actionPending, scenario.api.recording.start(Empty).code())
            session.finishes.complete(
                session.finishes.received.last().let { app.powerlog.application.port.FinishedWorkout("w", true) }
            )
            delay(100)
            session.begins.script = { Answer.Ok(Unit) }
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
        }
    }

    @Test
    fun theFirstWebStartAsksToPersistStorageAndDeviceInfoShowsTheAnswer() {
        val scenario = Scenario(Platform.web)
        val persistence = FakeStoragePersistence(scenario.post)
        scenario.persistence = persistence
        scenario.test {
            with(scenario) { ready() }
            val before = assertIs<StreamAnswer.Value<DeviceInfoView>>(scenario.api.device.info.next(Empty, null, "d"))
            assertEquals(PersistenceState.notPersisted, before.value.storage)
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val after =
                assertIs<StreamAnswer.Value<DeviceInfoView>>(scenario.api.device.info.next(Empty, before.revision, "d"))
            assertEquals(PersistenceState.persisted, after.value.storage)
            val rideId = requireNotNull(scenario.recording().ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            assertEquals(1, persistence.requests)
        }
    }
}
