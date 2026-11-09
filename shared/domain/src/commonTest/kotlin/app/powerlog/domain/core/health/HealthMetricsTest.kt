package app.powerlog.domain.core.health

import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.metric.MetricId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthMetricsTest {
    @Test
    fun eachProvidersNamePairsWithItsMetric() {
        assertEquals(MetricId.heartRateBpm, HealthMetrics.metricId(HealthMetric("heartRateBpm")))
        assertEquals(MetricId.heartRateBpm, HealthMetrics.metricId(HealthMetric("heartRate")))
        assertEquals(MetricId.activeEnergyKcal, HealthMetrics.metricId(HealthMetric("activeEnergyKcal")))
        assertEquals(MetricId.activeEnergyKcal, HealthMetrics.metricId(HealthMetric("activeEnergy")))
        assertEquals(MetricId.basalEnergyKcal, HealthMetrics.metricId(HealthMetric("basalEnergyKcal")))
        listOf("cyclingPowerW", "riderPower", "respiratoryRate", "association").forEach {
            assertNull(HealthMetrics.metricId(HealthMetric(it)), it)
        }
    }

    @Test
    fun eachProviderReadsBackItsOwnFrozenNames() {
        assertEquals(
            listOf(
                "heartRateBpm",
                "activeEnergyKcal",
                "basalEnergyKcal",
                "cyclingDistanceMeters",
                "cyclingPowerW",
                "cyclingCadenceRpm",
                "cyclingSpeedMps",
                "respiratoryRateBrpm",
                "oxygenSaturationFraction",
                "heartRateVariabilitySDNNMs",
                "physicalEffortMET",
                "cyclingFunctionalThresholdPowerW",
                "workoutEffortScore",
                "estimatedWorkoutEffortScore",
            ),
            HealthMetrics.readback(HealthProviderKind.appleHealth).map { it.wire },
        )
        assertEquals(
            listOf(
                "heartRate",
                "activeEnergy",
                "respiratoryRate",
                "oxygenSaturation",
                "hrvRMSSD",
                "riderPower",
                "cyclingCadence",
                "cyclingSpeed",
                "cyclingDistance",
                "association",
            ),
            HealthMetrics.readback(HealthProviderKind.healthConnect).map { it.wire },
        )
    }

    @Test
    fun aMetricMapsToItsProvidersNameAndBack() {
        for (provider in HealthProviderKind.entries) for (metric in MetricId.entries) {
            val name = HealthMetrics.of(metric, provider) ?: continue
            assertEquals(metric, HealthMetrics.metricId(name), "$provider $metric")
            assertEquals(true, name in HealthMetrics.readback(provider), "$provider $metric")
        }
        assertEquals(
            HealthMetric("heartRate"),
            HealthMetrics.of(MetricId.heartRateBpm, HealthProviderKind.healthConnect),
        )
        assertEquals(
            HealthMetric("basalEnergyKcal"),
            HealthMetrics.of(MetricId.basalEnergyKcal, HealthProviderKind.appleHealth),
        )
        assertNull(HealthMetrics.of(MetricId.basalEnergyKcal, HealthProviderKind.healthConnect))
        assertNull(HealthMetrics.of(MetricId.humanPowerW, HealthProviderKind.appleHealth))
    }
}
