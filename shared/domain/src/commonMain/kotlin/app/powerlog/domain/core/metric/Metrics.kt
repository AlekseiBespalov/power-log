package app.powerlog.domain.core.metric

import kotlin.math.nextDown
import kotlinx.serialization.Serializable

@Serializable
public enum class MetricId {
    humanPowerW,
    cadenceRpm,
    heartRateBpm,
    pedalTorqueNm,
    activeEnergyKcal,
    basalEnergyKcal,
    batteryVoltageV,
    batteryCurrentA,
    motorInputPowerW,
    consumedAh,
    consumedWh,
    motorCurrentA,
    motorTempC,
    controllerTempC,
    motorRpm,
    throttleVoltageV,
    speedMps,
    controllerSpeedMps,
    distanceMeters,
    gpsDistanceMeters,
    controllerDistanceMeters,
    controllerEnergyWh,
    controllerChargeAh,
    calculatedEnergyWh,
    calculatedChargeAh,
    batteryWhPerKm,
    riderWhPerKm,
    altitudeMeters,
    horizontalAccuracyM,
    verticalAccuracyM,
    courseDegrees,
    assistLevel,
    raceMode,
    faultCode,
    speedRaw,
}

@Serializable
public enum class MetricOrigin {
    telemetry,
    location,
    health,
    derived,
}

@Serializable
public enum class MetricSemantics {
    instantaneous,
    cumulative,
    counter,
    distance,
    category,
    circular,
    ratio,
}

@Serializable
public enum class UnitSystem {
    metric,
    imperial,
}

@Serializable
public enum class TemperatureUnit {
    celsius,
    fahrenheit,
}

public data class MetricDefinition(
    public val id: MetricId,
    public val siUnit: String,
    public val origin: MetricOrigin,
    public val semantics: MetricSemantics,
    public val quantum: Long,
    public val validRange: ClosedFloatingPointRange<Double>?,
    public val integrationGapUs: Long,
    public val plotGapUs: Long,
    public val plotRange: ClosedFloatingPointRange<Double>?,
    public val statisticsRange: ClosedFloatingPointRange<Double>?,
    public val displayHoldUs: Long?,
    public val tailUs: Long,
    public val allowsMean: Boolean,
    public val selectable: Boolean,
)

public object MetricCatalog {
    private const val SECOND_US = 1_000_000L
    private const val HELD_TAIL_US = 6 * SECOND_US
    private const val INTEGRATION_GAP_US = 2_500_000L
    private val nonNegative = 0.0..Double.POSITIVE_INFINITY

    private class Entry(
        val id: MetricId,
        val siUnit: String,
        val origin: MetricOrigin,
        val semantics: MetricSemantics,
        val quantum: Long,
        val validRange: ClosedFloatingPointRange<Double>?,
        val holdUs: Long,
        val plotGapUs: Long = holdUs,
        val integrationGapUs: Long = INTEGRATION_GAP_US,
        val plotRange: ClosedFloatingPointRange<Double>? = validRange,
        val statisticsRange: ClosedFloatingPointRange<Double>? = validRange,
        val total: Boolean = false,
        val mean: Boolean = semantics == MetricSemantics.instantaneous,
    ) {
        fun definition(): MetricDefinition =
            MetricDefinition(
                id = id,
                siUnit = siUnit,
                origin = origin,
                semantics = semantics,
                quantum = quantum,
                validRange = validRange,
                integrationGapUs = integrationGapUs,
                plotGapUs = plotGapUs,
                plotRange = plotRange,
                statisticsRange = statisticsRange,
                displayHoldUs = holdUs.takeUnless { total },
                tailUs = minOf(HELD_TAIL_US, plotGapUs),
                allowsMean = mean,
                selectable = id != MetricId.speedRaw && id != MetricId.basalEnergyKcal,
            )
    }

    private fun controller(
        id: MetricId,
        unit: String,
        quantum: Long,
        valid: ClosedFloatingPointRange<Double>? = null,
        semantics: MetricSemantics = MetricSemantics.instantaneous,
        plot: ClosedFloatingPointRange<Double>? = valid,
    ): Entry = Entry(id, unit, MetricOrigin.telemetry, semantics, quantum, valid, 6 * SECOND_US, plotRange = plot)

    private fun location(
        id: MetricId,
        unit: String,
        quantum: Long,
        valid: ClosedFloatingPointRange<Double>?,
        unbounded: ClosedFloatingPointRange<Double>?,
        integrationGapUs: Long = 10 * SECOND_US,
        semantics: MetricSemantics = MetricSemantics.instantaneous,
        plot: ClosedFloatingPointRange<Double>? = unbounded,
    ): Entry =
        Entry(
            id,
            unit,
            MetricOrigin.location,
            semantics,
            quantum,
            valid,
            10 * SECOND_US,
            integrationGapUs = integrationGapUs,
            plotRange = plot,
            statisticsRange = unbounded,
        )

    private fun health(
        id: MetricId,
        unit: String,
        quantum: Long,
        valid: ClosedFloatingPointRange<Double>,
        holdUs: Long,
        plotGapUs: Long = holdUs,
        integrationGapUs: Long = INTEGRATION_GAP_US,
        mean: Boolean,
    ): Entry =
        Entry(
            id,
            unit,
            MetricOrigin.health,
            MetricSemantics.instantaneous,
            quantum,
            valid,
            holdUs,
            plotGapUs = plotGapUs,
            integrationGapUs = integrationGapUs,
            mean = mean,
        )

    private fun total(id: MetricId, unit: String, semantics: MetricSemantics, plotGapUs: Long = 6 * SECOND_US): Entry =
        Entry(id, unit, MetricOrigin.derived, semantics, 1_000_000L, nonNegative, plotGapUs, total = true)

    private val entries: List<Entry> =
        listOf(
            controller(MetricId.humanPowerW, "W", 1_000L, 0.0..32_766.0, plot = null),
            controller(MetricId.cadenceRpm, "rpm", 10_000L, 0.0..254.0, plot = null),
            health(
                MetricId.heartRateBpm,
                "bpm",
                1_000L,
                1.0..254.0,
                15 * SECOND_US,
                plotGapUs = 30 * SECOND_US,
                integrationGapUs = 10 * SECOND_US,
                mean = true,
            ),
            controller(MetricId.pedalTorqueNm, "Nm", 100L),
            health(MetricId.activeEnergyKcal, "kcal", 1_000_000L, nonNegative, 120 * SECOND_US, mean = false),
            health(MetricId.basalEnergyKcal, "kcal", 1_000_000L, nonNegative, 120 * SECOND_US, mean = false),
            controller(MetricId.batteryVoltageV, "V", 10L),
            controller(MetricId.batteryCurrentA, "A", 100L),
            controller(MetricId.motorInputPowerW, "W", 1_000L),
            controller(MetricId.consumedAh, "Ah", 10_000L, semantics = MetricSemantics.counter),
            controller(MetricId.consumedWh, "Wh", 10_000L, semantics = MetricSemantics.counter),
            controller(MetricId.motorCurrentA, "A", 100L),
            controller(MetricId.motorTempC, "°C", 10L),
            controller(MetricId.controllerTempC, "°C", 10L),
            controller(MetricId.motorRpm, "rpm", 1L),
            controller(MetricId.throttleVoltageV, "V", 100L),
            location(MetricId.speedMps, "m/s", 1_000_000L, 0.0..40.0, nonNegative),
            Entry(
                MetricId.controllerSpeedMps,
                "m/s",
                MetricOrigin.derived,
                MetricSemantics.instantaneous,
                1_000_000L,
                0.0..40.0,
                6 * SECOND_US,
                plotRange = nonNegative,
                statisticsRange = nonNegative,
            ),
            total(MetricId.distanceMeters, "m", MetricSemantics.distance, plotGapUs = 120 * SECOND_US),
            total(MetricId.gpsDistanceMeters, "m", MetricSemantics.distance, plotGapUs = 2_500_000L),
            total(MetricId.controllerDistanceMeters, "m", MetricSemantics.distance, plotGapUs = 2_500_000L),
            total(MetricId.controllerEnergyWh, "Wh", MetricSemantics.cumulative),
            total(MetricId.controllerChargeAh, "Ah", MetricSemantics.cumulative),
            total(MetricId.calculatedEnergyWh, "Wh", MetricSemantics.cumulative),
            total(MetricId.calculatedChargeAh, "Ah", MetricSemantics.cumulative),
            total(MetricId.batteryWhPerKm, "Wh/km", MetricSemantics.ratio),
            total(MetricId.riderWhPerKm, "Wh/km", MetricSemantics.ratio),
            location(MetricId.altitudeMeters, "m", 1_000L, -500.0..20_000.0, null),
            location(MetricId.horizontalAccuracyM, "m", 1_000L, 0.0..50.0, nonNegative),
            location(MetricId.verticalAccuracyM, "m", 1_000L, 0.0..20.0, nonNegative),
            location(
                MetricId.courseDegrees,
                "°",
                1_000L,
                nonNegative,
                nonNegative,
                integrationGapUs = INTEGRATION_GAP_US,
                semantics = MetricSemantics.circular,
                plot = 0.0..360.0.nextDown(),
            ),
            controller(MetricId.assistLevel, "", 1L, semantics = MetricSemantics.category),
            controller(MetricId.raceMode, "", 1L, semantics = MetricSemantics.category),
            controller(MetricId.faultCode, "", 1L, semantics = MetricSemantics.category),
            controller(MetricId.speedRaw, "raw", 100L),
        )

    private val definitions: Map<MetricId, MetricDefinition> = entries.associate { it.id to it.definition() }

    public val all: List<MetricDefinition>
        get() = MetricId.entries.map { definitions.getValue(it) }

    public fun of(id: MetricId): MetricDefinition = definitions.getValue(id)

    public fun shown(selected: List<MetricId>, available: Set<MetricId>): List<MetricId> {
        val rawSpeed = MetricId.speedRaw in available && MetricId.controllerSpeedMps !in available
        return if (rawSpeed) selected.map { if (it == MetricId.controllerSpeedMps) MetricId.speedRaw else it }
        else selected
    }

    public fun stored(metric: MetricId): MetricId =
        if (metric == MetricId.speedRaw) MetricId.controllerSpeedMps else metric
}
