package app.powerlog.application.launch

import app.powerlog.application.Deadlines
import app.powerlog.application.Executors
import app.powerlog.application.Logs
import app.powerlog.application.RideSummaries
import app.powerlog.application.api.ApiFacade
import app.powerlog.application.api.HostEntries
import app.powerlog.application.api.Keys
import app.powerlog.application.api.LaunchGate
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.api.RefusingWriter
import app.powerlog.application.charts.ChartTiles
import app.powerlog.application.charts.Charts
import app.powerlog.application.charts.DerivedTiles
import app.powerlog.application.connection.ControllerConnection
import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.diagnostics.DiagnosticLog
import app.powerlog.application.diagnostics.Diagnostics
import app.powerlog.application.exports.Exports
import app.powerlog.application.health.Health
import app.powerlog.application.health.HealthConnectCalls
import app.powerlog.application.history.ExampleRideSource
import app.powerlog.application.history.ExampleRides
import app.powerlog.application.history.History
import app.powerlog.application.history.Maintenance
import app.powerlog.application.imports.Imports
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.KeepAliveEvents
import app.powerlog.application.port.LocationSource
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.StoragePersistence
import app.powerlog.application.port.TabLock
import app.powerlog.application.port.TonePlayer
import app.powerlog.application.port.WatchLink
import app.powerlog.application.recording.Capture
import app.powerlog.application.recording.LivePublisher
import app.powerlog.application.recording.OpenRides
import app.powerlog.application.recording.Permissions
import app.powerlog.application.recording.RecordingState
import app.powerlog.application.recording.RideCardPublisher
import app.powerlog.application.recording.RideEventsSubscribers
import app.powerlog.application.recording.RideRecorder
import app.powerlog.application.recording.TabLeases
import app.powerlog.application.settings.Settings
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StoreHost
import app.powerlog.application.watch.WatchCodec
import app.powerlog.application.watch.WatchRides
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.time.ClockReading
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

public class ApplicationStores(
    public val host: StoreHost,
    public val writer: ArchiveWriter,
    public val rides: RideRepository,
    public val measurements: MeasurementRepository,
    public val connection: ConnectionRepository,
    public val maintenance: MaintenanceRepository,
    public val health: HealthRepository,
    public val settings: SettingsRepository,
    public val cache: CacheRepository,
    public val memory: MemoryMeasurementRepository,
    public val log: DiagnosticLogRepository?,
)

public class PowerLogApplication(
    executors: Executors,
    clock: Clock,
    stores: ApplicationStores,
    device: DeviceInfo,
    permissions: PermissionGate,
    picker: FilePicker,
    ble: BleCentral?,
    location: LocationSource?,
    health: HealthProvider?,
    keepAlive: RecordingKeepAlive?,
    tabLock: TabLock?,
    persistence: StoragePersistence?,
    rideCard: RideCard?,
    tones: TonePlayer?,
    watch: WatchLink?,
    watchCodec: WatchCodec?,
    scheduler: BackgroundScheduler?,
    exampleRides: ExampleRideSource?,
    developerTools: Boolean,
    crashLines: CrashLineSource?,
    unexpected: (String, Throwable, ClockReading) -> LogLine,
) {
    private val scope =
        CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, error -> logs.unexpected("application", error) }
        )
    private val log: DiagnosticLogRepository? = stores.log?.let { DiagnosticLog(it, executors.maintenance) }
    private val logs: Logs = Logs(scope, executors.maintenance, log, clock, unexpected)
    private val deadlines = Deadlines(clock)
    private val gate = LaunchGate()
    private val writer = RefusingWriter(stores.writer, gate)
    private val providers = health?.let { HealthConnectCalls(it, scope, executors.health, clock) }
    private val ports = Ports(ble, location, keepAlive, rideCard, watch, providers)
    private val capabilities =
        Launch.Capabilities(
            device = device,
            ble = ble,
            location = location,
            health = providers,
            tabLock = tabLock,
            watch = watch,
            diagnosticLog = stores.log != null,
            developerTools = developerTools,
            gate = gate,
            clock = clock,
            persistence = persistence?.let { Launch.StorageReads(it, scope, executors.ride) },
        )
    private val state = RecordingState()
    private val live = LivePublisher(scope, executors.ride, clock, state, deadlines)
    private val card = rideCard?.let { RideCardPublisher(clock, it, state, live, logs) }
    private val settings = Settings(stores.settings, { device.snapshot().localeFacts }, capabilities::current, clock)
    private val watchRides =
        if (watch == null || watchCodec == null) null
        else
            WatchRides(
                scope,
                executors.ride,
                clock,
                watch,
                watchCodec,
                state,
                live,
                { settings.stream.value.app.units },
                writer,
                stores.rides,
                stores.measurements,
                logs,
                deadlines,
                { ride, originals -> healthWork.samples(ride, originals) },
            )
    private val healthWork =
        Health(
            scope,
            executors,
            clock,
            providers,
            stores.rides,
            stores.health,
            stores.measurements,
            writer,
            scheduler,
            device,
        )
    private val events = RideEventsSubscribers(listOfNotNull(card, healthWork.publisher, watchRides))
    private val exports =
        Exports(executors, stores.rides, stores.measurements, stores.memory, device, logs) {
            gate.refuse(RefusalDetail.unreadable)
        }
    private val access =
        Permissions(
            scope,
            executors.ride,
            permissions,
            providers,
            { capabilities.current().gps },
            device,
            { settings.ride },
            clock,
            logs,
        )
    private val capture =
        Capture(scope, executors.ride, clock, writer, stores.memory, state, live, events, logs, device, deadlines)
    private val rides = OpenRides(clock, stores.rides, writer, state, live, events, logs)
    private val leases = tabLock?.let { TabLeases(scope, executors.ride, it, writer, rides, state, live) }
    private val diagnostics = Diagnostics(scope, executors, clock, device, log, deadlines)
    private val connection =
        ControllerConnection(
            scope,
            executors.ride,
            clock,
            ble,
            permissions,
            device,
            stores.connection,
            writer,
            capture,
            diagnostics,
            logs,
            keepAlive,
            deadlines,
        )
    private val recorder =
        RideRecorder(
            scope = scope,
            ride = executors.ride,
            clock = clock,
            writer = writer,
            measurements = stores.measurements,
            state = state,
            capture = capture,
            live = live,
            connection = connection,
            settings = settings,
            events = events,
            device = device,
            permissions = permissions,
            access = access,
            ports = RideRecorder.RecordingPorts(keepAlive, location, rideCard, providers, watch, persistence, tones),
            capabilities = capabilities::current,
            logs = logs,
            rides = rides,
            leases = leases,
            phoneSession = true,
            deadlines = deadlines,
        )
    private val summaries = RideSummaries(scope, executors, stores.measurements, stores.cache)
    private val history =
        History(
            scope = scope,
            executors = executors,
            clock = clock,
            rides = stores.rides,
            measurements = stores.measurements,
            healthWork = stores.health,
            writer = writer,
            exports = exports,
            health = providers,
            summaries = summaries,
            maintenance = Maintenance(scope, executors, stores.maintenance, stores.cache, writer),
            forget = healthWork::deleted,
        ) {
            gate.refuse(RefusalDetail.unreadable)
        }
    private val charts =
        Charts(
            executors,
            clock,
            stores.rides,
            stores.measurements,
            stores.memory,
            writer.commits,
            live,
            summaries,
            ChartTiles(stores.cache, executors.scans),
            DerivedTiles(stores.cache),
        ) {
            gate.refuse(RefusalDetail.unreadable)
        }

    private val imports = Imports(executors, picker, stores.memory, charts::closed)
    private val examples =
        ExampleRides(executors, exampleRides, stores.rides, writer) { gate.refuse(RefusalDetail.unreadable) }

    private val launch =
        Launch(
            executors = executors,
            clock = clock,
            gate = gate,
            host = stores.host,
            maintenance = stores.maintenance,
            settings = settings,
            open = rides,
            leases = leases,
            recorder = recorder,
            capture = capture,
            live = live,
            connection = connection,
            history = history,
            permissions = access,
            capabilities = capabilities,
            after = Launch.AfterGate(scope, device, logs, stores.health, scheduler, crashLines, persistence),
            health = healthWork,
            watchOpened = { watchRides?.opened() },
            deadlines = deadlines,
        )

    init {
        healthWork.publisher?.recorder = recorder
        healthWork.publisher?.watchItems = watchRides
        gate.onRefused = { scope.launch(executors.ride) { capabilities.refresh() } }
        diagnostics.reports = {
            Diagnostics.Reports(
                connection.snapshot(),
                ble?.radio(),
                access.states?.bluetooth,
                connection.traceContract(),
            )
        }
        live.bikeWanted = { connection.stream.value.bike != null }
        live.published = { card?.refresh() }
        recorder.cardToken = { card?.token }
        recorder.endCard = { card?.end() }
        recorder.storageChanged = capabilities::storageChanged
        watchRides?.recorder = recorder
        capture.connection = { if (connection.wanted) connection.summary() else null }
        capture.background = { connection.snapshot().background }
        connection.rideOpen = { state.open != null }
        connection.changed = { live.publish(urgent = true) }
        connection.permissionsAnswered = access::answered
        settings.onRideSettings {
            connection.rateChanged(it.sampleRate)
            access.publish()
        }
    }

    public val api: PowerLogApi =
        ApiFacade(
            executors,
            gate,
            Keys(),
            capabilities.info,
            connection,
            recorder,
            access,
            live,
            history,
            examples,
            charts,
            imports,
            healthWork,
            settings,
            diagnostics,
            logs,
            exports,
        )

    public val host: HostEntries = launch

    public fun attach() {
        ports.ble?.attach(connection.events)
        ports.location?.attach(capture.locationEvents)
        ports.keepAlive?.attach(
            object : KeepAliveEvents {
                override fun expiring(kind: HoldKind) {
                    if (kind == HoldKind.reconnect) connection.holdExpiring()
                }
            }
        )
        card?.let { ports.rideCard?.attach(it.events) }
        watchRides?.let { ports.watch?.attach(it.events) }
    }

    private class Ports(
        val ble: BleCentral?,
        val location: LocationSource?,
        val keepAlive: RecordingKeepAlive?,
        val rideCard: RideCard?,
        val watch: WatchLink?,
        val health: HealthProvider?,
    )

    private companion object {}
}
