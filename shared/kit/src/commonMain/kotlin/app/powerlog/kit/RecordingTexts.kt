package app.powerlog.kit

import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import app.powerlog.domain.recording.SourceKind
import app.powerlog.domain.recording.SourceState

internal object RecordingTexts {
    private const val CHECK_PERMISSIONS = "Check permissions"
    private const val LOCATION_SETTINGS = "Location settings"

    fun phaseChip(phase: RidePhase): String =
        when (phase) {
            RidePhase.preparing -> "Starting"
            RidePhase.running -> "Running"
            RidePhase.paused -> "Paused"
            RidePhase.interrupted -> "Needs attention"
            RidePhase.completed -> "Saved"
            RidePhase.failed -> "Failed"
        }

    fun source(kind: SourceKind, state: SourceState, context: SourceContext): SourceText =
        when (kind) {
            SourceKind.controller -> samples(state, "Waiting for workout samples", "Bike unavailable")
            SourceKind.heartRate -> heartRate(state, context)
            SourceKind.gps -> gps(state, context)
        }

    private fun samples(state: SourceState, waiting: String, unavailable: String): SourceText =
        when (state) {
            SourceState.backfilled -> SourceText("Backfilled samples", SourceTone.neutral)
            SourceState.receiving -> SourceText("Receiving samples", SourceTone.ready)
            SourceState.stale -> SourceText("No recent samples", SourceTone.warning)
            SourceState.off -> SourceText("Not selected", SourceTone.neutral)
            SourceState.unavailable -> SourceText(unavailable, SourceTone.neutral)
            SourceState.paused -> SourceText("Paused", SourceTone.neutral)
            SourceState.weak -> SourceText("Weak signal", SourceTone.warning)
            SourceState.waiting,
            SourceState.denied -> SourceText(waiting, SourceTone.neutral)
        }

    private fun heartRate(state: SourceState, context: SourceContext): SourceText {
        val options = context.options
        if (context.provider == HealthProviderKind.healthConnect && options.saveToHealth)
            when (state) {
                SourceState.denied -> return SourceText("Heart-rate access denied", SourceTone.warning)
                SourceState.receiving,
                SourceState.backfilled -> return SourceText("Heart rate from Health Connect", SourceTone.neutral)
                SourceState.waiting -> return SourceText("Heart rate comes later", SourceTone.neutral)
                else -> {}
            }
        if (state != SourceState.unavailable && !options.useWatch && !options.saveToHealth)
            return SourceText("Not selected", SourceTone.neutral)
        return samples(state, "Waiting for heart rate", "Not available on this device")
    }

    private fun gps(state: SourceState, context: SourceContext): SourceText {
        if (!context.options.recordGps)
            return SourceText(if (context.options.indoor) "Off for indoor ride" else "Off", SourceTone.neutral)
        return when (state) {
            SourceState.backfilled -> SourceText("Backfilled GPS", SourceTone.neutral)
            SourceState.receiving -> SourceText("Receiving GPS", SourceTone.ready)
            SourceState.weak -> SourceText("Weak GPS signal", SourceTone.warning)
            SourceState.stale -> SourceText("No recent GPS", SourceTone.warning)
            SourceState.paused -> SourceText("GPS paused", SourceTone.neutral)
            SourceState.off -> SourceText("GPS off", SourceTone.neutral)
            SourceState.denied -> SourceText("Location permission denied", SourceTone.warning)
            SourceState.unavailable -> SourceText("GPS unavailable", SourceTone.warning)
            SourceState.waiting -> SourceText("Waiting for GPS", SourceTone.neutral)
        }
    }

    fun permission(
        need: PermissionNeed,
        action: PermissionAction,
        provider: HealthProviderKind?,
        platform: TextPlatform,
    ): PermissionText {
        val health = need == PermissionNeed.health
        return when (action) {
            PermissionAction.request ->
                if (health)
                    PermissionText("Set up permissions", "Review the health permissions requested by Power Log.")
                else PermissionText("Allow location", "Your phone needs location access to record GPS measurements.")
            PermissionAction.healthHelp -> healthAccess(provider)
            PermissionAction.openAppSettings ->
                when (need) {
                    PermissionNeed.health -> healthAccess(provider)
                    PermissionNeed.location ->
                        PermissionText(LOCATION_SETTINGS, "Allow location access for Power Log in Settings.")
                    PermissionNeed.locationRestricted ->
                        PermissionText(LOCATION_SETTINGS, "Location access is restricted by device settings.")
                    PermissionNeed.locationServices ->
                        PermissionText(LOCATION_SETTINGS, "Turn on Location Services to record the phone route.")
                    PermissionNeed.preciseLocation ->
                        PermissionText("Precise location", "Enable Precise Location for a useful cycling route.")
                }
            PermissionAction.check ->
                if (platform == TextPlatform.web && !health)
                    PermissionText(
                        CHECK_PERMISSIONS,
                        "Allow location in this site's browser settings, then check permissions.",
                    )
                else PermissionText(CHECK_PERMISSIONS, "Current permission readiness could not be determined.")
            PermissionAction.unavailable ->
                if (health) PermissionText("Health unavailable", "Health saving is unavailable on this device.")
                else PermissionText("GPS unavailable", "Your phone needs location access to record GPS measurements.")
        }
    }

    private fun healthAccess(provider: HealthProviderKind?): PermissionText =
        PermissionText(
            "Review Health access",
            "Some write access is denied. Review Power Log’s permissions in " +
                "${HealthTexts.provider(provider ?: HealthProviderKind.appleHealth)}.",
        )
}
