package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.port.RadioState
import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class OverlappingCommandsScenarioTest {
    @Test
    fun twoOverlappingStartsOpenOneRideAndTheOtherAnswersActionPending() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            val first = async { scenario.api.recording.start(Empty) }
            val second = async { scenario.api.recording.start(Empty) }
            val answers = listOf(first.await(), second.await())
            assertEquals(setOf(null, FailureCode.actionPending), answers.map { it.code() }.toSet(), answers.toString())
            assertEquals(1, scenario.mutations().count { it is Mutation.CreateRide })
        }
    }

    @Test
    fun overlappingConnectsShareOneLinkAndAnotherBikeAnswersDisconnectFirst() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.discovered()
            scenario.connection { it.found.isNotEmpty() }
            val first = async { scenario.api.connection.connect(ConnectInput(BikeId(Scenario.BIKE))) }
            val second = async { scenario.api.connection.connect(ConnectInput(BikeId(Scenario.BIKE))) }
            runCurrent()
            assertEquals(
                FailureCode.disconnectFirst,
                scenario.api.connection.connect(ConnectInput(BikeId("D2"))).code(),
            )
            assertEquals(listOf(Outcome.Ok(Unit), Outcome.Ok(Unit)), listOf(first.await(), second.await()))
            assertEquals(1, scenario.fake.osCalls.count { it == "connect ${Scenario.BIKE}" })
        }
    }

    @Test
    fun overlappingSearchesStartOneScan() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val first = async { scenario.api.connection.startSearch(Empty) }
            val second = async { scenario.api.connection.startSearch(Empty) }
            runCurrent()
            scenario.fake.os.radio(RadioState.poweredOn)
            assertEquals(listOf(Outcome.Ok(Unit), Outcome.Ok(Unit)), listOf(first.await(), second.await()))
            assertEquals(1, scenario.fake.osCalls.count { it.startsWith("scan") })
        }
    }

    @Test
    fun twoOverlappingPausesPauseOnceAndTheOtherAnswersActionPending() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            val held = scenario.archive.holdNext { it.mutations.first() is Mutation.AppendTelemetry }
            delay(1_500)
            val first = async { scenario.control(rideId, RideAction.pause) }
            val second = async { scenario.control(rideId, RideAction.pause) }
            runCurrent()
            held.complete(Unit)
            val answers = listOf(first.await(), second.await())
            assertEquals(setOf(null, FailureCode.actionPending), answers.map { it.code() }.toSet(), answers.toString())
            val events = scenario.mutations().filterIsInstance<Mutation.AppendEvents>().flatMap { it.events }
            assertEquals(1, events.count { it.kind == RideEventKind.pause })
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
        }
    }
}
