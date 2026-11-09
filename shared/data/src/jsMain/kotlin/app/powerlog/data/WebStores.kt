package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading

public object WebStores {
    public fun create(archiveName: String, log: DiagnosticLogRepository, clock: () -> ClockReading): Stores =
        IdbStores(archiveName, log, clock).stores

    public val diagnosticLog: DiagnosticLogRepository = WebDiagnosticLog
}

internal class IdbStores(
    name: String,
    log: DiagnosticLogRepository,
    clock: () -> ClockReading,
    rulesVersion: Int = RULES_VERSION,
    layout: Int = CACHE_LAYOUT,
) {
    val cache: IdbCache = IdbCache(log, clock, rulesVersion, layout)
    val archive: IdbArchive = IdbArchive(name, cache)
    val settings: IdbSettingsRepository = IdbSettingsRepository(archive)
    val stores: Stores =
        Stores(
            host = archive,
            writer = IdbArchiveWriter(archive),
            rides = IdbRideRepository(archive),
            measurements = IdbMeasurementRepository(archive),
            connection = IdbConnectionRepository(archive),
            maintenance = IdbMaintenanceRepository(archive),
            health = IdbHealthRepository(archive),
            settings = settings,
            cache = cache,
        )
}

// The web keeps no diagnostic log: lines reach only the console mirror the composition wraps around it.
internal object WebDiagnosticLog : DiagnosticLogRepository {
    override suspend fun append(line: LogLine) {}

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
        StoreResult.Ok(LogChunk(ByteArray(0), null))
}
