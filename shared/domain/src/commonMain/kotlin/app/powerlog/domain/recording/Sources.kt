package app.powerlog.domain.recording

import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import kotlinx.serialization.Serializable

@Serializable
public enum class SourceKind {
    controller,
    heartRate,
    gps,
}

@Serializable
public enum class SourceState {
    off,
    waiting,
    receiving,
    weak,
    stale,
    paused,
    denied,
    unavailable,
    backfilled,
}

public object Sources {
    private val ageing = setOf(SourceState.waiting, SourceState.receiving, SourceState.weak, SourceState.stale)

    public fun state(
        kind: SourceKind,
        requested: Boolean,
        phase: RidePhase?,
        ageUs: Long?,
        accuracyM: Double?,
        reported: SourceState?,
    ): SourceState {
        if (!requested) return SourceState.off
        if (phase == RidePhase.paused) return SourceState.paused
        val state =
            reported
                ?: when {
                    ageUs == null -> SourceState.waiting
                    kind != SourceKind.gps -> SourceState.receiving
                    accuracyM == null || !accuracyM.isFinite() || accuracyM < 0 -> SourceState.unavailable
                    accuracyM > LocationPolicy.weakAccuracyM -> SourceState.weak
                    else -> SourceState.receiving
                }
        if (ageUs == null && (state == SourceState.receiving || state == SourceState.weak)) return SourceState.waiting
        if (state in ageing && ageUs != null && ageUs >= holdUs(kind)) return SourceState.stale
        return state
    }

    internal fun holdUs(kind: SourceKind): Long =
        requireNotNull(
            MetricCatalog.of(
                    when (kind) {
                        SourceKind.controller -> MetricId.humanPowerW
                        SourceKind.heartRate -> MetricId.heartRateBpm
                        SourceKind.gps -> MetricId.speedMps
                    }
                )
                .displayHoldUs
        )
}

public object FreshnessPolicy {
    public const val rideCardRefreshUs: Long = 15_000_000L

    public fun fresh(acquiredMonoUs: Long?, nowMonoUs: Long, windowUs: Long): Boolean =
        acquiredMonoUs != null && nowMonoUs - acquiredMonoUs in 0 until windowUs

    public fun rideCardFreshUntilUtcUs(kind: SourceKind, acquiredMonoUs: Long, now: ClockReading): Long =
        now.utcUs + Sources.holdUs(kind) + rideCardRefreshUs - (now.monoUs - acquiredMonoUs)
}

public object StartPolicy {
    public const val windowUs: Long = 15_000_000L

    public fun deadline(startMonoUs: Long): Long = startMonoUs + windowUs

    public fun expired(deadlineMonoUs: Long, nowMonoUs: Long): Boolean = nowMonoUs >= deadlineMonoUs
}
