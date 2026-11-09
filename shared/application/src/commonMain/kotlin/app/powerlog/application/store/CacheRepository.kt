package app.powerlog.application.store

import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId

public interface CacheRepository {
    public suspend fun summary(rideId: RideId, inputRevision: Long): RideSummary?

    public suspend fun putSummary(rideId: RideId, inputRevision: Long, summary: RideSummary): Unit

    public suspend fun tiles(
        rideId: RideId,
        metric: MetricId,
        level: Int,
        slots: LongRange,
        inputRevision: Long,
    ): Map<Long, PlotAggregate>

    public suspend fun putTiles(
        rideId: RideId,
        metric: MetricId,
        level: Int,
        inputRevision: Long,
        tiles: Map<Long, PlotAggregate>,
    ): Unit

    public suspend fun dropRide(rideId: RideId): Unit
}
