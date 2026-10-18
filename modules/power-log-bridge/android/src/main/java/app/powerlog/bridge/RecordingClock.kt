package app.powerlog.bridge

import android.os.SystemClock
import app.powerlog.core.telemetry.TelemetryCaptureAdmission
import java.time.Instant

internal data class RecordingClockReading(
    val timestamp: String,
    val monotonicMillis: Long,
    val wallClockSeconds: Double = Instant.parse(timestamp).toEpochMilli() / 1000.0,
) {
    fun lifecycle(epoch: String) =
        app.powerlog.core.lifecycle.LifecycleClock(
            app.powerlog.core.protocol.Timestamps.parse(timestamp),
            epoch,
            monotonicMillis * 1000,
        )

    fun discontinuity(previous: RecordingClockReading): Double? =
        TelemetryCaptureAdmission.discontinuity(
            previous.wallClockSeconds,
            wallClockSeconds,
            previous.monotonicMillis / 1000.0,
            monotonicMillis / 1000.0,
        )
}

internal fun interface RecordingClock {
    fun read(): RecordingClockReading
}

internal object SystemRecordingClock : RecordingClock {
    override fun read(): RecordingClockReading {
        val utcMillis = System.currentTimeMillis()
        val monotonicMillis = SystemClock.elapsedRealtime()
        return RecordingClockReading(iso(utcMillis), monotonicMillis, utcMillis / 1000.0)
    }
}
