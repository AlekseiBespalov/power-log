package app.powerlog.kit

import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.PersistenceState
import app.powerlog.domain.settings.ViewId

internal object SettingsTexts {
    private const val MONITORING_VIEW = "Monitoring view"

    private val titles =
        mapOf(
            "sampleRate" to "Bike sample rate",
            "distance" to "Distance source",
            "battery" to "Battery consumption source",
            "indoor" to "Ride type",
            "useWatch" to "Apple Watch",
            "saveToHealth" to "Save to Health",
            "recordGps" to "GPS route",
            "syncBeeps" to "Video sync beeps",
            "units" to "Units",
            "temperature" to "Temperature",
            "fitPower" to "FIT power source",
            "activeView" to MONITORING_VIEW,
            "historyView" to MONITORING_VIEW,
            "historyRange" to "Chart time range",
        )

    private val choices =
        mapOf(
            "sampleRate" to mapOf("2" to "2 Hz", "4" to "4 Hz", "8" to "8 Hz"),
            "battery" to mapOf("controller" to "Controller", "calculated" to "Calculated"),
            "indoor" to mapOf("false" to "Outdoor", "true" to "Indoor"),
            "units" to mapOf("metric" to "Metric", "imperial" to "Imperial"),
            "temperature" to mapOf("celsius" to "°C", "fahrenheit" to "°F"),
            "fitPower" to mapOf("rider" to "Rider power", "battery" to "Battery input power"),
        )

    private val distanceSelections =
        mapOf(
            "auto" to DistanceSelection.auto,
            "controller" to DistanceSelection.controller,
            "gps:phone" to DistanceSelection.phoneGps,
        )

    fun setting(field: String): String = titles[field] ?: field

    fun choice(field: String, value: String, platform: TextPlatform): String =
        when (field) {
            "distance" -> distanceSelections[value]?.let { distanceSource(it, platform == TextPlatform.web) } ?: value
            "activeView",
            "historyView" -> ViewId.entries.firstOrNull { it.name == value }?.let(ChartsTexts::viewName) ?: value
            "historyRange" -> ChartRange.entries.firstOrNull { it.name == value }?.let(ChartsTexts::range) ?: value
            else -> choices[field]?.get(value) ?: value
        }

    fun distanceSource(source: DistanceSelection, foregroundOnly: Boolean): String =
        when (source) {
            DistanceSelection.auto -> "Auto"
            DistanceSelection.phoneGps -> if (foregroundOnly) "GPS · Browser" else "GPS · Phone"
            DistanceSelection.controller -> "Controller estimate"
        }

    fun healthSetup(options: RideOptions?, request: StartRequest, provider: HealthProviderKind?): String? {
        if (options == null || !request.saveToHealth || options.saveToHealth) return null
        val recorder = if (options.useWatch) "Watch" else "Phone"
        val name = provider?.let(HealthTexts::provider) ?: "Health"
        return "$recorder-recorded rides cannot be saved to $name on this device. This ride will stay in Power Log."
    }

    fun storagePersistence(state: PersistenceState): String =
        when (state) {
            PersistenceState.persisted -> "Protected from automatic browser cleanup"
            PersistenceState.notPersisted -> "The browser may clear saved rides when storage runs low"
            PersistenceState.unsupported -> "Protection status unavailable in this browser"
        }
}
