package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading

internal const val ARCHIVE_FILE: String = "$ARCHIVE_NAME.sqlite3"

internal const val CACHE_FILE: String = "power-log-cache.sqlite3"

internal const val ARCHIVE_READERS: Int = 4

internal const val CACHE_READERS: Int = 2

internal class SqlStores(
    archiveFile: SqlFile,
    cacheFile: SqlFile,
    executors: StoreExecutors,
    log: DiagnosticLogRepository,
    clock: () -> ClockReading,
    rulesVersion: Int = RULES_VERSION,
    layout: Int = CACHE_LAYOUT,
) {
    val cache: SqlCache = SqlCache(cacheFile, executors, log, clock, rulesVersion, layout)
    val archive: SqlArchive = SqlArchive(archiveFile, cache, executors)
    val stores: Stores =
        Stores(
            host = archive,
            writer = SqlArchiveWriter(archive),
            rides = SqlRideRepository(archive),
            measurements = SqlMeasurementRepository(archive),
            connection = SqlConnectionRepository(archive),
            maintenance = SqlMaintenanceRepository(archive, cache),
            health = SqlHealthRepository(archive),
            settings = SqlSettingsRepository(archive),
            cache = cache,
        )
}
