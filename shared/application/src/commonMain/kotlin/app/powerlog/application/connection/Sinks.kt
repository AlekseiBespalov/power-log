package app.powerlog.application.connection

import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.ClockReading

internal interface ReadingSink {
    fun previewStarted(previewId: RideId, originMonoUs: Long)

    fun controllerVerified(identity: ControllerIdentity, capabilities: ControllerCapabilities)

    fun readingReceived(values: Map<MetricId, Double>, at: ClockReading)
}

internal interface TraceSink {
    val capturing: Boolean

    fun record(event: String, detail: String, bytes: ByteArray?, at: ClockReading)
}

internal class ConnectionSummary(
    val replyMeanMs: Long?,
    val replyMaxMs: Long?,
    val notificationErrors: Int,
    val discardedBytes: Long,
    val requestedHz: Int?,
)

internal class ConnectionSnapshot(
    val state: ConnectionState,
    val identity: ControllerIdentity?,
    val sampleRateHz: Int?,
    val background: Boolean,
    val connectionAttempts: Int,
    val reconnects: Int,
    val lastSampleAgeMs: Long?,
    val lastGapMs: Long?,
    val recentSampleHz: Double?,
)
