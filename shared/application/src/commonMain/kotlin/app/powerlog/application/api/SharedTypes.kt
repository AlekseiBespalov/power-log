package app.powerlog.application.api

import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.HealthWriter
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.DeviceCapabilities
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.PersistenceState
import kotlinx.serialization.Serializable

@Serializable
public data class ApiFailure(
    public val code: FailureCode,
    public val retryable: Boolean,
    public val occurrence: String?,
    public val detail: RefusalDetail?,
)

@Serializable
public data class HealthView(
    public val provider: HealthProviderKind?,
    public val writer: HealthWriter?,
    public val outcome: HealthOutcome,
    public val reason: HealthReason?,
    public val components: List<ComponentResult>,
)

public interface DeviceApi {
    public val info: ApiStream<Empty, DeviceInfoView>
}

@Serializable
public data class DeviceInfoView(
    public val platform: Platform,
    public val appVersion: String,
    public val sdkLevel: Int?,
    public val uses24h: Boolean,
    public val locale: String,
    public val capabilities: DeviceCapabilities,
    public val storage: PersistenceState?,
    public val developerTools: Boolean,
)

@Serializable public data class RideInput(public val rideId: RideId)
