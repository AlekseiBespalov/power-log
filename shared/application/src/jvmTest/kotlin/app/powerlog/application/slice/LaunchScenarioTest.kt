package app.powerlog.application.slice

import app.powerlog.application.CommitPolicy
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.CancelInput
import app.powerlog.application.api.DeviceInfoView
import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.api.RideInput
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.charts.InspectInput
import app.powerlog.application.charts.LatestInput
import app.powerlog.application.charts.PlotInput
import app.powerlog.application.charts.StatisticsInput
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.diagnostics.DiagnosticsReadInput
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportKind
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportPageInput
import app.powerlog.application.exports.ExportSource
import app.powerlog.application.fakes.FakeBackgroundScheduler
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.history.HistoryPageInput
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.launch.LaunchPolicy
import app.powerlog.application.port.ExitReport
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.recording.ControlInput
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class LaunchScenarioTest {
    @Test
    fun aRefusedStoreRefusesEveryOperationButDeviceInfoAndItsHealthWorkFails() {
        val opens =
            listOf(
                StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unreadable)) to RefusalDetail.unreadable,
                StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unavailable)) to RefusalDetail.unavailable,
                StoreResult.Failed(StorageError(StorageError.Kind.io)) to RefusalDetail.unavailable,
                StoreResult.Failed(StorageError(StorageError.Kind.full)) to RefusalDetail.unavailable,
            )
        for ((open, detail) in opens) {
            val scenario = Scenario()
            scenario.archive.onOpen = { open }
            scenario.test {
                assertEquals(LaunchResult.Refused(detail), scenario.launch())
                for ((operation, failure) in everyOperation(scenario.api)) assertEquals(
                    FailureCode.storageRefused to detail,
                    failure?.let { it.code to it.detail },
                    operation,
                )
                val info =
                    assertIs<StreamAnswer.Value<DeviceInfoView>>(scenario.api.device.info.next(Empty, null, "device"))
                assertFalse(info.value.capabilities.recording)
                assertEquals(HealthWorkResult.failure, scenario.application.host.runHealthWork("ride", 0))
            }
        }
    }

    @Test
    fun aCallAndHealthWorkWaitingAtTheGateAnswerStorageBusyAndRetryAfterItsLimit() {
        val scenario = Scenario()
        scenario.archive.onOpen = {
            delay(31_000)
            null
        }
        scenario.test {
            val launch = async { scenario.launch() }
            val call = async { scenario.api.settings.change(SettingsChange.Toggle(RideToggle.indoor, true)) }
            val work = async { scenario.application.host.runHealthWork("ride", 0) }
            runCurrent()
            delay(LaunchPolicy.gateUs / 1_000 - 1)
            assertFalse(call.isCompleted || work.isCompleted)
            delay(1)
            runCurrent()
            assertEquals(FailureCode.storageBusy, (call.await() as Outcome.Failed).failure.code)
            assertEquals(HealthWorkResult.retry, work.await())
            assertEquals(LaunchResult.Ready, launch.await())
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.Toggle(RideToggle.indoor, true)))
        }
    }

    @Test
    fun aCorruptCommitAnswersStorageRefusedForItAndEveryLaterCall() {
        val unreadable = FailureCode.storageRefused to RefusalDetail.unreadable
        val corrupt = StoreResult.Failed(StorageError(StorageError.Kind.corrupt))

        val starting = Scenario()
        starting.test {
            assertEquals(LaunchResult.Ready, starting.launch())
            with(starting) { connect() }
            starting.indoor()
            starting.archive.onCommit = { batch -> corrupt.takeIf { batch.mutations.first() is Mutation.CreateRide } }
            assertEquals(unreadable, starting.api.recording.start(Empty).refusal())
            for ((operation, failure) in everyOperation(starting.api)) assertEquals(
                unreadable,
                failure?.let { it.code to it.detail },
                operation,
            )
        }

        val recording = Scenario()
        recording.test {
            with(recording) { riding() }
            recording.archive.onCommit = { batch ->
                corrupt.takeIf { batch.mutations.first() is Mutation.AppendTelemetry }
            }
            delay(2_000)
            for ((operation, failure) in everyOperation(recording.api)) assertEquals(
                unreadable,
                failure?.let { it.code to it.detail },
                operation,
            )
        }
    }

    @Test
    fun aRunningRideAtLaunchIsInterruptedOrStopsLaunchBehindTheRefusalGate() {
        val interrupted = Scenario()
        interrupted.test {
            val ride = interrupted.seedOpenRide(RidePhase.running)
            var busy = true
            interrupted.archive.onOpenRide = {
                if (busy) StoreResult.Failed(StorageError(StorageError.Kind.busy)).also { busy = false } else null
            }
            assertEquals(LaunchResult.Ready, interrupted.launch())
            assertEquals(CommitPolicy.busyRetryUs, interrupted.clock.monoUs())
            assertEquals(listOf(Mutation.SetPhase(ride.id, RidePhase.interrupted)), interrupted.mutations())
            val view = interrupted.recording()
            assertEquals(RidePhase.interrupted, view.phase)
            assertEquals(ride.id, view.ride?.rideId)
        }

        val full = Scenario()
        full.test {
            full.seedOpenRide(RidePhase.running)
            full.archive.onCommit = { StoreResult.Failed(StorageError(StorageError.Kind.full)) }
            assertEquals(LaunchResult.Refused(RefusalDetail.unavailable), full.launch())
            assertEquals(
                FailureCode.storageRefused to RefusalDetail.unavailable,
                full.api.recording.start(Empty).refusal(),
            )
        }

        for ((kind, detail) in
            listOf(
                StorageError.Kind.io to RefusalDetail.unavailable,
                StorageError.Kind.constraint to RefusalDetail.unreadable,
            )) {
            val unread = Scenario()
            unread.test {
                unread.seedOpenRide(RidePhase.running)
                unread.archive.onOpenRide = { StoreResult.Failed(StorageError(kind)) }
                assertEquals(LaunchResult.Refused(detail), unread.launch(), kind.name)
                assertEquals(emptyList(), unread.mutations(), kind.name)
            }
        }
    }

    @Test
    fun enteringTheBackgroundCommitsTheInboxOutstandingBatchFirstThenCheckpoints() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            val held = CompletableDeferred<Unit>()
            scenario.archive.onCommit = { batch ->
                if (batch.mutations.first() is Mutation.AppendTelemetry && !held.isCompleted) held.await()
                null
            }
            delay(1_500)
            scenario.archive.journal.clear()
            scenario.archive.onCommit = { null }
            val background = async { scenario.application.host.didEnterBackground() }
            runCurrent()
            assertFalse(background.isCompleted)
            held.complete(Unit)
            background.await()
            assertEquals(
                listOf("AppendTelemetry, Checkpoint", "AppendTelemetry, Checkpoint", "checkpoint(truncate = false)"),
                scenario.archive.journal,
            )
        }
    }

    @Test
    fun anAndroidLaunchLogsItsLineThenItsExitAndCrashLinesAndSchedulesDueHealthWorkOnce() {
        val scenario = Scenario(Platform.android)
        val crash =
            LogLine(
                LogLevel.error,
                1,
                1,
                "crash",
                listOf(LogField("exception", LogValue.Text("IllegalStateException"))),
            )
        val pending = mutableListOf(crash)
        scenario.device.previousExit = ExitReport(1_000, "lowMemory", 0, 100)
        scenario.crashLines =
            object : CrashLineSource {
                override fun take(): List<LogLine> = pending.toList().also { pending.clear() }
            }
        val scheduler = FakeBackgroundScheduler()
        scenario.backgroundScheduler = scheduler
        scenario.archive.onDueRides = { StoreResult.Ok(listOf(RideId("due"))) }
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            runCurrent()
            val events = scenario.archive.log.map { it.event }
            assertEquals(listOf("launch", "exit", "crash"), events.filter { it in setOf("launch", "exit", "crash") })
            val launch = scenario.archive.log.single { it.event == "launch" }
            assertEquals(LogValue.Text("ready"), launch.fields.single { it.key == "outcome" }.value)
            assertEquals(LogLevel.error, launch.level)
            assertEquals(listOf(FakeBackgroundScheduler.Work("due", null, ScheduleKind.keep)), scheduler.pending("due"))
        }
    }

    @Test
    fun anExceptionInACallAnswersUnknownAndAppendsAnUnexpectedLine() {
        val scenario = Scenario()
        scenario.exampleRides =
            object : ExampleRideSource {
                override val rideIds: List<RideId>
                    get() = throw IllegalStateException("example rides")

                override fun batch(rideId: RideId, index: Int): WriteBatch? = null
            }
        scenario.test {
            val seeded = scenario.seedRide(endUs = 10_000_000, readingsUs = listOf(0L, 250_000L))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val defect =
                app.powerlog.application.charts.PlotInput(
                    app.powerlog.application.charts.ChartSource.Ride(seeded.ride.id),
                    listOf(app.powerlog.domain.core.metric.MetricId.humanPowerW),
                    "plot",
                    null,
                    0,
                    0,
                )
            assertEquals(FailureCode.unknown, (scenario.api.charts.plot(defect) as Outcome.Failed).failure.code)
            runCurrent()
            val facade =
                scenario.archive.log.filter { line ->
                    line.event == "unexpected" &&
                        line.fields.single { it.key == "component" }.value == LogValue.Text("ApiFacade")
                }
            assertEquals(1, facade.size)
        }
    }

    @Test
    fun aRefusalAfterLaunchTurnsRecordingOffInDeviceInfo() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            scenario.archive.onCommit = { batch ->
                StoreResult.Failed(StorageError(StorageError.Kind.corrupt)).takeIf {
                    batch.mutations.first() is Mutation.CreateRide
                }
            }
            assertEquals(FailureCode.storageRefused, scenario.api.recording.start(Empty).refusal()?.first)
            runCurrent()
            val info =
                assertIs<StreamAnswer.Value<DeviceInfoView>>(scenario.api.device.info.next(Empty, null, "device"))
            assertFalse(info.value.capabilities.recording)
        }
    }

    @Test
    fun aLaunchThatThrowsRefusesTheGateAtOnce() {
        val scenario = Scenario()
        scenario.archive.onOpenRide = { throw IllegalStateException("launch") }
        scenario.test {
            assertEquals(LaunchResult.Refused(RefusalDetail.unavailable), scenario.launch())
            val before = scenario.clock.monoUs()
            assertEquals(
                FailureCode.storageRefused to RefusalDetail.unavailable,
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.indoor, true)).refusal(),
            )
            assertEquals(before, scenario.clock.monoUs())
            runCurrent()
            val line = scenario.archive.log.single { it.event == "unexpected" }
            assertEquals(LogValue.Text("Launch"), line.fields.single { it.key == "component" }.value)
        }
    }

    private fun Outcome<*>.refusal(): Pair<FailureCode, RefusalDetail?>? =
        (this as? Outcome.Failed)?.failure?.let { it.code to it.detail }

    private suspend fun everyOperation(api: PowerLogApi): List<Pair<String, ApiFailure?>> {
        val ride = RideId("ride")
        val source = ChartSource.Ride(ride)
        val metrics = listOf(MetricId.humanPowerW)
        fun Outcome<*>.failed() = (this as? Outcome.Failed)?.failure
        fun StreamAnswer<*>.failed() = (this as? StreamAnswer.Failed)?.failure
        return listOf(
            "connection.state" to api.connection.state.next(Empty, null, "a").failed(),
            "connection.startSearch" to api.connection.startSearch(Empty).failed(),
            "connection.stopSearch" to api.connection.stopSearch(Empty).failed(),
            "connection.connect" to api.connection.connect(ConnectInput(BikeId("D2"))).failed(),
            "connection.disconnect" to api.connection.disconnect(Empty).failed(),
            "recording.state" to api.recording.state.next(Empty, null, "b").failed(),
            "recording.permissions" to api.recording.permissions.next(Empty, null, "c").failed(),
            "recording.start" to api.recording.start(Empty).failed(),
            "recording.control" to api.recording.control(ControlInput(ride, RideAction.finish)).failed(),
            "recording.requestPermissions" to api.recording.requestPermissions(Empty).failed(),
            "recording.refreshPermissions" to api.recording.refreshPermissions(Empty).failed(),
            "history.list" to api.history.list.next(Empty, null, "d").failed(),
            "history.ride" to api.history.ride.next(RideInput(ride), null, "e").failed(),
            "history.page" to api.history.page(HistoryPageInput(null, "f")).failed(),
            "history.delete" to api.history.delete(RideInput(ride)).failed(),
            "history.addExampleRides" to api.history.addExampleRides(Empty).failed(),
            "charts.head" to api.charts.head.next(ChartHeadInput(source), null, "g").failed(),
            "charts.plot" to api.charts.plot(PlotInput(source, metrics, "h", null, 64, 0)).failed(),
            "charts.latest" to api.charts.latest(LatestInput(source, metrics, "i", 0)).failed(),
            "charts.inspect" to api.charts.inspect(InspectInput(source, metrics, "j", 0, null, 0)).failed(),
            "charts.statistics" to
                api.charts.statistics(StatisticsInput(source, metrics, "k", TimeRange(0, 1), 0, false)).failed(),
            "exports.open" to
                api.exports
                    .open(ExportOpenInput(ExportSource.Ride(ride), ExportKind.fit, FitPowerSource.rider))
                    .failed(),
            "exports.close" to api.exports.close(ExportCloseInput("session", null)).failed(),
            "exports.page" to api.exports.page(ExportPageInput("session", "seconds", null)).failed(),
            "imports.open" to api.imports.open(KeyInput("l")).failed(),
            "imports.close" to api.imports.close(ImportInput(ride)).failed(),
            "health.refresh" to api.health.refresh(RideInput(ride)).failed(),
            "health.retrySave" to api.health.retrySave(RideInput(ride)).failed(),
            "settings.state" to api.settings.state.next(Empty, null, "m").failed(),
            "settings.change" to api.settings.change(SettingsChange.Reset).failed(),
            "diagnostics.state" to api.diagnostics.state.next(Empty, null, "n").failed(),
            "diagnostics.startTrace" to api.diagnostics.startTrace(Empty).failed(),
            "diagnostics.stopTrace" to api.diagnostics.stopTrace(Empty).failed(),
            "diagnostics.clearTrace" to api.diagnostics.clearTrace(Empty).failed(),
            "diagnostics.readTrace" to api.diagnostics.readTrace(Empty).failed(),
            "diagnostics.readLog" to api.diagnostics.readLog(DiagnosticsReadInput(null)).failed(),
            "cancel" to api.cancel(CancelInput("a")).failed(),
        )
    }
}
