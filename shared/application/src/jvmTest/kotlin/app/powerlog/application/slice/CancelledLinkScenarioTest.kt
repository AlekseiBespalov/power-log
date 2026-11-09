package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RidePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class CancelledLinkScenarioTest {
    private val graceMs = ControllerPolicy.cancelGraceUs / 1_000
    private val bike = ConnectInput(BikeId(Scenario.BIKE))

    @Test
    fun aConnectOrSearchRightAfterADisconnectWaitsForTheCancelledLinksLinkDown() {
        for (platform in listOf(Platform.ios, Platform.android)) {
            val scenario = Scenario(platform)
            scenario.test {
                assertEquals(LaunchResult.Ready, scenario.launch())
                with(scenario) { connect() }
                assertEquals(Outcome.Ok(Unit), scenario.api.connection.disconnect(Empty))
                with(scenario) { search() }
                scenario.discovered()
                scenario.connection { it.found.isNotEmpty() }
                val connecting = async { scenario.api.connection.connect(bike) }
                delay(1_000)
                assertEquals(1, scenario.connects(), platform.name)
                assertEquals(ConnectionState.connecting, scenario.connection().state, platform.name)

                scenario.fake.os.disconnected(Scenario.BIKE)
                assertEquals(Outcome.Ok(Unit), connecting.await(), platform.name)
                assertEquals(2, scenario.connects(), platform.name)
                assertFalse("close ${Scenario.BIKE}" in scenario.fake.osCalls, platform.name)
            }
        }
    }

    @Test
    fun aCancelledLinkStillHeldAtTheGraceIsReleasedOnAndroidAndTheNewAttemptFollows() {
        val manual = Scenario(Platform.android)
        manual.test {
            assertEquals(LaunchResult.Ready, manual.launch())
            with(manual) { connect() }
            assertEquals(Outcome.Ok(Unit), manual.api.connection.disconnect(Empty))
            val connecting = async { manual.api.connection.connect(bike) }
            delay(graceMs - 1)
            assertEquals(1, manual.connects())
            assertEquals(Outcome.Ok(Unit), connecting.await())
            assertEquals(listOf("disconnect", "close", "connect"), manual.linkCalls().takeLast(3))
            runCurrent()
            assertEquals(listOf(true), manual.deadlines())
        }

        val riding = Scenario(Platform.android)
        riding.test {
            with(riding) { riding() }
            riding.telemetryReplies = false
            waitFor { "disconnect ${Scenario.BIKE}" in riding.fake.osCalls }
            delay(graceMs - 1)
            assertEquals(1, riding.connects())
            waitFor { riding.connects() == 2 }
            assertEquals(listOf("disconnect", "close", "connect"), riding.linkCalls().takeLast(3))
            assertEquals(RidePhase.running, riding.recording().phase)
        }
    }

    @Test
    fun aCancelledLinkTheIPhoneCannotReleaseGivesUpOutsideARideAndWaitsForTheOsInOne() {
        val manual = Scenario(Platform.ios)
        manual.test {
            assertEquals(LaunchResult.Ready, manual.launch())
            with(manual) { connect() }
            assertEquals(Outcome.Ok(Unit), manual.api.connection.disconnect(Empty))
            val disconnectedAtUs = manual.clock.monoUs()
            val forgotten = manual.forgets()
            assertEquals(FailureCode.bikeUnavailable, manual.api.connection.connect(bike).code())
            assertEquals(disconnectedAtUs + ControllerPolicy.cancelGraceUs, manual.clock.monoUs())
            assertEquals(1, manual.connects())
            runCurrent()
            assertEquals(listOf(false), manual.deadlines())
            val view = manual.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bikeUnavailable, view.failure?.code)
            waitFor { manual.forgets() == forgotten + 1 }
        }

        val riding = Scenario(Platform.ios)
        riding.test {
            with(riding) { riding() }
            riding.telemetryReplies = false
            waitFor { "disconnect ${Scenario.BIKE}" in riding.fake.osCalls }
            delay(graceMs + 1_000)
            assertEquals(1, riding.connects())
            assertEquals(ConnectionState.reconnecting, riding.connection().state)
            assertEquals(RidePhase.running, riding.recording().phase)

            riding.fake.os.disconnected(Scenario.BIKE)
            waitFor { riding.connects() == 2 }
        }
    }

    @Test
    fun aSilentLinkTheAppCancelledWithoutALinkDownIsReleasedAtTheGrace() {
        val android = Scenario(Platform.android)
        android.test {
            assertEquals(LaunchResult.Ready, android.launch())
            with(android) { connect() }
            android.telemetryReplies = false
            waitFor { "disconnect ${Scenario.BIKE}" in android.fake.osCalls }
            delay(graceMs + 1_000)
            assertEquals(listOf("disconnect", "close", "connect"), android.linkCalls().takeLast(3))
        }

        val iphone = Scenario(Platform.ios)
        iphone.test {
            assertEquals(LaunchResult.Ready, iphone.launch())
            with(iphone) { connect() }
            iphone.telemetryReplies = false
            waitFor { "disconnect ${Scenario.BIKE}" in iphone.fake.osCalls }
            delay(graceMs + 1_000)
            val view = iphone.connection()
            assertEquals(ConnectionState.idle, view.state)
            assertEquals(FailureCode.bikeUnavailable, view.failure?.code)
            assertEquals(1, iphone.connects())
        }
    }

    private fun Scenario.deadlines(): List<Boolean> =
        archive.log
            .filter { it.event == "cancellationDeadline" }
            .map { line ->
                (line.fields.single { it.key == "released" }.value as LogValue.Flag).value
            }

    private fun Scenario.connects(): Int = fake.osCalls.count { it == "connect ${Scenario.BIKE}" }

    private fun Scenario.linkCalls(): List<String> =
        fake.osCalls
            .filter { it.endsWith(" ${Scenario.BIKE}") && it.substringBefore(' ') in linkVerbs }
            .map {
                it.substringBefore(' ')
            }

    private fun Scenario.forgets(): Int = mutations().count { it == Mutation.SelectBike(null) }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(120_000) {
            if (condition()) return
            delay(1)
        }
        fail("the condition never held")
    }

    private companion object {
        val linkVerbs = setOf("connect", "disconnect", "close", "cancel")
    }
}
