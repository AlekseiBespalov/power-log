package app.powerlog.data

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.StoreHost

public class Stores
internal constructor(
    public val host: StoreHost,
    public val writer: ArchiveWriter,
    public val rides: RideRepository,
    public val measurements: MeasurementRepository,
    public val connection: ConnectionRepository,
    public val maintenance: MaintenanceRepository,
    public val health: HealthRepository,
    public val settings: SettingsRepository,
    public val cache: CacheRepository,
)
