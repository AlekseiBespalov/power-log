package app.powerlog.domain.health

import app.powerlog.domain.Sha256
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Ride

public object HealthPolicy {
    public const val bufferUs: Long = 3_000_000L
    public const val maxRiderPowerW: Double = 5_000.0
    public const val maxCadenceRpm: Double = 300.0
    public const val maxSpeedMps: Double = 40.0
    public const val maxSpeedFixAccuracyM: Double = 50.0
    public const val routeAccuracyM: Double = 50.0
    public const val appleRouteCapacity: Int = 30_000
    public const val healthConnectRouteBudgetBytes: Long = 900_000L
    public const val healthConnectSessionTitle: String = "Power Log ride"
    public val readbackAfterFinishUs: List<Long> = listOf(2_000_000L, 900_000_000L, 3_600_000_000L)
}

public object HealthSamples {
    public fun sampleKey(sampleId: String, member: Int?, startUtcUs: Long, endUtcUs: Long, value: Double?): String {
        val id = sampleId.lowercase()
        if (member == null || value == null) return id
        val text = "$id:${bits(seconds(startUtcUs))}:${bits(seconds(endUtcUs))}:${bits(value)}"
        val hex = Sha256.hash(text.encodeToByteArray())
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun seconds(utcUs: Long): Double = utcUs.toDouble() / 1_000_000.0

    private fun bits(value: Double): String = value.toRawBits().toULong().toString()

    public fun inRide(ride: Ride, metric: HealthMetric, startUtcUs: Long, endUtcUs: Long): Boolean {
        val rideEnd = ride.end?.utcUs
        return when (HealthMetrics.metricId(metric)) {
            MetricId.activeEnergyKcal,
            MetricId.basalEnergyKcal ->
                startUtcUs < endUtcUs && endUtcUs > ride.startedUtcUs && (rideEnd == null || startUtcUs < rideEnd)
            else -> endUtcUs >= ride.startedUtcUs && (rideEnd == null || endUtcUs <= rideEnd)
        }
    }

    public fun place(
        ride: Ride,
        timeline: ActiveTimeline,
        startUtcUs: Long?,
        endUtcUs: Long,
        representation: HealthRepresentation,
    ): SamplePlace {
        val utcUs =
            if (representation == HealthRepresentation.intervalRecord && startUtcUs != null) startUtcUs else endUtcUs
        val elapsedUs = utcUs - ride.startedUtcUs
        return SamplePlace(utcUs, elapsedUs, timeline.intervalAt(elapsedUs))
    }
}

public data class SamplePlace(public val utcUs: Long, public val elapsedUs: Long, public val activeInterval: Int?)

public object HealthIds {
    public const val syncVersion: Int = 1

    private const val PREFIX = "powerlog:"

    public fun workout(rideId: RideId): String = "$PREFIX${rideId.value}:workout"

    public fun route(rideId: RideId): String = "$PREFIX${rideId.value}:route"

    public fun lap(rideId: RideId, event: EventId): String = "$PREFIX${rideId.value}:lap:event:${event.value}"

    public fun second(rideId: RideId, quantity: HealthQuantity, n: Long): String =
        "$PREFIX${rideId.value}:${quantity.serialName()}:second:$n"

    public fun distance(rideId: RideId, firstSeq: Long, lastSeq: Long): String =
        "$PREFIX${rideId.value}:${HealthQuantity.cyclingDistance.serialName()}:interval:" +
            "seq:phone:$firstSeq:seq:phone:$lastSeq"

    public fun isOwn(syncId: String?): Boolean = syncId?.startsWith(PREFIX) == true

    private fun HealthQuantity.serialName(): String = HealthQuantity.serializer().descriptor.getElementName(ordinal)
}

public object PublicationRules {
    private val retryable =
        setOf(HealthReason.permission, HealthReason.quota, HealthReason.locked, HealthReason.transientFailure)

    public fun retryAllowed(state: ComponentState, reason: HealthReason?): Boolean =
        state == ComponentState.failed && reason in retryable
}
