package app.powerlog.application.charts

import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public interface ChartsApi {
    public val head: ApiStream<ChartHeadInput, ChartHeadView>

    public suspend fun plot(input: PlotInput): Outcome<PlotView>

    public suspend fun latest(input: LatestInput): Outcome<LatestView>

    public suspend fun inspect(input: InspectInput): Outcome<InspectView>

    public suspend fun statistics(input: StatisticsInput): Outcome<StatisticsView>
}

@Serializable
public sealed interface ChartSource {
    @Serializable @SerialName("preview") public data object Preview : ChartSource

    @Serializable @SerialName("ride") public data class Ride(public val rideId: RideId) : ChartSource

    @Serializable @SerialName("imported") public data class Imported(public val importId: RideId) : ChartSource
}

@Serializable public data class ChartHeadInput(public val source: ChartSource)

@Serializable
public data class ChartHeadView(
    public val revision: Long,
    public val rangeUs: TimeRange,
    public val live: Boolean,
    public val originMonoUs: Long?,
    public val metrics: List<MetricId>,
    public val catchingUp: Boolean,
)

@Serializable
public data class PlotInput(
    public val source: ChartSource,
    public val metrics: List<MetricId>,
    public val key: String,
    public val rangeUs: TimeRange?,
    public val buckets: Int,
    public val smoothingUs: Long,
)

@Serializable
public data class PlotView(
    public val revision: Long,
    public val rangeUs: TimeRange,
    public val slotUs: Long?,
    public val series: List<PlotSeries>,
)

@Serializable
public data class PlotSeries(
    public val metric: MetricId,
    public val segment: List<Int>,
    public val firstUs: List<Long>,
    public val first: List<Double>,
    public val minUs: List<Long>,
    public val min: List<Double>,
    public val maxUs: List<Long>,
    public val max: List<Double>,
    public val lastUs: List<Long>,
    public val last: List<Double>,
)

@Serializable
public data class LatestInput(
    public val source: ChartSource,
    public val metrics: List<MetricId>,
    public val key: String,
    public val smoothingUs: Long,
)

@Serializable public data class LatestView(public val revision: Long, public val readings: List<LatestReading>)

@Serializable
public data class LatestReading(
    public val metric: MetricId,
    public val value: Double?,
    public val elapsedUs: Long?,
    public val freshUntilMonoUs: Long?,
    public val tailUntilMonoUs: Long?,
    public val rideAverage: Double?,
)

@Serializable
public data class InspectInput(
    public val source: ChartSource,
    public val metrics: List<MetricId>,
    public val key: String,
    public val atUs: Long,
    public val anchor: Anchor?,
    public val smoothingUs: Long,
)

@Serializable public data class Anchor(public val metric: MetricId, public val elapsedUs: Long)

@Serializable
public data class InspectView(public val revision: Long, public val atUs: Long, public val values: List<InspectedValue>)

@Serializable
public data class InspectedValue(public val metric: MetricId, public val original: Original?, public val gap: Boolean)

@Serializable public data class Original(public val value: Double, public val utcUs: Long, public val elapsedUs: Long)

@Serializable
public data class StatisticsInput(
    public val source: ChartSource,
    public val metrics: List<MetricId>,
    public val key: String,
    public val rangeUs: TimeRange,
    public val smoothingUs: Long,
    public val whPerKmCharted: Boolean,
)

@Serializable
public data class StatisticsView(
    public val revision: Long,
    public val statistics: List<MetricRangeStatistics>,
    public val nextUs: Long?,
)

@Serializable
public data class MetricRangeStatistics(
    public val metric: MetricId,
    public val min: Original?,
    public val average: Double?,
    public val max: Original?,
    public val total: Double?,
    public val ratio: Double?,
    public val partial: Boolean,
)

public object ChartsPolicy {
    public const val statisticsRefreshUs: Long = 2_000_000L
    public const val finalTileLagUs: Long = 10_000_000L
    public const val energySeries: Int = 2
}
