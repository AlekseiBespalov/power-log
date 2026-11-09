package app.powerlog.application.imports

import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.ids.RideId
import kotlinx.serialization.Serializable

public interface ImportsApi {
    public suspend fun open(input: KeyInput): Outcome<ImportedRideView>

    public suspend fun close(input: ImportInput): Outcome<Unit>
}

@Serializable public data class KeyInput(public val key: String)

@Serializable public data class ImportInput(public val importId: RideId)

@Serializable
public data class ImportedRideView(
    public val importId: RideId,
    public val name: String,
    public val summary: ImportedSummaryView,
)

@Serializable
public data class ImportedSummaryView(
    public val samples: Int,
    public val durationUs: Long,
    public val gaps: Int,
    public val clockAdjustments: Int,
    public val averagePowerW: Double?,
    public val peakPowerW: Double?,
    public val averageCadenceRpm: Double?,
    public val riderWorkWh: Double?,
)

public object ImportsPolicy {
    public const val readChunkBytes: Int = 1_048_576
}
