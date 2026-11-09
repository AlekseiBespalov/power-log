package app.powerlog.domain.core.measurement

import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideEvent
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
public enum class Producer {
    controller,
    phone,
    watch,
    browser,
}

public data class ControllerSession(
    public val rideId: RideId,
    public val sessionNo: Int,
    public val identity: ControllerIdentity?,
    public val capabilities: ControllerCapabilities,
)

public data class TelemetrySample(
    public val rideId: RideId,
    public val sessionNo: Int,
    public val seq: Long,
    public val elapsedUs: Long,
    public val utcUs: Long,
    public val activeInterval: Int?,
    public val run: Int,
    public val quanta: Map<MetricId, Long>,
)

public class TelemetryRows(
    public val rows: Int,
    public val elapsedUs: DoubleArray,
    public val sessionNo: IntArray,
    public val seq: DoubleArray,
    public val utcUs: DoubleArray,
    // 0: none
    public val activeInterval: IntArray,
    public val run: IntArray,
    // NaN: not reported
    public val quanta: Map<MetricId, DoubleArray>,
) {
    public fun value(metric: MetricId, row: Int, session: ControllerSession): Double? {
        if (metric == MetricId.controllerSpeedMps) {
            if (!session.capabilities.controllerSpeed) return null
            if (metric !in quanta)
                return value(MetricId.speedRaw, row, session)?.let { it / CONTROLLER_SPEED_KMH_PER_MPS }
        }
        val count = quanta[metric]?.get(row) ?: return null
        return if (count.isNaN()) null else count / MetricCatalog.of(metric).quantum
    }

    public companion object {
        private const val CONTROLLER_SPEED_KMH_PER_MPS = 3.6

        public fun of(samples: List<TelemetrySample>): TelemetryRows {
            val metrics = samples.flatMapTo(linkedSetOf()) { it.quanta.keys }
            return TelemetryRows(
                rows = samples.size,
                elapsedUs = DoubleArray(samples.size) { samples[it].elapsedUs.toDouble() },
                sessionNo = IntArray(samples.size) { samples[it].sessionNo },
                seq = DoubleArray(samples.size) { samples[it].seq.toDouble() },
                utcUs = DoubleArray(samples.size) { samples[it].utcUs.toDouble() },
                activeInterval = IntArray(samples.size) { samples[it].activeInterval ?: 0 },
                run = IntArray(samples.size) { samples[it].run },
                quanta =
                    metrics.associateWith { metric ->
                        DoubleArray(samples.size) { samples[it].quanta[metric]?.toDouble() ?: Double.NaN }
                    },
            )
        }
    }
}

public interface TelemetryConsumer {
    public fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>)
}

// IndexedDB stores the ordinal: this order is part of ARCHIVE_VERSION.
@Serializable
public enum class AltitudeDatum {
    msl,
    wgs84Ellipsoid,
    unknown,
}

public data class LocationFix(
    public val rideId: RideId,
    public val seq: Long,
    public val elapsedUs: Long,
    public val utcUs: Long,
    public val activeInterval: Int?,
    public val run: Int,
    public val barrier: Boolean,
    public val latitude: Double,
    public val longitude: Double,
    public val horizontalAccuracyM: Double?,
    public val altitudeM: Double?,
    public val altitudeDatum: AltitudeDatum,
    public val verticalAccuracyM: Double?,
    public val mslM: Double?,
    public val mslVerticalAccuracyM: Double?,
    public val speedMps: Double?,
    public val speedAccuracyMps: Double?,
    public val courseDeg: Double?,
    public val courseAccuracyDeg: Double?,
)

@Serializable @JvmInline public value class HealthMetric(public val wire: String)

@Serializable
public enum class HealthRepresentation {
    rawQuantity,
    rawSeries,
    intervalRecord,
    builderMostRecent,
    cumulativeWorkoutTotal,
    finalWorkoutTotal,
    workoutAssociation,
    healthTombstone,
    workoutMetadata,
}

public data class HealthSample(
    public val rideId: RideId,
    public val producer: Producer,
    public val sampleKey: String,
    public val dataOrigin: String,
    public val parentId: String?,
    public val member: Int?,
    public val metric: HealthMetric?,
    public val representation: HealthRepresentation,
    public val value: Double?,
    public val sampleCount: Int?,
    public val utcUs: Long,
    public val elapsedUs: Long,
    public val startUtcUs: Long?,
    public val endUtcUs: Long?,
    public val activeInterval: Int?,
)

public sealed interface Fact {
    public data class Location(public val fix: LocationFix) : Fact

    public data class Health(public val sample: HealthSample) : Fact

    public data class Event(public val event: RideEvent) : Fact
}

@Serializable public data class OriginalRef(public val utcUs: Long, public val elapsedUs: Long, public val valueQ: Long)

public sealed interface Bounded<out T> {
    public data class Done<T>(public val value: T) : Bounded<T>

    public data object Capacity : Bounded<Nothing>
}
