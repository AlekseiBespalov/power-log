package app.powerlog.application.store

import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Ride

public data class RideRecord(
    public val ride: Ride,
    public val health: RideHealth,
    public val publish: List<HealthWork>,
    public val revision: Long,
)

public data class RideCursor(public val startedUtcUs: Long, public val rideId: RideId)

public data class RideRecordPage(public val rows: List<RideRecord>, public val next: RideCursor?)

public interface RideRepository {
    // null: absent or deleted
    public suspend fun ride(rideId: RideId): StoreResult<RideRecord?>

    // the undeleted ride preparing, running, paused or interrupted
    public suspend fun openRide(): StoreResult<RideRecord?>

    public suspend fun page(after: RideCursor?, limit: Int): StoreResult<RideRecordPage>

    // history_revision
    public suspend fun marker(): StoreResult<Long>
}
