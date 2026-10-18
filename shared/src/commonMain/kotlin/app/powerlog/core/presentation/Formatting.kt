package app.powerlog.core.presentation

import app.powerlog.core.analytics.DistancePolicy
import app.powerlog.core.health.MeasurementSource
import app.powerlog.core.health.ProviderNames
import app.powerlog.core.telemetry.MetricCatalog
import kotlin.math.floor

data class SourcePresentation(val label: String, val detail: String, val tone: String)

data class DistancePresentation(
    val text: String,
    val unit: String,
    val label: String,
    val caption: String,
    val partial: Boolean,
    val estimated: Boolean,
)

object PresentationFormatting {
    fun duration(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite() || seconds > Long.MAX_VALUE.toDouble()) return "—"
        val s = maxOf(0.0, floor(seconds)).toLong()
        val hours = s / 3600
        return (if (hours > 0) "$hours:" else "") +
            (s / 60 % 60).toString().padStart(2, '0') +
            ":" +
            (s % 60).toString().padStart(2, '0')
    }

    fun provider(provider: String?): String = ProviderNames.fromWire(provider)

    fun physiology(label: String, provider: String?): String =
        if (provider == "healthConnect") "$label (backfilled)" else label

    @Throws(IllegalArgumentException::class)
    fun distanceOptionLabel(source: String, foregroundOnly: Boolean = false): String =
        when (source) {
            "auto" -> "Auto"
            "gps:watch" -> "GPS · Watch"
            "gps:phone" -> if (foregroundOnly) "GPS · Browser" else "GPS · Phone"
            "controller" -> "Controller estimate"
            else -> throw IllegalArgumentException("Invalid distance source")
        }

    @Throws(IllegalArgumentException::class)
    fun distance(
        meters: Double?,
        source: String?,
        partial: Boolean = false,
        estimated: Boolean = false,
        descriptors: List<MeasurementSource> = emptyList(),
    ): DistancePresentation {
        require(source == null || source != "auto" && DistancePolicy.validSelection(source))
        val value = meters?.takeIf { it.isFinite() && it >= 0 }
        val label = source?.let { DistancePolicy.label(it, descriptors) } ?: "Unavailable"
        val isEstimated = source == "controller" || estimated
        val caption = distanceCaption(label, source, partial, isEstimated)
        return DistancePresentation(
            MetricCatalog.format("distanceMeters", value),
            MetricCatalog.metric("distanceMeters").unit,
            label,
            caption,
            partial,
            isEstimated,
        )
    }

    fun distanceCaption(label: String, source: String?, partial: Boolean, estimated: Boolean): String =
        label + (if (partial) " · Partial" else "") + (if (estimated && source != "controller") " · Estimated" else "")

    fun locationPermission(status: String): String =
        when (status) {
            "authorizedAlways" -> "Location allowed"
            "authorizedWhenInUse" -> "Location allowed while using the app"
            "denied" -> "Location permission denied"
            "restricted" -> "Location access restricted"
            "notDetermined" -> "Location permission needed"
            else -> "Location permission status unavailable"
        }
}
