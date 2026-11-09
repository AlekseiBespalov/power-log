package app.powerlog.application.history

import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.ids.RideId

class FakeExampleRideSource(private val rides: Map<RideId, List<WriteBatch>>) : ExampleRideSource {
    private val asked = mutableListOf<Pair<RideId, Int>>()

    val requests: List<Pair<RideId, Int>>
        get() = asked.toList()

    override val rideIds: List<RideId> = rides.keys.toList()

    override fun batch(rideId: RideId, index: Int): WriteBatch? {
        asked += rideId to index
        return rides[rideId]?.getOrNull(index)
    }
}
