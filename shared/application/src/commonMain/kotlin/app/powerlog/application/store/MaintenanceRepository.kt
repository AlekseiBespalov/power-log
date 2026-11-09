package app.powerlog.application.store

import app.powerlog.domain.core.ids.RideId

public enum class Step {
    more,
    done,
}

public interface MaintenanceRepository {
    // deleted rides whose rows remain
    public suspend fun pendingDeletions(): StoreResult<List<RideId>>

    public suspend fun cleanupStep(rideId: RideId, maxRows: Int): StoreResult<Step>

    public suspend fun reclaimStep(): StoreResult<Step>

    public suspend fun checkpoint(truncate: Boolean): StoreResult<Unit>
}
