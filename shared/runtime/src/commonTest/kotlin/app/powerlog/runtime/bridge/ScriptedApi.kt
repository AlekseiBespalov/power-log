@file:Suppress("UNCHECKED_CAST")

package app.powerlog.runtime.bridge

import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.CancelInput
import app.powerlog.application.api.DeviceApi
import app.powerlog.application.api.DeviceInfoView
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.api.RideInput
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartHeadView
import app.powerlog.application.charts.ChartsApi
import app.powerlog.application.charts.InspectInput
import app.powerlog.application.charts.InspectView
import app.powerlog.application.charts.LatestInput
import app.powerlog.application.charts.LatestView
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.PlotView
import app.powerlog.application.charts.StatisticsInput
import app.powerlog.application.charts.StatisticsView
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.connection.ConnectionApi
import app.powerlog.application.connection.ConnectionView
import app.powerlog.application.diagnostics.DiagnosticsApi
import app.powerlog.application.diagnostics.DiagnosticsPage
import app.powerlog.application.diagnostics.DiagnosticsReadInput
import app.powerlog.application.diagnostics.DiagnosticsView
import app.powerlog.application.diagnostics.TraceDocument
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.exports.ExportPageInput
import app.powerlog.application.exports.ExportSessionView
import app.powerlog.application.exports.ExportsApi
import app.powerlog.application.health.HealthApi
import app.powerlog.application.history.AddedRidesView
import app.powerlog.application.history.HistoryApi
import app.powerlog.application.history.HistoryListView
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.history.RideDetailView
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.ImportsApi
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.recording.PermissionsView
import app.powerlog.application.recording.RecordingApi
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.settings.SettingsApi
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.settings.SettingsView
import kotlinx.coroutines.CompletableDeferred

internal class ScriptedApi : PowerLogApi {
    class Call(val op: String, val input: Any, val key: String?) {
        val answer = CompletableDeferred<Outcome<Any?>>()
    }

    class Next(val stream: String, val params: Any, val after: Long?, val key: String) {
        val answer = CompletableDeferred<StreamAnswer<Any?>>()
    }

    val calls = mutableListOf<Call>()
    val nexts = mutableListOf<Next>()
    val cancelled = mutableListOf<String>()

    var immediate: (op: String, input: Any) -> Outcome<Any?>? = { _, _ -> null }

    var immediateNext: (stream: String, after: Long?) -> StreamAnswer<Any?>? = { _, _ -> null }

    fun pending(op: String): Call = calls.single { it.op == op && !it.answer.isCompleted }

    override suspend fun cancel(input: CancelInput): Outcome<Unit> {
        cancelled += input.key
        for (call in calls) if (call.key == input.key) call.answer.complete(Outcome.Cancelled)
        for (next in nexts) if (next.key == input.key) next.answer.complete(StreamAnswer.Cancelled)
        return Outcome.Ok(Unit)
    }

    private suspend fun <T> answer(op: String, input: Any, key: String? = null): Outcome<T> {
        val call = Call(op, input, key)
        calls += call
        immediate(op, input)?.let {
            return it as Outcome<T>
        }
        return call.answer.await() as Outcome<T>
    }

    private fun <P : Any, T> stream(name: String): ApiStream<P, T> =
        object : ApiStream<P, T> {
            override suspend fun next(params: P, after: Long?, key: String): StreamAnswer<T> {
                val next = Next(name, params, after, key)
                nexts += next
                immediateNext(name, after)?.let {
                    return it as StreamAnswer<T>
                }
                return next.answer.await() as StreamAnswer<T>
            }
        }

    override val device: DeviceApi =
        object : DeviceApi {
            override val info: ApiStream<Empty, DeviceInfoView> = stream("device.info")
        }

    override val connection: ConnectionApi =
        object : ConnectionApi {
            override val state: ApiStream<Empty, ConnectionView> = stream("connection.state")

            override suspend fun startSearch(input: Empty): Outcome<Unit> = answer("connection.startSearch", input)

            override suspend fun stopSearch(input: Empty): Outcome<Unit> = answer("connection.stopSearch", input)

            override suspend fun connect(input: ConnectInput): Outcome<Unit> = answer("connection.connect", input)

            override suspend fun disconnect(input: Empty): Outcome<Unit> = answer("connection.disconnect", input)
        }

    override val recording: RecordingApi =
        object : RecordingApi {
            override val state: ApiStream<Empty, RecordingView> = stream("recording.state")
            override val permissions: ApiStream<Empty, PermissionsView> = stream("recording.permissions")

            override suspend fun start(input: Empty): Outcome<Unit> = answer("recording.start", input)

            override suspend fun cancelStart(input: Empty): Outcome<Unit> = answer("recording.cancelStart", input)

            override suspend fun control(input: ControlInput): Outcome<Unit> = answer("recording.control", input)

            override suspend fun testBeep(input: Empty): Outcome<Unit> = answer("recording.testBeep", input)

            override suspend fun requestPermissions(input: Empty): Outcome<Unit> =
                answer("recording.requestPermissions", input)

            override suspend fun refreshPermissions(input: Empty): Outcome<Unit> =
                answer("recording.refreshPermissions", input)
        }

    override val history: HistoryApi =
        object : HistoryApi {
            override val list: ApiStream<Empty, HistoryListView> = stream("history.list")
            override val ride: ApiStream<RideInput, RideDetailView> = stream("history.ride")

            override suspend fun page(input: HistoryPageInput): Outcome<HistoryPage> =
                answer("history.page", input, input.key)

            override suspend fun delete(input: RideInput): Outcome<Unit> = answer("history.delete", input)

            override suspend fun addExampleRides(input: Empty): Outcome<AddedRidesView> =
                answer("history.addExampleRides", input)
        }

    override val charts: ChartsApi =
        object : ChartsApi {
            override val head: ApiStream<ChartHeadInput, ChartHeadView> = stream("charts.head")

            override suspend fun plot(input: PlotInput): Outcome<PlotView> = answer("charts.plot", input, input.key)

            override suspend fun latest(input: LatestInput): Outcome<LatestView> =
                answer("charts.latest", input, input.key)

            override suspend fun inspect(input: InspectInput): Outcome<InspectView> =
                answer("charts.inspect", input, input.key)

            override suspend fun statistics(input: StatisticsInput): Outcome<StatisticsView> =
                answer("charts.statistics", input, input.key)
        }

    override val exports: ExportsApi =
        object : ExportsApi {
            override suspend fun open(input: ExportOpenInput): Outcome<ExportSessionView> =
                answer("exports.open", input)

            override suspend fun close(input: ExportCloseInput): Outcome<Unit> = answer("exports.close", input)

            override suspend fun page(input: ExportPageInput): Outcome<ExportPage> = answer("exports.page", input)
        }

    override val imports: ImportsApi =
        object : ImportsApi {
            override suspend fun open(input: KeyInput): Outcome<ImportedRideView> =
                answer("imports.open", input, input.key)

            override suspend fun close(input: ImportInput): Outcome<Unit> = answer("imports.close", input)
        }

    override val health: HealthApi =
        object : HealthApi {
            override suspend fun refresh(input: RideInput): Outcome<Unit> = answer("health.refresh", input)

            override suspend fun retrySave(input: RideInput): Outcome<Unit> = answer("health.retrySave", input)
        }

    override val settings: SettingsApi =
        object : SettingsApi {
            override val state: ApiStream<Empty, SettingsView> = stream("settings.state")

            override suspend fun change(input: SettingsChange): Outcome<Unit> = answer("settings.change", input)
        }

    override val diagnostics: DiagnosticsApi =
        object : DiagnosticsApi {
            override val state: ApiStream<Empty, DiagnosticsView> = stream("diagnostics.state")

            override suspend fun startTrace(input: Empty): Outcome<Unit> = answer("diagnostics.startTrace", input)

            override suspend fun stopTrace(input: Empty): Outcome<Unit> = answer("diagnostics.stopTrace", input)

            override suspend fun clearTrace(input: Empty): Outcome<Unit> = answer("diagnostics.clearTrace", input)

            override suspend fun readTrace(input: Empty): Outcome<TraceDocument> =
                answer("diagnostics.readTrace", input)

            override suspend fun readLog(input: DiagnosticsReadInput): Outcome<DiagnosticsPage> =
                answer("diagnostics.readLog", input)
        }
}
