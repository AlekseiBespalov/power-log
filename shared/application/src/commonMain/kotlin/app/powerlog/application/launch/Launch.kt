package app.powerlog.application.launch

import app.powerlog.application.Deadlines
import app.powerlog.application.Executors
import app.powerlog.application.Logs
import app.powerlog.application.api.DeviceInfoView
import app.powerlog.application.api.HostEntries
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchGate
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.connection.ControllerConnection
import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.health.Health
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.history.History
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.LocationSource
import app.powerlog.application.port.Reply
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.port.StoragePersistence
import app.powerlog.application.port.TabLock
import app.powerlog.application.port.WatchLink
import app.powerlog.application.recording.Capture
import app.powerlog.application.recording.LivePublisher
import app.powerlog.application.recording.OpenRides
import app.powerlog.application.recording.Permissions
import app.powerlog.application.recording.RecordingState
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.application.recording.RideRecorder
import app.powerlog.application.recording.TabLeases
import app.powerlog.application.settings.Settings
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ride.DeviceCapabilities
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.PersistenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class Launch(
    private val executors: Executors,
    private val clock: app.powerlog.application.port.Clock,
    private val gate: LaunchGate,
    private val host: StoreHost,
    private val maintenance: MaintenanceRepository,
    private val settings: Settings,
    private val open: OpenRides,
    private val leases: TabLeases?,
    private val recorder: RideRecorder,
    private val capture: Capture,
    private val live: LivePublisher,
    private val connection: ControllerConnection,
    private val history: History,
    private val permissions: Permissions,
    private val capabilities: Capabilities,
    private val after: AfterGate,
    private val health: Health,
    private val watchOpened: () -> Unit,
    private val deadlines: Deadlines,
) : HostEntries {
    class AfterGate(
        val scope: CoroutineScope,
        val device: DeviceInfo,
        val logs: Logs,
        val health: HealthRepository,
        val scheduler: BackgroundScheduler?,
        val crashLines: CrashLineSource?,
        val persistence: StoragePersistence? = null,
    )

    class Capabilities(
        private val device: DeviceInfo,
        private val ble: BleCentral?,
        private val location: LocationSource?,
        private val health: HealthProvider?,
        private val tabLock: TabLock?,
        private val watch: WatchLink?,
        private val diagnosticLog: Boolean,
        private val developerTools: Boolean,
        private val gate: LaunchGate,
        clock: app.powerlog.application.port.Clock,
        private val persistence: StorageReads?,
    ) {
        var archiveOpened = false
        private var current = derive()
        private var storage: PersistenceState? = null
        val info: Published<DeviceInfoView> = Published(view(), 0, clock)

        fun current(): DeviceCapabilities = current

        fun refresh() {
            current = derive()
            info.publish(view(), urgent = true)
            persistence?.read(::storageChanged)
        }

        fun storageChanged(state: PersistenceState) {
            if (state == storage) return
            storage = state
            info.publish(view(), urgent = true)
        }

        private fun derive(): DeviceCapabilities {
            val web = device.snapshot().platform == Platform.web
            return DeviceCapabilities(
                recording = archiveOpened && gate.refused == null && ble != null && (!web || tabLock != null),
                bluetooth = ble != null,
                gps = location != null,
                health = health?.takeIf { it.access.availability() == HealthAvailability.available }?.kind,
                watch = watch != null,
                diagnosticLog = diagnosticLog,
            )
        }

        private fun view(): DeviceInfoView {
            val snapshot = device.snapshot()
            return DeviceInfoView(
                platform = snapshot.platform,
                appVersion = snapshot.appVersion,
                sdkLevel = snapshot.sdkLevel,
                uses24h = snapshot.uses24h,
                locale = snapshot.locale,
                capabilities = current,
                storage = storage,
                developerTools = developerTools,
            )
        }
    }

    class StorageReads(
        private val persistence: StoragePersistence,
        private val scope: CoroutineScope,
        private val ride: CoroutineDispatcher,
    ) {
        fun read(answer: (PersistenceState) -> Unit) {
            persistence.state(
                Reply(
                    onOk = { state -> scope.launch(ride) { answer(state) } },
                    onFailed = { scope.launch(ride) { answer(PersistenceState.unsupported) } },
                )
            )
        }
    }

    override suspend fun launch(context: LaunchContext): LaunchResult =
        withContext(executors.ride) {
            val startedUs = clock.monoUs()
            var pragmas = emptyList<FilePragmas>()
            val result =
                try {
                    start(context) { pragmas = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    after.logs.unexpected("Launch", error)
                    refused(RefusalDetail.unavailable)
                }
            logLaunch(context, result, pragmas, startedUs)
            if (result == LaunchResult.Ready) afterGate(context.reason == LaunchReason.userInterface)
            result
        }

    private suspend fun start(context: LaunchContext, opened: (List<FilePragmas>) -> Unit): LaunchResult {
        when (val open = host.open()) {
            is StoreResult.Ok ->
                when (val result = open.value) {
                    is StoreOpen.Opened -> opened(result.pragmas)
                    is StoreOpen.Refused -> return refused(result.detail)
                }
            is StoreResult.Failed -> {
                after.logs.storageFailed(open.error, "launch")
                return refused(RefusalDetail.unavailable)
            }
        }
        capabilities.archiveOpened = true
        capabilities.refresh()
        if (settings.load() == Settings.Load.Refused) return refused(RefusalDetail.unreadable)
        restore()?.let {
            return refused(it)
        }
        connection.start(background = context.reason != LaunchReason.userInterface)
        history.watch()
        leases?.watch()
        gate.open()
        watchOpened()
        settings.refresh()
        permissions.reread()
        live.rideChanged()
        return LaunchResult.Ready
    }

    private fun logLaunch(context: LaunchContext, result: LaunchResult, pragmas: List<FilePragmas>, startedUs: Long) {
        val snapshot = after.device.snapshot()
        val refusal = (result as? LaunchResult.Refused)?.detail
        val level = if (refusal != null || !Logs.healthy(pragmas)) LogLevel.error else LogLevel.info
        val fields =
            listOfNotNull(
                LogField("reason", LogValue.Text(context.reason.name)),
                LogField("outcome", LogValue.Text(if (refusal == null) "ready" else "refused")),
                refusal?.let { LogField("refusal", LogValue.Text(it.name)) },
                LogField("launchMs", LogValue.Integer((clock.monoUs() - startedUs) / 1_000)),
                LogField("appVersion", LogValue.Text(snapshot.appVersion)),
                LogField("appBuild", LogValue.Text(snapshot.appBuild)),
                Logs.pragmas(pragmas).takeIf { pragmas.isNotEmpty() }?.let { LogField("pragmas", it) },
            )
        after.logs.append(after.logs.line(level, "launch", fields))
    }

    private fun afterGate(launchedWithUi: Boolean) {
        after.persistence?.state(
            Reply(
                onOk = { state -> after.scope.launch(executors.ride) { capabilities.storageChanged(state) } },
                onFailed = {},
            )
        )
        health.launched(launchedWithUi)
        val android = after.device.snapshot().platform == Platform.android
        after.scope.launch(executors.maintenance) {
            if (android)
                after.device.previousExit()?.let {
                    after.logs.append(after.logs.line(LogLevel.warning, "exit", Logs.exit(it)))
                }
            after.crashLines?.take()?.forEach(after.logs::append)
        }
        val scheduler = after.scheduler
        if (android && scheduler != null)
            after.scope.launch(executors.health) {
                when (val due = after.health.dueRides(clock.utcUs())) {
                    is StoreResult.Ok -> due.value.forEach { scheduler.schedule(it.value, null, ScheduleKind.keep) }
                    is StoreResult.Failed -> after.logs.storageFailed(due.error, "read")
                }
            }
    }

    override suspend fun foregroundChanged(foreground: Boolean) {
        if (gate.failure() != null) return
        withContext(executors.ride) {
            deadlines.poke()
            after.logs.event(LogLevel.info, "foreground", "foreground" to foreground)
            connection.foreground(foreground)
            recorder.visibilityChanged(foreground)
            health.foregroundChanged(foreground)
            if (foreground) {
                capabilities.refresh()
                settings.refresh()
                permissions.reread()
                leases?.check()
            }
        }
    }

    override suspend fun didEnterBackground() {
        if (gate.failure() != null) return
        withContext(executors.ride) {
            capture.flush()
            maintenance.checkpoint(truncate = false)
        }
    }

    override suspend fun willTerminate() {
        if (gate.failure() != null) return
        withContext(executors.ride) { capture.flush() }
    }

    override suspend fun rideCardIntent(intent: RideCardIntent): Outcome<Unit> {
        gate.failure()?.let {
            return Outcome.Failed(it)
        }
        return withContext(executors.ride) { recorder.rideCardIntent(intent) }
    }

    override suspend fun runHealthWork(rideId: String, attempt: Int): HealthWorkResult {
        gate.failure()?.let {
            return if (it.code == FailureCode.storageBusy) HealthWorkResult.retry else HealthWorkResult.failure
        }
        deadlines.poke()
        return health.runHealthWork(rideId, attempt)
    }

    private fun refused(detail: RefusalDetail): LaunchResult {
        gate.refuse(detail)
        capabilities.refresh()
        return LaunchResult.Refused(detail)
    }

    private suspend fun restore(): RefusalDetail? {
        leases?.let {
            return it.launch()?.let(::refusal)
        }
        val stored =
            when (val read = open.read("launch")) {
                is OpenRides.Read.Failed -> return refusal(read.error)
                is OpenRides.Read.Found -> read.ride
            }
        if (stored == null || stored.phase !in RecordingState.recordingPhases) {
            open.restore(stored)
            return null
        }
        return open.interrupt(stored, atLaunch = true)?.let(::refusal)
    }

    private fun refusal(error: StorageError): RefusalDetail =
        when (error.kind) {
            StorageError.Kind.corrupt,
            StorageError.Kind.constraint -> RefusalDetail.unreadable
            else -> RefusalDetail.unavailable
        }
}
