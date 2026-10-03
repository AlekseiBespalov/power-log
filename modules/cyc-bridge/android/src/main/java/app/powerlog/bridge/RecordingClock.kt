package app.powerlog.bridge

import android.os.SystemClock

internal data class RecordingClockReading(val timestamp: String, val monotonicMillis: Long)

internal fun interface RecordingClock {
    fun read(): RecordingClockReading
}

internal object SystemRecordingClock : RecordingClock {
    override fun read(): RecordingClockReading {
        val utcMillis = System.currentTimeMillis()
        val monotonicMillis = SystemClock.elapsedRealtime()
        return RecordingClockReading(iso(utcMillis), monotonicMillis)
    }
}
