package app.powerlog.application.history

import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.ids.RideId

public interface ExampleRideSource {
    public val rideIds: List<RideId>

    public fun batch(rideId: RideId, index: Int): WriteBatch?
}
