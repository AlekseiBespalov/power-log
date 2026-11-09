package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.diagnostics.DiagnosticsPage
import app.powerlog.application.diagnostics.DiagnosticsReadInput
import app.powerlog.application.diagnostics.DiagnosticsView
import app.powerlog.application.diagnostics.TraceDocument
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class DiagnosticsScenarioTest {
    @Test
    fun aTraceStopsAtItsTimeLimitAndReadsBackTodaysEvents() {
        val scenario = Scenario()
        scenario.test {
            val api = scenario.api
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(FailureCode.actionUnavailable, api.diagnostics.readTrace(Empty).code())
            assertEquals(Outcome.Ok(Unit), api.diagnostics.startTrace(Empty))
            with(scenario) { connect() }
            delay(130_000)
            val view = assertIs<StreamAnswer.Value<DiagnosticsView>>(api.diagnostics.state.next(Empty, null, "d")).value
            assertEquals(TraceState.stopped, view.trace.state)
            assertEquals(TraceStopReason.timeLimit, view.trace.stopReason)

            val document = assertIs<Outcome.Ok<TraceDocument>>(api.diagnostics.readTrace(Empty)).value
            val trace = document.trace
            assertEquals("power-log-bluetooth-trace", trace.format)
            assertEquals(1, trace.version)
            assertEquals(TraceStopReason.timeLimit, trace.stopReason)
            assertEquals(trace.eventCount, trace.events.size)
            assertEquals(CycDecoder.traceContract, document.expectedContract)
            assertNull(document.exportedAt)
            val names = trace.events.map { it.event }
            assertEquals("capture_started", names.first())
            for (name in
                listOf("advertisement", "service", "characteristic", "write", "notification", "decoder")) assertTrue(
                name in names,
                "$name in $names",
            )
            val write = trace.events.first { it.event == "write" }
            assertEquals(write.byteLength, write.capturedBytes)
            assertEquals(write.byteLength?.times(2), write.hex?.length)
            assertTrue(trace.events.last().elapsedSeconds <= 120.0)
        }
    }

    @Test
    fun aStoppedTraceKeepsItsEventsUntilCleared() {
        val scenario = Scenario()
        scenario.test {
            val api = scenario.api
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(Outcome.Ok(Unit), api.diagnostics.startTrace(Empty))
            assertEquals(Outcome.Ok(Unit), api.diagnostics.stopTrace(Empty))
            val stopped = assertIs<Outcome.Ok<TraceDocument>>(api.diagnostics.readTrace(Empty)).value.trace
            assertEquals(TraceStopReason.manual, stopped.stopReason)
            assertEquals(listOf("capture_started"), stopped.events.map { it.event })
            assertEquals(Outcome.Ok(Unit), api.diagnostics.clearTrace(Empty))
            val cleared =
                assertIs<StreamAnswer.Value<DiagnosticsView>>(api.diagnostics.state.next(Empty, null, "d")).value
            assertEquals(TraceState.idle, cleared.trace.state)
            assertEquals(FailureCode.actionUnavailable, api.diagnostics.readTrace(Empty).code())
        }
    }

    @Test
    fun anUnreadableLogStillAnswersTheReportsWithAnEmptyLogAndLogError() {
        val scenario = Scenario()
        scenario.archive.logReadFails = true
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            val page =
                assertIs<Outcome.Ok<DiagnosticsPage>>(scenario.api.diagnostics.readLog(DiagnosticsReadInput(null)))
                    .value
            assertEquals("", page.log)
            assertEquals(FailureCode.readFailed, page.logError)
            assertNull(page.next)
            val device = assertNotNull(page.device)
            assertNull(device.snapshot.localeFacts.region)
            assertTrue('-' !in device.snapshot.locale && '_' !in device.snapshot.locale, device.snapshot.locale)
            val connection = assertNotNull(page.connection)
            assertEquals(ConnectionState.connected, connection.state)
            assertEquals(1, connection.connectionAttempts)
            assertNotNull(connection.lastSampleAgeMs)
        }
    }

    @Test
    fun aWantedBikeThatIsReconnectingStillGetsItsCaptureSummary() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(app.powerlog.application.api.LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == app.powerlog.domain.connection.ConnectionState.connected }
            scenario.linkUps = false
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == app.powerlog.domain.connection.ConnectionState.reconnecting }
            val from = scenario.archive.log.count { it.event == "captureSummary" }
            kotlinx.coroutines.delay(31_000)
            assertEquals(app.powerlog.domain.connection.ConnectionState.reconnecting, scenario.connection().state)
            kotlin.test.assertTrue(scenario.archive.log.count { it.event == "captureSummary" } > from)
        }
    }

    @Test
    fun theTraceMirrorsTheConnectionsLogLines() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(app.powerlog.application.api.LaunchResult.Ready, scenario.launch())
            assertEquals(Outcome.Ok(Unit), scenario.api.diagnostics.startTrace(Empty))
            with(scenario) { connect() }
            scenario.connection { it.state == app.powerlog.domain.connection.ConnectionState.connected }
            val document = assertIs<Outcome.Ok<TraceDocument>>(scenario.api.diagnostics.readTrace(Empty)).value
            val events = document.trace.events.map { it.event }
            for (event in listOf("searchStarted", "connectAttempt", "linkUp", "ready", "identity")) kotlin.test
                .assertTrue(event in events, "$event in $events")
        }
    }
}
