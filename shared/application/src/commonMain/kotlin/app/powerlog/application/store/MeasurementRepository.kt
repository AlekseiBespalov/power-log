package app.powerlog.application.store

import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.TimeRange

public interface MeasurementRepository {
    // null: absent or deleted
    public suspend fun extent(rideId: RideId): StoreResult<Extent?>

    // session_no order
    public suspend fun sessions(rideId: RideId): StoreResult<List<ControllerSession>>

    // seq order, which is elapsed order
    public suspend fun events(rideId: RideId): StoreResult<List<RideEvent>>

    public suspend fun telemetry(
        rideId: RideId,
        range: TimeRange,
        after: TelemetryKey?,
        limit: Int,
    ): StoreResult<TelemetryPage>

    public suspend fun locations(
        rideId: RideId,
        range: TimeRange,
        after: LocationKey?,
        limit: Int,
    ): StoreResult<LocationPage>

    // metric null: every metric
    public suspend fun health(
        rideId: RideId,
        range: TimeRange,
        metric: HealthMetric?,
        after: HealthKey?,
        limit: Int,
        source: HealthSourceFilter? = null,
    ): StoreResult<HealthSamplePage>

    public suspend fun nearest(
        rideId: RideId,
        target: NearestTarget,
        atUs: Long,
        before: Boolean,
        withinUs: Long,
    ): StoreResult<Long?>

    public suspend fun lastFact(rideId: RideId): StoreResult<RetainedEnd?>
}

// a null field matches every sample
public data class HealthSourceFilter(
    public val producer: Producer?,
    public val dataOrigin: String?,
    public val representations: Set<HealthRepresentation>?,
)

// each range is [first, last + 1)
public data class Extent(
    public val revision: Long,
    public val telemetry: TimeRange?,
    public val locations: TimeRange?,
    public val health: TimeRange?,
    public val healthMetrics: Set<HealthMetric>,
    public val healthProducers: Set<Producer>,
)

public sealed interface NearestTarget {
    // a metric with a telemetry column
    public data class Telemetry(public val metric: MetricId) : NearestTarget

    // a metric with a location column
    public data class Location(public val metric: MetricId) : NearestTarget

    public data class Health(public val metrics: Set<HealthMetric>, public val source: HealthSourceFilter?) :
        NearestTarget
}

public class TelemetryPage(public val rows: TelemetryRows, public val next: TelemetryKey?)

public data class TelemetryKey(public val elapsedUs: Long, public val sessionNo: Int, public val seq: Long)

public data class LocationPage(public val fixes: List<LocationFix>, public val next: LocationKey?)

public data class LocationKey(public val elapsedUs: Long, public val seq: Long)

public data class HealthSamplePage(public val samples: List<HealthSample>, public val next: HealthKey?)

// order is opaque
public data class HealthKey(public val elapsedUs: Long, public val order: Long)
