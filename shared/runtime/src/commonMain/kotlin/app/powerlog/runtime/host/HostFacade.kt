package app.powerlog.runtime.host

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail

public interface HostFacade {
    public fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit)

    public fun foregroundChanged(foreground: Boolean)

    public fun didEnterBackground(done: () -> Unit)

    public fun willTerminate(timeoutUs: Long): Boolean

    public fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit)

    public fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit)
}
