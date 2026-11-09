package app.powerlog.kit

import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.ViewId

internal object ChartsTexts {
    fun viewName(view: ViewId): String =
        when (view) {
            ViewId.ride -> "Ride"
            ViewId.battery -> "Battery"
            ViewId.temperature -> "Temperature"
        }

    fun range(range: ChartRange): String =
        when (range) {
            ChartRange.seconds30 -> "30 sec"
            ChartRange.seconds120 -> "2 min"
            ChartRange.seconds600 -> "10 min"
            ChartRange.all -> "Whole ride"
        }

    fun status(state: ChartState): StatusText =
        when (state) {
            ChartState.chartError -> StatusText("Chart error", "")
            ChartState.settingsError -> StatusText("Settings error", "")
            ChartState.awaitingData -> StatusText("Awaiting data", "Waiting for measurements.")
            ChartState.syncing -> StatusText("Syncing ride…", "Syncing remaining ride data.")
            ChartState.noMeasurements -> StatusText("No measurements", "No measurements are available.")
            ChartState.catchingUp -> StatusText("", "Charts catching up…")
        }
}
