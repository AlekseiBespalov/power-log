package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.diagnostics.DiagnosticsPage
import app.powerlog.application.diagnostics.DiagnosticsReadInput
import app.powerlog.application.fakes.Answer
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.ride.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class LinkRowsScenarioTest {
    @Test
    fun anIphoneRideReconnectsBeforeTheBikesLinkLimitAndLogsItOnce() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            repeat(3) {
                scenario.connection { it.state == ConnectionState.connected }
                delay(95_000)
                scenario.fake.os.disconnected(Scenario.BIKE)
                scenario.connection { it.state == ConnectionState.reconnecting }
            }
            scenario.connection { it.state == ConnectionState.connected }
            val planned = scenario.clock.monoUs()
            while (
                scenario.archive.log.none {
                    it.event == "reconnectScheduled" && it.text("reason") == "plannedReconnect"
                }
            ) delay(10)
            val plannedAt = scenario.clock.monoUs() - planned
            assertTrue(plannedAt in 92_000_000..93_500_000, "$plannedAt")
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.connected }
            val scheduled = scenario.archive.log.filter { it.event == "reconnectScheduled" }
            assertEquals(
                listOf("peerOrSystem", "peerOrSystem", "peerOrSystem", "plannedReconnect"),
                scheduled.map { it.text("reason") },
            )
            assertEquals("plannedReconnect", scenario.archive.log.last { it.event == "linkDown" }.text("reason"))
            val page =
                assertIs<Outcome.Ok<DiagnosticsPage>>(scenario.api.diagnostics.readLog(DiagnosticsReadInput(null)))
            assertEquals(4, page.value.connection?.reconnects)
        }
    }

    @Test
    fun anIphoneInTheBackgroundHoldsTheReconnectUntilTheLinkVerifiesAgain() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            scenario.application.host.foregroundChanged(false)
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.reconnecting }
            delay(1)
            assertEquals(setOf(HoldKind.reconnect), scenario.keepAlive.held)
            scenario.connection { it.state == ConnectionState.connected }
            assertEquals(emptySet(), scenario.keepAlive.held)
            assertEquals(listOf(HoldKind.reconnect), scenario.keepAlive.released)
        }
    }

    @Test
    fun aFailedReconnectHoldLogsItAndTheReconnectGoesOn() {
        val scenario = Scenario()
        scenario.keepAlive.holds.script = { Answer.Failed(PortError(PortErrorKind.notAllowedInBackground)) }
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            scenario.application.host.foregroundChanged(false)
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.reconnecting }
            scenario.connection { it.state == ConnectionState.connected }
            val failure = scenario.archive.log.single { it.event == "failure" }
            assertEquals("keepAlive", failure.text("source"))
            assertEquals("notAllowedInBackground", failure.text("error"))
        }
    }

    @Test
    fun invalidatedServicesWaitForTheCancelledLinkThenFollowTheBackOff() {
        val scenario = Scenario(Platform.android)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            val invalidated = scenario.clock.monoUs()
            scenario.events.servicesInvalidated(scenario.lastLink)
            scenario.connection { it.state == ConnectionState.reconnecting }
            scenario.connection { it.state == ConnectionState.connected }
            val attempt = scenario.archive.log.last { it.event == "connectAttempt" }
            assertTrue(
                attempt.monoUs - invalidated >= ControllerPolicy.cancelGraceUs,
                "${attempt.monoUs - invalidated}",
            )
            val deadline = scenario.archive.log.single { it.event == "cancellationDeadline" }
            assertEquals(LogValue.Flag(true), deadline.fields.single { it.key == "released" }.value)
        }
    }

    @Test
    fun invalidatedServicesWhoseLinkDownArrivesReconnectAfterTheFirstBackOff() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            val invalidated = scenario.clock.monoUs()
            scenario.events.servicesInvalidated(scenario.lastLink)
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.connected }
            val attempt = scenario.archive.log.last { it.event == "connectAttempt" }
            assertEquals(ControllerPolicy.reconnectBackoffUs[0], attempt.monoUs - invalidated)
        }
    }

    @Test
    fun aDisconnectDuringASearchEndsItSoItsWindowChangesNothingLater() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.disconnect(app.powerlog.application.api.Empty))
            delay(ControllerPolicy.searchUs / 1_000 + 1_000)
            val view = scenario.connection()
            assertEquals(ConnectionState.idle, view.state)
            assertEquals(null, view.failure)
        }
    }

    @Test
    fun aFailedWebChooserAnswersBikeUnavailable() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.events.searchEnded(PortError(PortErrorKind.failed))
            assertEquals(
                app.powerlog.domain.core.failure.FailureCode.bikeUnavailable,
                scenario.connection { it.state == ConnectionState.idle }.failure?.code,
            )
        }
    }

    private fun LogLine.text(key: String): String? =
        (fields.singleOrNull { it.key == key }?.value as? LogValue.Text)?.value
}
