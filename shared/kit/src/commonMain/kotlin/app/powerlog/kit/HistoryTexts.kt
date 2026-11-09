package app.powerlog.kit

import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RidePhase

internal object HistoryTexts {
    fun historyPhase(phase: RidePhase, interrupted: Boolean): String {
        val label =
            when (phase) {
                RidePhase.preparing -> "Preparing"
                RidePhase.running -> "Recording"
                RidePhase.paused -> "Paused"
                RidePhase.interrupted -> "Needs attention"
                RidePhase.completed -> null
                RidePhase.failed -> "Failed"
            }
        return listOfNotNull(label, "Interrupted".takeIf { interrupted }).joinToString(" · ")
    }

    fun recordedOn(recordedBy: Device, useWatch: Boolean): String =
        when {
            recordedBy == Device.browser -> "Browser"
            useWatch -> "Watch + iPhone"
            recordedBy == Device.androidPhone -> "Android"
            else -> "iPhone"
        }

    fun rideCount(count: Int): String = "$count ${if (count == 1) "ride" else "rides"}"

    fun lapCount(laps: Int): String = "$laps ${if (laps == 1) "lap" else "laps"}"
}
