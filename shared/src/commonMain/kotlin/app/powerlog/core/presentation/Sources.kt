package app.powerlog.core.presentation

import app.powerlog.core.StreamStatus
import kotlin.math.floor

data class SourcePresentationInput(
    val phase: String,
    val selected: FrozenRideOptions,
    val bikeReady: Boolean,
    val controllerStatus: String,
    val heartStatus: String,
    val gpsStatus: String,
    val gpsSource: String,
    val gpsAccuracy: Double?,
    val healthProvider: String?,
)

data class RideSourcePresentation(
    val bike: SourcePresentation,
    val heartRate: SourcePresentation,
    val gps: SourcePresentation,
)

object SourcePresentations {
    fun ownership(foreignOwner: String?): String? =
        if (foreignOwner == "anotherBrowserTab")
            "Another browser tab is recording this ride. Use that tab to control it."
        else null

    private fun samples(status: String, waiting: String, detail: String, unavailable: String): SourcePresentation =
        when (StreamStatus.normalized(status)) {
            "backfilled" -> SourcePresentation("Backfilled samples", detail, "neutral")
            "receiving" -> SourcePresentation("Receiving samples", detail, "ready")
            "stale" -> SourcePresentation("No recent samples", detail, "warning")
            "off" -> SourcePresentation("Not selected", detail, "neutral")
            "unavailable" -> SourcePresentation(unavailable, detail, "neutral")
            "paused" -> SourcePresentation("Paused", detail, "neutral")
            "weak" -> SourcePresentation("Weak signal", detail, "warning")
            else -> SourcePresentation(waiting, detail, "neutral")
        }

    fun project(input: SourcePresentationInput): RideSourcePresentation =
        project(
            input.phase,
            input.selected.indoor,
            input.selected.useWatch,
            input.selected.saveToHealth,
            input.selected.recordGPS,
            input.bikeReady,
            input.controllerStatus,
            input.heartStatus,
            input.gpsStatus,
            input.gpsSource,
            input.gpsAccuracy,
            input.healthProvider,
        )

    fun project(
        phase: String,
        indoor: Boolean,
        useWatch: Boolean,
        saveToHealth: Boolean,
        recordGPS: Boolean,
        bikeReady: Boolean,
        controllerStatus: String,
        heartStatus: String,
        gpsStatus: String,
        gpsSource: String,
        gpsAccuracy: Double?,
        healthProvider: String?,
        controllerDisplay: String? = null,
    ): RideSourcePresentation {
        val active = StreamStatus.active(phase)
        val source = if (if (active) gpsSource == "watch" else useWatch) "Watch" else "Phone"
        val heartSource = if (useWatch) "Apple Watch" else "Compatible heart-rate source on phone"
        val gpsOff = SourcePresentation(if (indoor) "Off for indoor ride" else "Off", "No route recording", "neutral")
        if (!active)
            return RideSourcePresentation(
                if (bikeReady)
                    SourcePresentation("Bike connected", "Workout samples start when you start the ride.", "ready")
                else SourcePresentation("Not connected", "Bike measurements are optional.", "neutral"),
                if (useWatch) SourcePresentation("Starts with workout", heartSource, "neutral")
                else SourcePresentation("Not selected", "Heart rate is optional.", "neutral"),
                if (recordGPS) SourcePresentation("Starts with workout", source, "neutral") else gpsOff,
            )
        val bike =
            samples(
                if (controllerDisplay in setOf("live", "held")) "receiving" else controllerStatus,
                "Waiting for workout samples",
                "Rider power, cadence and motor readings",
                "Bike unavailable",
            )
        var heart =
            if (heartStatus != "unavailable" && !useWatch && !saveToHealth)
                SourcePresentation("Not selected", "Heart rate is optional.", "neutral")
            else samples(heartStatus, "Waiting for heart rate", heartSource, "Not available on this device")
        if (healthProvider == "healthConnect" && saveToHealth)
            heart =
                when (heartStatus) {
                    "denied",
                    "restricted" ->
                        SourcePresentation(
                            if (heartStatus == "denied") "Heart-rate access denied" else "Heart-rate access restricted",
                            "Health Connect readback",
                            "warning",
                        )
                    "receiving",
                    "backfilled" -> SourcePresentation("Backfilled heart rate", "Health Connect readback", "neutral")
                    "waiting",
                    "notDetermined" ->
                        SourcePresentation(
                            "Waiting for backfill",
                            "Heart rate can appear after Health Connect readback.",
                            "neutral",
                        )
                    else -> heart
                }
        if (!recordGPS) return RideSourcePresentation(bike, heart, gpsOff)
        val status = StreamStatus.normalized(gpsStatus)
        var gps =
            when (status) {
                "backfilled" -> SourcePresentation("Backfilled GPS", source, "neutral")
                "receiving" -> SourcePresentation("Receiving GPS", source, "ready")
                "weak" -> SourcePresentation("Weak GPS signal", source, "warning")
                "stale" -> SourcePresentation("No recent GPS", source, "warning")
                "paused" -> SourcePresentation("GPS paused", source, "neutral")
                "off" -> SourcePresentation("GPS off", source, "neutral")
                "denied",
                "restricted",
                "notDetermined" ->
                    SourcePresentation(PresentationFormatting.locationPermission(status), source, "warning")
                "unavailable" -> SourcePresentation("GPS unavailable", source, "warning")
                else -> SourcePresentation("Waiting for GPS", source, "neutral")
            }
        if (status == "receiving" && gpsAccuracy != null && gpsAccuracy.isFinite() && gpsAccuracy >= 0)
            gps = gps.copy(detail = gps.detail + " · ±${floor(gpsAccuracy + 0.5).toLong()} m")
        return RideSourcePresentation(bike, heart, gps)
    }
}
