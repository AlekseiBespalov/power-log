package app.powerlog.application.exports

import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public interface ExportsApi {
    public suspend fun open(input: ExportOpenInput): Outcome<ExportSessionView>

    public suspend fun close(input: ExportCloseInput): Outcome<Unit>

    public suspend fun page(input: ExportPageInput): Outcome<ExportPage>
}

@Serializable
public sealed interface ExportSource {
    @Serializable @SerialName("ride") public data class Ride(public val rideId: RideId) : ExportSource

    @Serializable @SerialName("imported") public data class Imported(public val importId: RideId) : ExportSource
}

@Serializable
public enum class ExportKind {
    fit,
    csv,
    zip,
}

@Serializable
public data class ExportOpenInput(
    public val source: ExportSource,
    public val kind: ExportKind,
    public val fitPower: FitPowerSource,
)

@Serializable
public data class ExportSessionView(
    public val sessionId: String,
    public val platform: Platform,
    public val metadata: ExportRideMetadata?,
    public val elapsedEnd: Double,
    public val timerSeconds: Double,
    public val producers: ExportProducers,
    public val consumption: ConsumptionAvailability,
)

@Serializable
public sealed interface ConsumptionAvailability {
    @Serializable @SerialName("available") public data object Available : ConsumptionAvailability

    @Serializable
    @SerialName("unavailable")
    public data class Unavailable(public val reason: String) : ConsumptionAvailability
}

@Serializable
public data class ExportRideMetadata(
    public val batteryConsumptionSource: BatteryMethod,
    public val startedAt: String,
    public val endedAt: String,
    public val ownerTiming: ExportOwnerTiming,
    public val indoor: Boolean,
    public val interrupted: Boolean,
    public val watchEnabled: Boolean,
    public val saveToHealth: Boolean,
    public val recordGPS: Boolean,
    public val health: ExportHealth,
    public val example: Boolean,
    public val sampleHz: Int,
)

@Serializable
public data class ExportOwnerTiming(
    public val timestamp: String,
    public val elapsedSeconds: Double,
    public val timerSeconds: Double,
)

@Serializable
public data class ExportHealth(
    public val provider: HealthProviderKind?,
    public val state: HealthOutcome,
    public val workoutUUID: String?,
)

@Serializable public data class ExportProducers(public val gps: List<Producer>, public val health: List<Producer>)

@Serializable
public data class ExportConnection(
    public val token: String,
    public val vendor: String,
    public val model: String?,
    public val firmware: String?,
    public val protocol: String?,
)

@Serializable
public data class ExportPageInput(
    public val sessionId: String,
    public val projection: String,
    public val after: List<Double>?,
)

@Serializable public data class ExportCloseInput(public val sessionId: String, public val failure: FailureCode?)

public object ExportsPolicy {
    public const val pageMaxBytes: Int = 4_194_304
}
