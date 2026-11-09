package app.powerlog.application.recording

import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId

internal interface LiveReadings {
    fun snapshot(): LiveSnapshot
}

internal data class LiveSnapshot(
    val rideId: RideId?,
    val previewId: RideId?,
    val previewOriginMonoUs: Long?,
    val rideOriginMonoUs: Long?,
    val newest: Map<MetricId, LiveValue>,
    val totals: Map<MetricId, LiveValue>,
    val rideAverage: Map<MetricId, Double?>,
)

internal data class LiveValue(val value: Double, val elapsedUs: Long, val acquiredMonoUs: Long)
