package app.powerlog.application.health

import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.health.HealthPlan
import app.powerlog.domain.health.IndexedRoutePoint

internal interface WatchHealthItems {
    fun planned(
        rideId: RideId,
        quantities: List<HealthPlan.Quantity>,
        points: List<IndexedRoutePoint>,
        stride: Int,
        final: Boolean,
    )
}
