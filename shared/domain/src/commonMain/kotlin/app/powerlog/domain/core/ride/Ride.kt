package app.powerlog.domain.core.ride

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.Availability
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthWriter
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.time.RideTiming
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class RidePhase {
    preparing,
    running,
    paused,
    interrupted,
    completed,
    failed,
}

@Serializable
public enum class RideAction {
    pause,
    resume,
    lap,
    finish,
    discard,
}

@Serializable
public enum class RideEventKind {
    start,
    pause,
    resume,
    lap,
    stop,
}

@Serializable
public enum class Device {
    iphone,
    androidPhone,
    browser,
    appleWatch,
}

@Serializable
public enum class SampleRate(public val hz: Int) {
    @SerialName("2") hz2(2),
    @SerialName("4") hz4(4),
    @SerialName("8") hz8(8),
}

@Serializable
public enum class FitPowerSource {
    rider,
    battery,
}

@Serializable
public enum class TimerState {
    running,
    frozen,
    unavailable,
}

@Serializable
public data class RideTimer(public val state: TimerState, public val baseUs: Long, public val originUtcUs: Long?)

@Serializable
public data class RideOptions(
    public val sampleRate: SampleRate,
    public val indoor: Boolean,
    public val useWatch: Boolean,
    public val saveToHealth: Boolean,
    public val recordGps: Boolean,
    public val distanceSource: DistanceSource,
    public val batteryMethod: BatteryMethod,
) {
    public val healthWriter: HealthWriter?
        get() = if (!saveToHealth) null else if (useWatch) HealthWriter.watch else HealthWriter.phone
}

public data class Ride(
    public val id: RideId,
    public val recordedBy: Device,
    public val example: Boolean,
    public val startedUtcUs: Long,
    public val options: RideOptions,
    public val phase: RidePhase,
    public val interrupted: Boolean,
    public val checkpoint: RideTiming,
    public val end: RideTiming?,
)

@Serializable
public enum class Platform {
    ios,
    android,
    web,
}

@Serializable
public data class DeviceCapabilities(
    public val recording: Boolean,
    public val bluetooth: Boolean,
    public val gps: Boolean,
    public val health: HealthProviderKind?,
    public val watch: Boolean,
    public val diagnosticLog: Boolean,
)

public object RideRules {
    public fun exportReady(ride: Ride): Boolean = ride.phase == RidePhase.completed

    public fun deletable(ride: Ride, exporting: Set<RideId>): Availability =
        when {
            ride.phase in open -> Availability(false, FailureCode.rideInProgress)
            ride.id in exporting -> Availability(false, FailureCode.exportInProgress)
            else -> Availability(true, null)
        }

    private val open = setOf(RidePhase.preparing, RidePhase.running, RidePhase.paused, RidePhase.interrupted)
}

public data class StartRequest(
    public val indoor: Boolean,
    public val useWatch: Boolean,
    public val saveToHealth: Boolean,
    public val recordGps: Boolean?,
)

@Serializable
public data class RideSettings(
    public val sampleRate: SampleRate,
    public val distance: DistanceSelection,
    public val battery: BatteryMethod,
    public val indoor: Boolean,
    public val useWatch: Boolean,
    public val saveToHealth: Boolean,
    public val recordGps: Boolean?,
)

public object RideOptionsRules {
    public fun resolve(settings: RideSettings, request: StartRequest, capabilities: DeviceCapabilities): RideOptions? {
        val useWatch = capabilities.watch && request.useWatch
        val recordGps = capabilities.gps && (request.recordGps ?: !request.indoor)
        val distanceSource =
            when (settings.distance) {
                DistanceSelection.auto ->
                    if (recordGps && !request.indoor) DistanceSource.phoneGps else DistanceSource.controller
                DistanceSelection.controller -> DistanceSource.controller
                DistanceSelection.phoneGps -> if (recordGps) DistanceSource.phoneGps else return null
            }
        return RideOptions(
            sampleRate = settings.sampleRate,
            indoor = request.indoor,
            useWatch = useWatch,
            saveToHealth = request.saveToHealth && (useWatch || capabilities.health != null),
            recordGps = recordGps,
            distanceSource = distanceSource,
            batteryMethod = settings.battery,
        )
    }
}
