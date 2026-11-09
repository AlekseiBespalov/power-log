package app.powerlog.runtime.dev

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.PlotView
import app.powerlog.application.connection.ConnectionView
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.bridge.ScriptedApi
import app.powerlog.runtime.bridge.WireCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher

class SliceRunTest {
    private object NoLog : DiagnosticLogRepository {
        override suspend fun append(line: LogLine) {}

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
    }

    private val clock = FakeClock()
    private val rideThread = Executors.newSingleThreadExecutor { Thread(it, "ride") }
    private val callers = mutableSetOf<String>()
    private val json = WireCodec(clock).json
    private val api = ScriptedApi()
    private val ride = "11111111-1111-4111-8111-111111111111"

    private val availability = """{"enabled":true,"reason":null}"""
    private val allowed =
        """{"startSearch":$availability,"stopSearch":$availability,"connect":$availability,"disconnect":$availability}"""
    private val searching =
        """{"state":"searching","display":"live","heldUntilMonoUs":null,"bike":null,"found":[{"bikeId":"${DemoBike.BIKE}","advertisedName":"${DemoBike.NAME}","known":null}],"faultCode":null,"failure":null,"allowed":$allowed}"""
    private val connected =
        """{"state":"connected","display":"live","heldUntilMonoUs":null,"bike":{"bikeId":"${DemoBike.BIKE}","advertisedName":null,"identity":{"decoder":"cyc","model":"X6","firmware":"20250725","protocol":"5.3"},"controllerSpeed":true},"found":[],"faultCode":null,"failure":null,"allowed":$allowed}"""
    private val running =
        """{"phase":"running","ride":{"rideId":"$ride","options":{"sampleRate":"8","indoor":true,"useWatch":false,"saveToHealth":false,"recordGps":false,"distanceSource":"controller","batteryMethod":"controller"}},"pending":null,"timer":{"state":"running","baseUs":0,"originMonoUs":1000000},"allowed":{"start":$availability,"pause":$availability,"resume":$availability,"lap":$availability,"finish":$availability,"discard":$availability},"finishRequest":null,"sources":{"controller":"receiving","heartRate":"off","gps":"off"},"health":null,"otherTab":false,"failure":null}"""
    private val listed =
        """{"rows":[{"rideId":"$ride","startedUtcUs":1793523600000000,"elapsedUs":60000000,"phase":"completed","interrupted":false,"indoor":true,"example":false,"health":null,"open":$availability,"delete":$availability}],"next":null}"""
    private val plotted =
        """{"revision":1,"rangeUs":{"startUs":0,"endUs":60000000},"slotUs":1000000,"series":[{"metric":"humanPowerW","segment":[0],"firstUs":[0],"first":[1.0],"minUs":[0],"min":[0.0],"maxUs":[0],"max":[1.0],"lastUs":[0],"last":[1.0]}]}"""

    private val stale = running.replace("\"controller\":\"receiving\"", "\"controller\":\"stale\"")

    private fun answerTheSlice(recording: (reads: Int) -> String = { running }) {
        var reads = 0
        api.immediate = { op, _ ->
            callers += Thread.currentThread().name.substringBefore(" @")
            when (op) {
                "history.page" -> Outcome.Ok(json.decodeFromString(HistoryPage.serializer(), listed))
                "charts.plot" -> Outcome.Ok(json.decodeFromString(PlotView.serializer(), plotted))
                else -> Outcome.Ok(Unit)
            }
        }
        api.immediateNext = { stream, after ->
            when (stream) {
                "connection.state" ->
                    StreamAnswer.Value(
                        (after ?: 0) + 1,
                        json.decodeFromString(ConnectionView.serializer(), if (after == null) searching else connected),
                    )
                "recording.state" ->
                    StreamAnswer.Value(
                        (after ?: 0) + 1,
                        json.decodeFromString(RecordingView.serializer(), recording(++reads)),
                    )
                else -> null
            }
        }
    }

    private fun runSlice(framesMs: Long = 0L, sampleMs: Long = 1L): String {
        val printed = ByteArrayOutputStream()
        val out = System.out
        System.setOut(PrintStream(printed, true))
        try {
            val session = BridgeSession(api, WireCodec(clock), clock, NoLog)
            SliceRun(session, clock, rideThread.asCoroutineDispatcher(), framesMs, sampleMs).start()
            val deadline = System.currentTimeMillis() + 10_000
            while (!printed.toString().contains("[power-log] slice") && System.currentTimeMillis() < deadline) Thread
                .sleep(10)
        } finally {
            System.setOut(out)
        }
        return printed.toString().trim()
    }

    @AfterTest
    fun stopTheRideThread() {
        rideThread.shutdownNow()
    }

    @Test
    fun theSliceConnectsRecordsFinishesAndFindsTheRideAndItsChart() {
        answerTheSlice()
        assertEquals("[power-log] slice ok", runSlice())
        assertEquals(setOf("ride"), callers)
        val ops = api.calls.map { it.op }.distinct()
        assertEquals(
            listOf(
                "connection.startSearch",
                "connection.connect",
                "settings.change",
                "recording.start",
                "recording.control",
                "history.page",
                "charts.plot",
            ),
            ops,
        )
        val settings = api.calls.map { it.input }.filterIsInstance<SettingsChange>()
        assertEquals(5, settings.size)
        assertTrue(SettingsChange.RecordGpsTo(false) in settings)
        assertEquals(
            RideAction.finish,
            (api.calls.single { it.op == "recording.control" }.input as ControlInput).action,
        )
        assertEquals(
            "humanPowerW",
            (api.calls.first { it.op == "charts.plot" }.input as PlotInput).metrics.single().name,
        )
    }

    @Test
    fun theFramesStepSamplesTheRecordingStateUntilTheFramesTimeIsUp() {
        answerTheSlice()
        assertEquals("[power-log] slice ok", runSlice(framesMs = 40L, sampleMs = 10L))
        assertEquals(6, api.nexts.count { it.stream == "recording.state" })
    }

    @Test
    fun aControllerThatStopsReceivingDuringTheFramesFailsThem() {
        answerTheSlice { reads -> if (reads <= 3) running else stale }
        assertEquals("[power-log] slice failed frames", runSlice(framesMs = 40L, sampleMs = 10L))
    }

    @Test
    fun aRefusedStepIsNamedInTheFailureLine() {
        api.immediate = { op, _ ->
            if (op == "connection.startSearch") Outcome.Failed(ApiFailure(FailureCode.unknown, false, null, null))
            else null
        }
        assertEquals("[power-log] slice failed startSearch", runSlice())
    }
}
