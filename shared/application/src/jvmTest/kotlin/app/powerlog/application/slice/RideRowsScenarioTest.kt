package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeLocationSource
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.RideCard
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class RideRowsScenarioTest {
    @Test
    fun invalidatedServicesLoseTheLinkAndItReconnectsThroughTheBackOff() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            scenario.events.servicesInvalidated(scenario.lastLink)
            scenario.connection { it.state == ConnectionState.reconnecting }
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.connected }
            val down = scenario.archive.log.first { it.event == "linkDown" }
            assertEquals(LogValue.Text("servicesInvalidated"), down.fields.single { it.key == "reason" }.value)
        }
    }

    @Test
    fun thePreparingStopDiscardsTheRideAndTheStartAnswersStartCancelled() {
        val scenario = Scenario()
        scenario.keepAlive.holds.script = { Answer.Held }
        scenario.test {
            with(scenario) { ready() }
            val start = async { scenario.api.recording.start(Empty) }
            val rideId = requireNotNull(scenario.recording { it.phase == RidePhase.preparing }.ride).rideId
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.discard))
            assertEquals(FailureCode.startCancelled, start.await().code())
            assertNull(scenario.recording { it.phase == null }.failure)
            assertTrue(scenario.mutations().any { it is Mutation.MarkRideDeleted && it.rideId == rideId })
            assertTrue(scenario.mutations().none { it is Mutation.SetPhase && it.phase == RidePhase.failed })
        }
    }

    @Test
    fun aCaptureCommitFailingIoInterruptsTheRideAndKeepsWhatCommitted() {
        val scenario = Scenario()
        scenario.rideCard = scenario.card
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            val committed = scenario.telemetryRows()
            assertTrue(committed > 0)
            scenario.archive.onCommit = { batch ->
                StoreResult.Failed(StorageError(StorageError.Kind.io)).takeIf {
                    batch.mutations.any { it is Mutation.AppendTelemetry }
                }
            }
            val interrupted = scenario.recording { it.phase == RidePhase.interrupted }
            assertEquals(FailureCode.storageFailed, interrupted.failure?.code)
            assertEquals(RideCardState.interrupted, scenario.card.shown?.state)
            assertEquals(emptyList(), scenario.card.shown?.controls)
            assertTrue(HoldKind.ride !in scenario.keepAlive.held)
            assertEquals(committed, scenario.telemetryRows())
            val attempts = scenario.archive.attempts.size
            scenario.application.host.didEnterBackground()
            assertTrue(
                scenario.archive.attempts.drop(attempts).none { batch ->
                    batch.mutations.any { it is Mutation.AppendTelemetry }
                }
            )
            assertEquals(
                Mutation.SetPhase(rideId, RidePhase.interrupted),
                scenario.mutations().last { it is Mutation.SetPhase },
            )
        }
    }

    @Test
    fun aFullCaptureWithAFullInterruptBatchInterruptsInMemoryAndFinishMarksTheStoredRide() {
        val scenario = Scenario()
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location = location
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(3_000)
            scenario.archive.onCommit = { batch ->
                StoreResult.Failed(StorageError(StorageError.Kind.full)).takeIf {
                    batch.mutations.any { it is Mutation.AppendTelemetry || it is Mutation.SetPhase }
                }
            }
            val interrupted = scenario.recording { it.phase == RidePhase.interrupted }
            assertEquals(FailureCode.storageFull, interrupted.failure?.code)
            assertTrue(HoldKind.ride !in scenario.keepAlive.held)
            assertTrue(scenario.mutations().none { it is Mutation.SetPhase && it.phase == RidePhase.interrupted })

            scenario.archive.onCommit = { null }
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val finish = scenario.archive.committed.last().mutations
            assertEquals(Mutation.SetPhase(rideId, RidePhase.interrupted), finish.first())
            assertTrue(finish.any { it is Mutation.EndRide })
        }
    }

    @Test
    fun aRideInterruptedAtLaunchFinishesAtItsLastStoredFact() {
        val scenario = Scenario()
        scenario.test {
            val seeded = scenario.seedOpenRide(RidePhase.running)
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.recording { it.phase == RidePhase.interrupted }
            assertEquals(Outcome.Ok(Unit), scenario.control(seeded.id, RideAction.finish))
            val end = scenario.mutations().filterIsInstance<Mutation.EndRide>().single().end
            assertTrue(end.elapsedUs <= 5_000_000, "$end")
            assertNull(scenario.recording { it.phase == null }.ride)
        }
    }

    @Test
    fun finishingStopsGpsAndEndsTheCardBeforeTheSubscribersHearOfIt() {
        val scenario = Scenario()
        val order = mutableListOf<String>()
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location =
            object : app.powerlog.application.port.LocationSource by location {
                override fun stop() {
                    order += "location stop"
                    location.stop()
                }
            }
        scenario.rideCard =
            object : RideCard by scenario.card {
                override fun publish(facts: RideCardFacts) = scenario.card.publish(facts)

                override fun end() {
                    order += "card end"
                    scenario.card.end()
                }
            }
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(2_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(100)
            assertEquals(listOf("location stop", "card end"), order)
            assertTrue(HoldKind.ride !in scenario.keepAlive.held)
            assertNotNull(scenario.archive.log.lastOrNull { it.event == "ridePhase" })
        }
    }
}
