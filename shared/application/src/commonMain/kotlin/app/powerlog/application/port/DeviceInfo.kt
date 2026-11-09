package app.powerlog.application.port

import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.LocaleFacts
import kotlinx.serialization.Serializable

public interface DeviceInfo {
    public fun snapshot(): DeviceSnapshot

    public fun conditions(): DeviceConditions

    public fun previousExit(): ExitReport?
}

@Serializable
public data class DeviceSnapshot(
    public val platform: Platform,
    public val manufacturer: String,
    public val model: String,
    public val osVersion: String,
    public val sdkLevel: Int?,
    public val appVersion: String,
    public val appBuild: String,
    public val uses24h: Boolean,
    public val locale: String,
    public val localeFacts: LocaleFacts,
)

@Serializable
public data class DeviceConditions(
    public val powerSave: Boolean?,
    public val thermal: ThermalLevel?,
    public val processCpuUs: Long?,
    public val footprintBytes: Long?,
    public val ignoringBatteryOptimizations: Boolean?,
    public val backgroundRestricted: Boolean?,
    public val standbyBucket: Int?,
)

@Serializable
public enum class ThermalLevel {
    nominal,
    fair,
    serious,
    critical,
}

public data class ExitReport(
    public val atUtcUs: Long,
    public val reason: String,
    public val status: Int,
    public val importance: Int,
)
