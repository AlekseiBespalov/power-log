package app.powerlog.application.exports

import app.powerlog.application.Executors
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.launch.ApplicationStores
import app.powerlog.application.launch.PowerLogApplication
import app.powerlog.application.port.Clock
import app.powerlog.application.port.FilePicker
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import app.powerlog.data.JvmStores
import app.powerlog.data.MemoryMeasurements
import app.powerlog.data.StoreExecutors
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.time.ClockReading
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object ExportHarness {
    private val clock =
        object : Clock {
            override fun monoUs(): Long = System.nanoTime() / 1_000

            override fun utcUs(): Long = System.currentTimeMillis() * 1_000

            override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
        }

    private val log =
        object : DiagnosticLogRepository {
            override suspend fun append(line: LogLine) = Unit

            override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
                StoreResult.Ok(LogChunk(ByteArray(0), null))
        }

    suspend fun api(
        platform: Platform,
        picker: ((post: (() -> Unit) -> Unit) -> FilePicker)? = null,
        directory: File? = null,
        load: suspend (ArchiveWriter) -> Unit = {},
    ): PowerLogApi {
        val io = Dispatchers.IO.limitedParallelism(1)
        val executors = StoreExecutors(io, io, io, io, io)
        val stores =
            if (directory == null) JvmStores.inMemory(executors, log, clock::read)
            else JvmStores.create(directory, executors, log, clock::read)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val post: (() -> Unit) -> Unit = { reply -> scope.launch { reply() } }
        val application =
            PowerLogApplication(
                executors =
                    Executors(
                        Dispatchers.Default.limitedParallelism(1),
                        Dispatchers.Default.limitedParallelism(1),
                        Dispatchers.Default.limitedParallelism(1),
                        Dispatchers.Default.limitedParallelism(4),
                    ),
                clock = clock,
                stores =
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
                    ),
                device = FakeDeviceInfo(FakeDeviceInfo.snapshot(platform)),
                permissions = FakePermissionGate(post),
                picker = picker?.invoke(post) ?: FakeFilePicker(post),
                ble = null,
                location = null,
                health = null,
                keepAlive = null,
                tabLock = null,
                persistence = null,
                rideCard = null,
                tones = null,
                watch = null,
                watchCodec = null,
                scheduler = null,
                exampleRides = null,
                developerTools = false,
                crashLines = null,
                unexpected = { component, error, at ->
                    LogLine(
                        LogLevel.error,
                        at.utcUs,
                        at.monoUs,
                        "unexpected",
                        listOf(LogField(component, LogValue.Text("$error"))),
                    )
                },
            )
        application.attach()
        application.host.launch(LaunchContext(LaunchReason.userInterface))
        load(stores.writer)
        return application.api
    }
}
