package app.powerlog.application.api

import app.powerlog.application.Executors
import app.powerlog.application.Logs
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartHeadView
import app.powerlog.application.charts.Charts
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
import app.powerlog.application.connection.ControllerConnection
import app.powerlog.application.diagnostics.Diagnostics
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
import app.powerlog.application.exports.Exports
import app.powerlog.application.exports.ExportsApi
import app.powerlog.application.health.Health
import app.powerlog.application.health.HealthApi
import app.powerlog.application.history.AddedRidesView
import app.powerlog.application.history.ExampleRides
import app.powerlog.application.history.History
import app.powerlog.application.history.HistoryApi
import app.powerlog.application.history.HistoryListView
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.history.RideDetailView
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.Imports
import app.powerlog.application.imports.ImportsApi
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.recording.LivePublisher
import app.powerlog.application.recording.Permissions
import app.powerlog.application.recording.PermissionsView
import app.powerlog.application.recording.RecordingApi
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.recording.RideRecorder
import app.powerlog.application.settings.Settings
import app.powerlog.application.settings.SettingsApi
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.settings.SettingsView
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.recording.CommandIssuer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal class ApiFacade(
    private val executors: Executors,
    private val gate: LaunchGate,
    private val keys: Keys,
    private val deviceInfo: Published<DeviceInfoView>,
    private val connectionUseCases: ControllerConnection,
    private val recorder: RideRecorder,
    private val permissions: Permissions,
    private val live: LivePublisher,
    private val historyUseCases: History,
    private val exampleRides: ExampleRides,
    private val chartsUseCases: Charts,
    private val importsUseCases: Imports,
    private val healthUseCases: Health,
    private val settingsUseCases: Settings,
    private val diagnosticsUseCases: Diagnostics,
    private val logs: Logs,
    private val exportsUseCases: Exports,
) : PowerLogApi {
    private suspend fun <T> call(block: suspend () -> Outcome<T>): Outcome<T> {
        gate.failure()?.let {
            return Outcome.Failed(it)
        }
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logs.unexpected("ApiFacade", error)
            Outcome.Failed(failure(FailureCode.unknown))
        }
    }

    private suspend fun <T> onRide(block: suspend () -> Outcome<T>): Outcome<T> = call {
        withContext(executors.ride) { block() }
    }

    private suspend fun <T> keyed(key: String, block: suspend () -> Outcome<T>): Outcome<T> = call {
        keys.run(key, Outcome.Cancelled) { block() }
    }

    private suspend fun <T> stream(gated: Boolean, key: String, next: suspend () -> StreamAnswer<T>): StreamAnswer<T> {
        if (gated)
            gate.failure()?.let {
                return StreamAnswer.Failed(it)
            }
        return try {
            keys.run(key, StreamAnswer.Cancelled) { next() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logs.unexpected("ApiFacade", error)
            StreamAnswer.Failed(failure(FailureCode.unknown))
        }
    }

    private fun <P, T> published(gated: Boolean, source: () -> Published<T>): ApiStream<P, T> =
        object : ApiStream<P, T> {
            override suspend fun next(params: P, after: Long?, key: String): StreamAnswer<T> =
                stream(gated, key) { source().next(after) }
        }

    override val device: DeviceApi =
        object : DeviceApi {
            override val info: ApiStream<Empty, DeviceInfoView> = published(gated = false) { deviceInfo }
        }

    override val connection: ConnectionApi =
        object : ConnectionApi {
            override val state: ApiStream<Empty, ConnectionView> = published(gated = true) { connectionUseCases.stream }

            override suspend fun startSearch(input: Empty): Outcome<Unit> = onRide { connectionUseCases.startSearch() }

            override suspend fun stopSearch(input: Empty): Outcome<Unit> = onRide { connectionUseCases.stopSearch() }

            override suspend fun connect(input: ConnectInput): Outcome<Unit> = onRide {
                connectionUseCases.connect(input.bikeId)
            }

            override suspend fun disconnect(input: Empty): Outcome<Unit> = onRide { connectionUseCases.disconnect() }
        }

    override val recording: RecordingApi =
        object : RecordingApi {
            override val state: ApiStream<Empty, RecordingView> = published(gated = true) { live.stream }

            override val permissions: ApiStream<Empty, PermissionsView> =
                object : ApiStream<Empty, PermissionsView> {
                    override suspend fun next(params: Empty, after: Long?, key: String): StreamAnswer<PermissionsView> =
                        stream(gated = true, key) { this@ApiFacade.permissions.next(after) }
                }

            override suspend fun start(input: Empty): Outcome<Unit> = onRide { recorder.start() }

            override suspend fun cancelStart(input: Empty): Outcome<Unit> = onRide { recorder.cancelStart(null) }

            override suspend fun control(input: ControlInput): Outcome<Unit> = onRide {
                recorder.control(input.rideId, input.action, CommandIssuer.rider, EventId(newId()))
            }

            override suspend fun testBeep(input: Empty): Outcome<Unit> = onRide { recorder.testBeep() }

            override suspend fun requestPermissions(input: Empty): Outcome<Unit> = onRide {
                this@ApiFacade.permissions.request()
            }

            override suspend fun refreshPermissions(input: Empty): Outcome<Unit> = onRide {
                this@ApiFacade.permissions.refresh()
                Outcome.Ok(Unit)
            }
        }

    override val history: HistoryApi =
        object : HistoryApi {
            override val list: ApiStream<Empty, HistoryListView> =
                object : ApiStream<Empty, HistoryListView> {
                    override suspend fun next(params: Empty, after: Long?, key: String): StreamAnswer<HistoryListView> =
                        stream(gated = true, key) { historyUseCases.list(after) }
                }

            override val ride: ApiStream<RideInput, RideDetailView> =
                object : ApiStream<RideInput, RideDetailView> {
                    override suspend fun next(
                        params: RideInput,
                        after: Long?,
                        key: String,
                    ): StreamAnswer<RideDetailView> = stream(gated = true, key) { historyUseCases.ride(params, after) }
                }

            override suspend fun page(input: HistoryPageInput): Outcome<HistoryPage> =
                keyed(input.key) { historyUseCases.page(input) }

            override suspend fun delete(input: RideInput): Outcome<Unit> = call { historyUseCases.delete(input.rideId) }

            override suspend fun addExampleRides(input: Empty): Outcome<AddedRidesView> = call { exampleRides.add() }
        }

    override val charts: ChartsApi =
        object : ChartsApi {
            override val head: ApiStream<ChartHeadInput, ChartHeadView> =
                object : ApiStream<ChartHeadInput, ChartHeadView> {
                    override suspend fun next(
                        params: ChartHeadInput,
                        after: Long?,
                        key: String,
                    ): StreamAnswer<ChartHeadView> = stream(gated = true, key) { chartsUseCases.head(params, after) }
                }

            override suspend fun plot(input: PlotInput): Outcome<PlotView> =
                keyed(input.key) { chartsUseCases.plot(input) }

            override suspend fun latest(input: LatestInput): Outcome<LatestView> =
                keyed(input.key) { chartsUseCases.latest(input) }

            override suspend fun inspect(input: InspectInput): Outcome<InspectView> =
                keyed(input.key) { chartsUseCases.inspect(input) }

            override suspend fun statistics(input: StatisticsInput): Outcome<StatisticsView> =
                keyed(input.key) { chartsUseCases.statistics(input) }
        }

    override val exports: ExportsApi =
        object : ExportsApi {
            override suspend fun open(input: ExportOpenInput): Outcome<ExportSessionView> = call {
                exportsUseCases.open(input)
            }

            override suspend fun close(input: ExportCloseInput): Outcome<Unit> = call { exportsUseCases.close(input) }

            override suspend fun page(input: ExportPageInput): Outcome<ExportPage> = call {
                exportsUseCases.page(input)
            }
        }

    override val imports: ImportsApi =
        object : ImportsApi {
            override suspend fun open(input: KeyInput): Outcome<ImportedRideView> =
                keyed(input.key) { importsUseCases.open() }

            override suspend fun close(input: ImportInput): Outcome<Unit> = call {
                importsUseCases.close(input.importId)
            }
        }

    override val health: HealthApi =
        object : HealthApi {
            override suspend fun refresh(input: RideInput): Outcome<Unit> = call {
                healthUseCases.refresh(input.rideId)
            }

            override suspend fun retrySave(input: RideInput): Outcome<Unit> = call {
                healthUseCases.retrySave(input.rideId)
            }
        }

    override val settings: SettingsApi =
        object : SettingsApi {
            override val state: ApiStream<Empty, SettingsView> = published(gated = true) { settingsUseCases.stream }

            override suspend fun change(input: SettingsChange): Outcome<Unit> = onRide {
                settingsUseCases.change(input)
            }
        }

    override val diagnostics: DiagnosticsApi =
        object : DiagnosticsApi {
            override val state: ApiStream<Empty, DiagnosticsView> =
                published(gated = true) { diagnosticsUseCases.stream }

            override suspend fun startTrace(input: Empty): Outcome<Unit> = onRide {
                diagnosticsUseCases.start()
                Outcome.Ok(Unit)
            }

            override suspend fun stopTrace(input: Empty): Outcome<Unit> = onRide {
                diagnosticsUseCases.stop()
                Outcome.Ok(Unit)
            }

            override suspend fun clearTrace(input: Empty): Outcome<Unit> = onRide {
                diagnosticsUseCases.clear()
                Outcome.Ok(Unit)
            }

            override suspend fun readTrace(input: Empty): Outcome<TraceDocument> = call {
                diagnosticsUseCases.readTrace()
            }

            override suspend fun readLog(input: DiagnosticsReadInput): Outcome<DiagnosticsPage> = call {
                diagnosticsUseCases.readLog(input.after)
            }
        }

    override suspend fun cancel(input: CancelInput): Outcome<Unit> {
        gate.refused?.let {
            return Outcome.Failed(failure(FailureCode.storageRefused, it))
        }
        keys.cancel(input.key)
        return Outcome.Ok(Unit)
    }
}
