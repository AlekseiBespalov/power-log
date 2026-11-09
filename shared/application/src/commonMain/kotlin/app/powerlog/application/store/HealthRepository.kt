package app.powerlog.application.store

import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric

public enum class HealthTask {
    publish,
    readback,
}

public sealed interface HealthWorkScope {
    public data class Publish(public val component: HealthComponent) : HealthWorkScope

    public data class Readback(public val metric: HealthMetric) : HealthWorkScope
}

public data class HealthWork(
    public val rideId: RideId,
    public val scope: HealthWorkScope,
    public val state: ComponentState,
    public val attempts: Int,
    public val cursor: String?,
    public val dueUtcUs: Long?,
    public val reason: HealthReason?,
)

public interface HealthRepository {
    // null: absent or deleted
    public suspend fun outcome(rideId: RideId): StoreResult<RideHealth?>

    // key order: task, scope
    public suspend fun work(rideId: RideId): StoreResult<List<HealthWork>>

    // rides with a row due at or before now, earliest first; then completed Android rides whose Health outcome is
    // pending
    public suspend fun dueRides(nowUtcUs: Long): StoreResult<List<RideId>>

    // rides holding a pending readback row with no due instant: a read deferred until the app is in front
    public suspend fun deferredRides(): StoreResult<List<RideId>>
}
