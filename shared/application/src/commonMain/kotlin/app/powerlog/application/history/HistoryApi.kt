package app.powerlog.application.history

import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.HealthView
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.summary.LatLon
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import kotlinx.serialization.Serializable

public interface HistoryApi {
    public val list: ApiStream<Empty, HistoryListView>
    public val ride: ApiStream<RideInput, RideDetailView>

    public suspend fun page(input: HistoryPageInput): Outcome<HistoryPage>

    public suspend fun delete(input: RideInput): Outcome<Unit>

    public suspend fun addExampleRides(input: Empty): Outcome<AddedRidesView>
}

@Serializable public data class HistoryListView(public val revision: Long)

@Serializable public data class HistoryPageInput(public val after: String?, public val key: String)

@Serializable public data class HistoryPage(public val rows: List<HistoryRow>, public val next: String?)

@Serializable
public data class HistoryRow(
    public val rideId: RideId,
    public val startedUtcUs: Long,
    public val elapsedUs: Long,
    public val phase: RidePhase,
    public val interrupted: Boolean,
    public val indoor: Boolean,
    public val example: Boolean,
    public val health: HealthView?,
    public val open: Availability,
    public val delete: Availability,
)

@Serializable
public data class RideDetailView(
    public val ride: RideView,
    public val laps: Int,
    public val summary: SummaryView?,
    public val summaryState: SummaryState,
    public val route: List<List<LatLon>>?,
    public val health: HealthView?,
    public val allowed: RideDetailAllowed,
)

@Serializable
public enum class SummaryState {
    pending,
    ready,
    unavailable,
}

@Serializable
public data class RideView(
    public val rideId: RideId,
    public val startedUtcUs: Long,
    public val timerUs: Long,
    public val phase: RidePhase,
    public val interrupted: Boolean,
    public val example: Boolean,
    public val recordedBy: Device,
    public val options: RideOptions,
)

@Serializable
public data class SummaryView(
    public val timerUs: Long,
    public val distanceM: Double?,
    public val averageSpeedMps: Double?,
    public val batteryMethod: BatteryMethod,
    public val energyWh: Double?,
    public val chargeAh: Double?,
    public val averagePowerW: Double?,
    public val averageCadenceRpm: Double?,
    public val averageHeartRateBpm: Double?,
    public val ascentM: Double?,
    public val activeEnergyKcal: Double?,
    public val physiologyBackfilled: Boolean,
)

@Serializable
public data class RideDetailAllowed(
    public val finish: Availability,
    public val discard: Availability,
    public val delete: Availability,
    public val export: Availability,
    public val healthRefresh: Availability,
    public val healthRetry: Availability,
)

@Serializable public data class AddedRidesView(public val added: Int)

public object HistoryPolicy {
    public const val pageRows: Int = 50
}
