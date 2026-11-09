package app.powerlog.data

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import kotlinx.serialization.KSerializer

internal class ConstraintViolation(message: String) : IllegalStateException(message)

internal class Vocabulary<T : Enum<T>>(serializer: KSerializer<T>, entries: List<T>) {
    private val names = entries.associateWith { serializer.descriptor.getElementName(it.ordinal) }
    private val values = names.entries.associate { (value, name) -> name to value }

    fun name(value: T): String = names.getValue(value)

    fun value(name: String): T = values[name] ?: throw ConstraintViolation(name)

    fun valueOrNull(name: String): T? = values[name]
}

internal object Vocabularies {
    val phase = Vocabulary(RidePhase.serializer(), RidePhase.entries)
    val device = Vocabulary(Device.serializer(), Device.entries)
    val distanceSource = Vocabulary(DistanceSource.serializer(), DistanceSource.entries)
    val batteryMethod = Vocabulary(BatteryMethod.serializer(), BatteryMethod.entries)
    val provider = Vocabulary(HealthProviderKind.serializer(), HealthProviderKind.entries)
    val outcome = Vocabulary(HealthOutcome.serializer(), HealthOutcome.entries)
    val reason = Vocabulary(HealthReason.serializer(), HealthReason.entries)
    val component = Vocabulary(HealthComponent.serializer(), HealthComponent.entries)
    val componentState = Vocabulary(ComponentState.serializer(), ComponentState.entries)
    val producer = Vocabulary(Producer.serializer(), Producer.entries)
    val representation = Vocabulary(HealthRepresentation.serializer(), HealthRepresentation.entries)
    val eventKind = Vocabulary(RideEventKind.serializer(), RideEventKind.entries)
    val altitudeDatum = Vocabulary(AltitudeDatum.serializer(), AltitudeDatum.entries)

    fun sampleRate(hz: Long): SampleRate =
        SampleRate.entries.firstOrNull { it.hz.toLong() == hz } ?: throw ConstraintViolation("$hz Hz")

    fun flag(value: Long): Boolean =
        when (value) {
            0L -> false
            1L -> true
            else -> throw ConstraintViolation("flag $value")
        }
}

internal val TELEMETRY_METRICS: List<MetricId> =
    listOf(
        MetricId.humanPowerW,
        MetricId.cadenceRpm,
        MetricId.motorInputPowerW,
        MetricId.batteryVoltageV,
        MetricId.batteryCurrentA,
        MetricId.motorCurrentA,
        MetricId.motorRpm,
        MetricId.pedalTorqueNm,
        MetricId.controllerTempC,
        MetricId.motorTempC,
        MetricId.consumedAh,
        MetricId.consumedWh,
        MetricId.throttleVoltageV,
        MetricId.faultCode,
        MetricId.assistLevel,
        MetricId.speedRaw,
        MetricId.raceMode,
    )

internal val TELEMETRY_METRIC_SET: Set<MetricId> = TELEMETRY_METRICS.toSet()

internal fun requireTelemetryColumns(metrics: Set<MetricId>) {
    if (!TELEMETRY_METRIC_SET.containsAll(metrics)) {
        throw ConstraintViolation("no telemetry column for ${metrics - TELEMETRY_METRIC_SET}")
    }
}

internal enum class LocationColumn(val sql: String, val idb: String) {
    speed("speed_mps", "speedMps"),
    altitude("msl_m", "mslM"),
    verticalAccuracy("msl_v_acc_m", "mslVAccM"),
    horizontalAccuracy("h_acc_m", "hAccM"),
    course("course_deg", "courseDeg");

    companion object {
        fun of(metric: MetricId): LocationColumn? =
            when (metric) {
                MetricId.speedMps -> speed
                MetricId.altitudeMeters -> altitude
                MetricId.verticalAccuracyM -> verticalAccuracy
                MetricId.horizontalAccuracyM -> horizontalAccuracy
                MetricId.courseDegrees -> course
                else -> null
            }
    }
}
