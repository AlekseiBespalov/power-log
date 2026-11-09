package app.powerlog.runtime.compose

import app.powerlog.application.Executors
import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.launch.ApplicationStores
import app.powerlog.application.launch.PowerLogApplication
import app.powerlog.application.port.Clock
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.data.MemoryMeasurements
import app.powerlog.data.Stores
import app.powerlog.runtime.diagnostics.Unexpected
import app.powerlog.runtime.host.RuntimeHost
import app.powerlog.runtime.watch.JsonWatchCodec

internal object Composition {
    fun create(
        executors: Executors,
        clock: Clock,
        ports: Ports,
        stores: ApplicationStores,
        devTools: DevTools?,
        crashLines: CrashLineSource? = null,
    ): PowerLogRuntime {
        (devTools as? RideBound)?.bind(executors.ride)
        val composed = devTools?.ports(ports) ?: ports
        val application =
            PowerLogApplication(
                executors = executors,
                clock = clock,
                stores = stores,
                device = composed.device,
                permissions = composed.permissions,
                picker = composed.picker,
                ble = composed.ble,
                location = composed.location,
                health = composed.health,
                keepAlive = composed.keepAlive,
                tabLock = composed.tabLock,
                persistence = composed.persistence,
                rideCard = composed.rideCard,
                watch = composed.watch,
                watchCodec = composed.watch?.let { JsonWatchCodec() },
                scheduler = composed.scheduler,
                exampleRides = devTools?.exampleRides,
                developerTools = devTools != null,
                crashLines = crashLines,
                unexpected = Unexpected::line,
            )
        application.attach()
        val log = stores.log ?: Unlogged
        return PowerLogRuntime(
            RuntimeHost(executors, application.host, clock, log),
            application.api,
            clock,
            log,
            composed.ble,
        )
    }

    fun stores(stores: Stores, log: DiagnosticLogRepository?): ApplicationStores =
        ApplicationStores(
            host = stores.host,
            writer = stores.writer,
            rides = stores.rides,
            measurements = stores.measurements,
            connection = stores.connection,
            maintenance = stores.maintenance,
            health = stores.health,
            settings = stores.settings,
            cache = stores.cache,
            memory = MemoryMeasurements(),
            log = log,
        )

    private object Unlogged : DiagnosticLogRepository {
        override suspend fun append(line: LogLine) = Unit

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
            StoreResult.Ok(LogChunk(ByteArray(0), null))
    }
}
