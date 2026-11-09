package app.powerlog.runtime.dev

import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.PlotView
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.connection.ConnectionView
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.port.Clock
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.recording.SourceState
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.bridge.WireCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal class SliceRun(
    private val session: BridgeSession,
    clock: Clock,
    ride: CoroutineDispatcher,
    private val framesMs: Long = FRAMES_MS,
    private val sampleMs: Long = SAMPLE_MS,
) {
    private class StepFailed(val step: String) : Exception(step)

    private val json = WireCodec(clock).json
    private val scope = CoroutineScope(SupervisorJob() + ride)

    fun start() {
        scope.launch {
            val failed =
                try {
                    run()
                    null
                } catch (failure: StepFailed) {
                    failure.step
                }
            println(if (failed == null) "[power-log] slice ok" else "[power-log] slice failed $failed")
        }
    }

    private suspend fun run() {
        step("startSearch") { ok(call("connection.startSearch", "{}")) }
        val bike =
            step("search") {
                awaitState("connection.state", ConnectionView.serializer()) { view ->
                    view.found.firstOrNull { it.bikeId.value == DemoBike.BIKE }
                }
            }
        step("connect") { ok(call("connection.connect", encode(ConnectInput.serializer(), ConnectInput(bike.bikeId)))) }
        step("identity") {
            awaitState("connection.state", ConnectionView.serializer()) { view ->
                view.bike?.identity.takeIf { view.state == ConnectionState.connected }
            }
        }
        val changes =
            listOf(
                SettingsChange.Toggle(RideToggle.indoor, true),
                SettingsChange.RecordGpsTo(false),
                SettingsChange.SampleRateTo(SampleRate.hz8),
                SettingsChange.Toggle(RideToggle.useWatch, false),
                SettingsChange.Toggle(RideToggle.saveToHealth, false),
            )
        for (change in changes) step("settings") {
            ok(call("settings.change", encode(SettingsChange.serializer(), change)))
        }
        step("start") { ok(call("recording.start", "{}")) }
        val ride =
            step("running") {
                awaitState("recording.state", RecordingView.serializer()) { view ->
                    view.ride?.rideId.takeIf { view.phase == RidePhase.running }
                }
            }
        step("frames", framesMs + STEP_MS) {
            awaitState("recording.state", RecordingView.serializer()) { view -> Unit.takeIf { receiving(view) } }
            repeat((framesMs / sampleMs).toInt()) {
                delay(sampleMs)
                val view = value(next("recording.state", CURRENT_RECORDING), RecordingView.serializer())
                if (view == null || !receiving(view)) return@step null
            }
            Unit
        }
        step("finish") {
            ok(call("recording.control", encode(ControlInput.serializer(), ControlInput(ride, RideAction.finish))))
        }
        step("history") { poll { listed(ride) } }
        step("chart") { poll { plotted(ride) } }
    }

    private suspend fun <T : Any> step(name: String, timeoutMs: Long = STEP_MS, work: suspend () -> T?): T {
        val value =
            try {
                withTimeoutOrNull(timeoutMs) { work() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        return value ?: throw StepFailed(name)
    }

    private suspend fun listed(ride: RideId): Unit? {
        val input = HistoryPageInput(after = null, key = "slice:history")
        val page = value(call("history.page", encode(HistoryPageInput.serializer(), input)), HistoryPage.serializer())
        return Unit.takeIf { page?.rows.orEmpty().any { it.rideId == ride && it.phase == RidePhase.completed } }
    }

    private suspend fun plotted(ride: RideId): Unit? {
        val input =
            PlotInput(
                source = ChartSource.Ride(ride),
                metrics = listOf(MetricId.humanPowerW),
                key = "slice:chart",
                rangeUs = null,
                buckets = PLOT_BUCKETS,
                smoothingUs = 0L,
            )
        val plot = value(call("charts.plot", encode(PlotInput.serializer(), input)), PlotView.serializer())
        return Unit.takeIf {
            plot?.series.orEmpty().any { it.metric == MetricId.humanPowerW && it.firstUs.isNotEmpty() }
        }
    }

    private suspend fun <T : Any> poll(probe: suspend () -> T?): T {
        while (true) {
            probe()?.let {
                return it
            }
            delay(POLL_MS)
        }
    }

    private suspend fun <V, T : Any> awaitState(stream: String, serializer: KSerializer<V>, wanted: (V) -> T?): T {
        var after: Long? = null
        while (true) {
            val answer = envelope(next(stream, """{"params":{},"after":$after,"key":"slice:$stream"}"""))
            if (answer["ok"]?.jsonPrimitive?.boolean != true) {
                if (answer["cancelled"] != null || !retryable(answer)) throw StepFailed(stream)
                delay(POLL_MS)
                continue
            }
            after = answer.getValue("revision").jsonPrimitive.long
            wanted(json.decodeFromJsonElement(serializer, answer.getValue("value")))?.let {
                return it
            }
        }
    }

    private fun receiving(view: RecordingView): Boolean =
        view.phase == RidePhase.running && view.sources.controller == SourceState.receiving

    private fun retryable(answer: JsonObject): Boolean =
        (answer["failure"]?.jsonObject?.get("retryable") as? JsonPrimitive)?.booleanOrNull == true

    private fun ok(answer: String): Unit? = Unit.takeIf { envelope(answer)["ok"]?.jsonPrimitive?.boolean == true }

    private fun <T> value(answer: String, serializer: KSerializer<T>): T? {
        val envelope = envelope(answer)
        if (envelope["ok"]?.jsonPrimitive?.boolean != true) return null
        return json.decodeFromJsonElement(serializer, envelope.getValue("value"))
    }

    private fun envelope(answer: String): JsonObject = json.parseToJsonElement(answer).jsonObject

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private suspend fun call(op: String, request: String): String = suspendCancellableCoroutine { answered ->
        session.call(op, request) { answered.resume(it) }
    }

    private suspend fun next(stream: String, request: String): String = suspendCancellableCoroutine { answered ->
        session.next(stream, request) { answered.resume(it) }
    }

    internal companion object {
        const val FRAMES_MS: Long = 60_000L
        const val STEP_MS: Long = 30_000L
        const val POLL_MS: Long = 500L
        const val SAMPLE_MS: Long = 1_000L
        const val CURRENT_RECORDING: String = """{"params":{},"after":null,"key":"slice:recording.state"}"""
        const val PLOT_BUCKETS: Int = 64
    }
}
