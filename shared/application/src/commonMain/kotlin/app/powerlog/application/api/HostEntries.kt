package app.powerlog.application.api

import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail

public interface HostEntries {
    public suspend fun launch(context: LaunchContext): LaunchResult

    public suspend fun foregroundChanged(foreground: Boolean)

    public suspend fun didEnterBackground()

    public suspend fun willTerminate()

    public suspend fun rideCardIntent(intent: RideCardIntent): Outcome<Unit>

    public suspend fun runHealthWork(rideId: String, attempt: Int): HealthWorkResult
}

public data class LaunchContext(public val reason: LaunchReason)

public enum class LaunchReason {
    userInterface,
    background,
    bluetoothRelaunch,
}

public sealed interface LaunchResult {
    public data object Ready : LaunchResult

    public data class Refused(public val detail: RefusalDetail) : LaunchResult
}
