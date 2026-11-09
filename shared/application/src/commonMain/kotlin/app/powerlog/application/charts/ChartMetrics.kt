package app.powerlog.application.charts

import app.powerlog.application.store.NearestTarget
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin

internal enum class ChartKind {
    telemetry,
    location,
    health,
    distance,
    consumption,
    energyPerDistance,
}

internal object ChartMetrics {
    private val distances =
        setOf(MetricId.distanceMeters, MetricId.gpsDistanceMeters, MetricId.controllerDistanceMeters)
    private val consumption = ConsumptionMetric.entries.map { MetricId.valueOf(it.name) }.toSet()
    private val energyPerDistance = setOf(MetricId.batteryWhPerKm, MetricId.riderWhPerKm)

    fun kind(metric: MetricId): ChartKind =
        when {
            metric in distances -> ChartKind.distance
            metric in consumption -> ChartKind.consumption
            metric in energyPerDistance -> ChartKind.energyPerDistance
            metric == MetricId.controllerSpeedMps -> ChartKind.telemetry
            else ->
                when (MetricCatalog.of(metric).origin) {
                    MetricOrigin.telemetry -> ChartKind.telemetry
                    MetricOrigin.location -> ChartKind.location
                    MetricOrigin.health -> ChartKind.health
                    MetricOrigin.derived -> error("$metric has no chart reading")
                }
        }

    fun derived(metric: MetricId): Boolean =
        kind(metric).let { it == ChartKind.distance || it == ChartKind.consumption }

    fun distanceSource(metric: MetricId, frozen: DistanceSource): DistanceSource =
        when (metric) {
            MetricId.gpsDistanceMeters -> DistanceSource.phoneGps
            MetricId.controllerDistanceMeters -> DistanceSource.controller
            else -> frozen
        }

    fun consumptionMetric(metric: MetricId): ConsumptionMetric = ConsumptionMetric.valueOf(metric.name)

    fun target(metric: MetricId): NearestTarget =
        when (kind(metric)) {
            ChartKind.telemetry ->
                NearestTarget.Telemetry(if (metric == MetricId.controllerSpeedMps) MetricId.speedRaw else metric)
            ChartKind.location -> NearestTarget.Location(metric)
            else -> error("$metric has no stored original")
        }

    fun available(
        sessions: Collection<ControllerSession>,
        locations: Boolean,
        health: Set<MetricId>,
        derived: Derived?,
    ): List<MetricId> {
        val columns = sessions.flatMapTo(mutableSetOf()) { it.capabilities.metrics }
        val controllerSpeed = sessions.any { it.capabilities.controllerSpeed }
        val metrics = buildSet {
            addAll(columns)
            if (controllerSpeed) add(MetricId.controllerSpeedMps)
            if (locations) MetricCatalog.all.filter { it.origin == MetricOrigin.location }.mapTo(this) { it.id }
            addAll(health)
            if (derived != null) {
                val distance =
                    when (derived.distance) {
                        DistanceSource.controller -> controllerSpeed
                        DistanceSource.phoneGps -> locations
                    }
                if (controllerSpeed) add(MetricId.controllerDistanceMeters)
                if (locations) add(MetricId.gpsDistanceMeters)
                if (distance) add(MetricId.distanceMeters)
                if (MetricId.consumedAh in columns) add(MetricId.controllerChargeAh)
                if (MetricId.consumedWh in columns) add(MetricId.controllerEnergyWh)
                if (MetricId.batteryCurrentA in columns) add(MetricId.calculatedChargeAh)
                if (MetricId.batteryCurrentA in columns && MetricId.batteryVoltageV in columns)
                    add(MetricId.calculatedEnergyWh)
                val energy =
                    when (derived.battery) {
                        BatteryMethod.controller -> MetricId.controllerEnergyWh
                        BatteryMethod.calculated -> MetricId.calculatedEnergyWh
                    }
                if (distance && energy in this) add(MetricId.batteryWhPerKm)
                if (distance && MetricId.humanPowerW in columns) add(MetricId.riderWhPerKm)
            }
        }
        return MetricId.entries.filter { it in metrics }
    }

    class Derived(val distance: DistanceSource, val battery: BatteryMethod)
}
