package app.powerlog.domain.core.health

import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RideOptions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class HealthProviderKind {
    appleHealth,
    healthConnect,
}

@Serializable
public enum class HealthWriter {
    phone,
    watch,
}

@Serializable
public enum class HealthOutcome {
    pending,
    saved,
    failed,
    notSaved,
    unavailable,
    unknown,
    discarded,
    notRequested,
}

@Serializable
public enum class HealthReason {
    permission,
    locked,
    quota,
    invalid,
    ambiguous,
    builderLost,
    readbackUnavailable,
    noValidReading,
    transientFailure,
    clock,
}

@Serializable
public enum class HealthComponent {
    workout,
    route,
    laps,
    riderPower,
    cyclingCadence,
    cyclingSpeed,
    cyclingDistance,
    association,
    readback,
}

@Serializable
public enum class ComponentState {
    notRequested,
    pending,
    submitted,
    saved,
    unavailable,
    failed,
}

@Serializable
public enum class HealthQuantity {
    @SerialName("rider-power") riderPower,
    @SerialName("cycling-cadence") cyclingCadence,
    @SerialName("cycling-speed") cyclingSpeed,
    @SerialName("cycling-distance") cyclingDistance,
}

public data class RideHealth(
    public val provider: HealthProviderKind?,
    public val outcome: HealthOutcome,
    public val reason: HealthReason?,
    public val workoutId: String?,
)

@Serializable
public data class ComponentResult(
    public val component: HealthComponent,
    public val state: ComponentState,
    public val reason: HealthReason?,
)

public object HealthMetrics {
    public fun metricId(metric: HealthMetric): MetricId? =
        when (metric.wire) {
            "heartRateBpm",
            "heartRate" -> MetricId.heartRateBpm
            "activeEnergyKcal",
            "activeEnergy" -> MetricId.activeEnergyKcal
            "basalEnergyKcal" -> MetricId.basalEnergyKcal
            else -> null
        }

    public fun of(metric: MetricId, provider: HealthProviderKind): HealthMetric? =
        when (provider) {
            HealthProviderKind.appleHealth ->
                when (metric) {
                    MetricId.heartRateBpm -> "heartRateBpm"
                    MetricId.activeEnergyKcal -> "activeEnergyKcal"
                    MetricId.basalEnergyKcal -> "basalEnergyKcal"
                    else -> null
                }
            HealthProviderKind.healthConnect ->
                when (metric) {
                    MetricId.heartRateBpm -> "heartRate"
                    MetricId.activeEnergyKcal -> "activeEnergy"
                    else -> null
                }
        }?.let(::HealthMetric)

    public fun readback(provider: HealthProviderKind): List<HealthMetric> =
        when (provider) {
            HealthProviderKind.appleHealth -> appleReadback
            HealthProviderKind.healthConnect -> healthConnectReadback
        }

    private val appleReadback =
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
            )
            .map(::HealthMetric)

    private val healthConnectReadback =
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
            )
            .map(::HealthMetric)
}

public data class OriginCoverage(public val dataOrigin: String, public val coveredUs: Long)

public object HealthSourceSelection {
    public fun select(scores: List<OriginCoverage>): OriginCoverage? = scores.minWithOrNull { a, b ->
        val coverage = b.coveredUs.compareTo(a.coveredUs)
        if (coverage != 0) coverage else compareUtf8(a.dataOrigin, b.dataOrigin)
    }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val order = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (order != 0) return order
        }
        return x.size.compareTo(y.size)
    }
}

public object HealthOutcomeRules {
    public fun initial(options: RideOptions, provider: HealthProviderKind?): RideHealth =
        RideHealth(
            provider = provider,
            outcome = if (options.saveToHealth) HealthOutcome.pending else HealthOutcome.notRequested,
            reason = null,
            workoutId = null,
        )

    public fun initialComponents(options: RideOptions): List<ComponentResult> =
        if (options.healthWriter == HealthWriter.phone)
            HealthComponent.entries.map { ComponentResult(it, ComponentState.pending, null) }
        else emptyList()

    public fun next(current: RideHealth, observed: RideHealth): RideHealth? {
        val allowed =
            when (current.outcome) {
                HealthOutcome.unavailable ->
                    observed.outcome == HealthOutcome.unavailable && observed.reason == current.reason
                HealthOutcome.notRequested -> observed.outcome == HealthOutcome.discarded
                HealthOutcome.discarded -> false
                else -> true
            }
        return if (allowed && observed != current) observed else null
    }
}
