package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.diagnostics.DiagnosticsPage
import app.powerlog.application.diagnostics.DiagnosticsReadInput
import app.powerlog.application.diagnostics.DiagnosticsView
import app.powerlog.application.diagnostics.TraceDocument
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class DiagnosticsScenarioTest {
    private fun LogLine.integer(key: String): Long? =
        (fields.firstOrNull { it.key == key }?.value as? LogValue.Integer)?.value

    private fun LogLine.text(key: String): String? =
        (fields.firstOrNull { it.key == key }?.value as? LogValue.Text)?.value

    private fun identityTimeout(answer: ByteArray?): LogLine {
        val scenario = Scenario()
        scenario.replies = false
        lateinit var timeout: LogLine
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.discovered()
            scenario.connection { view -> view.found.any { it.bikeId == BikeId(Scenario.BIKE) } }
            val connecting = async { scenario.api.connection.connect(ConnectInput(BikeId(Scenario.BIKE))) }
            while (ControllerRequest.identity !in scenario.sent) delay(1)
            answer?.let { scenario.fake.os.notify(it) }
            delay(3_000)
            timeout = scenario.archive.log.first { it.event == "replyTimeout" }
            connecting.await()
        }
        assertEquals(ControllerRequest.identity.name, timeout.text("request"))
        return timeout
    }

    @Test
    fun captureSummariesContinueThroughWindowsWithoutReplies() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            scenario.telemetryReplies = false
            val from = scenario.archive.log.size
            delay(95_000)
            val summaries = scenario.archive.log.drop(from).filter { it.event == "captureSummary" }
            assertTrue(summaries.count { it.integer("replyMeanMs") == null } >= 2, "$summaries")
            assertTrue(scenario.archive.log.none { it.event == "unexpected" })
        }
    }

    @Test
    fun aTelemetrySilenceTimesOutWithNothingArrived() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            scenario.telemetryReplies = false
            delay(5_000)
            val timeout = scenario.archive.log.first { it.event == "replyTimeout" }
            assertEquals(ControllerRequest.telemetry.name, timeout.text("request"))
            assertEquals(0L, timeout.integer("notifications"))
            assertEquals(0L, timeout.integer("droppedReplies"))
            assertEquals(0L, timeout.integer("unknownFrames"))
            assertEquals(0L, timeout.integer("discardedBytes"))
            assertEquals(0L, timeout.integer("pendingBytes"))
        }
    }

    @Test
    fun anIdentitySilenceTimesOutWithNothingArrived() {
        val timeout = identityTimeout(null)
        assertEquals(0L, timeout.integer("notifications"))
        assertEquals(0L, timeout.integer("droppedReplies"))
    }

    @Test
    fun anUnrecognisedFrameIsLoggedWithItsCommand() {
        val timeout = identityTimeout(Scenario.frame("07"))
        assertEquals(1L, timeout.integer("notifications"))
        assertEquals(1L, timeout.integer("unknownFrames"))
        assertEquals(7L, timeout.integer("unknownCommand"))
        assertEquals(0L, timeout.integer("droppedReplies"))
    }

    @Test
    fun aReadingWhileIdentityIsPendingIsADroppedReply() {
        val timeout = identityTimeout(Scenario.telemetryFrame)
        assertEquals(1L, timeout.integer("notifications"))
        assertEquals(1L, timeout.integer("droppedReplies"))
        assertEquals(0L, timeout.integer("unknownFrames"))
        assertNull(timeout.integer("unknownCommand"))
    }

    @Test
    fun aDuplicateReplyBetweenPollsAddsNoReading() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            delay(2_000)
            val sends = scenario.sentAtUs.size
            while (scenario.sentAtUs.size == sends) delay(1)
            delay(Scenario.REPLY_MS + 10)
            scenario.fake.os.notify(Scenario.telemetryFrame)
            runCurrent()
            val page =
                assertIs<Outcome.Ok<DiagnosticsPage>>(scenario.api.diagnostics.readLog(DiagnosticsReadInput(null)))
                    .value
            val gapMs = assertNotNull(assertNotNull(page.connection).lastGapMs)
            assertTrue(gapMs > Scenario.REPLY_MS + 10, "last gap $gapMs ms")
        }
    }

    @Test
    fun aLinkTheBikeEndsCarriesWhatArrived() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.connection { it.state == ConnectionState.connected }
            scenario.linkUps = false
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.reconnecting }
            val down = scenario.archive.log.first { it.event == "linkDown" }
            assertEquals("peerOrSystem", down.text("initiator"))
            assertNotNull(down.integer("notifications"))
            assertNotNull(down.integer("droppedReplies"))
        }
    }

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
