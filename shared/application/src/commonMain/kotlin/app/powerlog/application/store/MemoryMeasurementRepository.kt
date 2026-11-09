package app.powerlog.application.store

import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.imports.ImportedRows

public interface MemoryMeasurementRepository : MeasurementRepository {
    // keeps the newest maxReadings readings
    public fun openPreview(rideId: RideId, maxReadings: Int): Unit

    public fun addSession(rideId: RideId, session: ControllerSession): Unit

    public fun appendPreview(rideId: RideId, samples: List<TelemetrySample>): Unit

    public fun openImported(rideId: RideId, maxReadings: Int, maxElapsedUs: Long): Unit

    // one reader page; false: past a bound, nothing of it appended
    public fun appendImported(rideId: RideId, rows: ImportedRows): Boolean

    // the reader's ImportResult.Read.capabilities
    public fun finishImported(rideId: RideId, capabilities: ControllerCapabilities): Unit

    public suspend fun importedRows(rideId: RideId, after: Int?, limit: Int): StoreResult<ImportedPage>

    public fun close(rideId: RideId): Unit
}

public data class ImportedPage(public val rows: ImportedRows, public val next: Int?)
