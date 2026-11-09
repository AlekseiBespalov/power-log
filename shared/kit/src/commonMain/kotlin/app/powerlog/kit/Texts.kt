package app.powerlog.kit

import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.StartRequest
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import app.powerlog.domain.recording.SourceKind
import app.powerlog.domain.recording.SourceState
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.PersistenceState
import app.powerlog.domain.settings.ViewId

public data class FailureText(public val message: String, public val nextStep: String) {
    public val text: String
        get() = listOf(message, nextStep).filter { it.isNotBlank() }.joinToString(" ")
}

public enum class TextPlatform {
    ios,
    android,
    web,
    watch,
}

public data class SourceContext(public val options: RideOptions, public val provider: HealthProviderKind?)

public enum class SourceTone {
    ready,
    neutral,
    warning,
}

public data class SourceText(public val label: String, public val tone: SourceTone)

public data class PermissionText(public val label: String, public val detail: String?)

public enum class ChartState {
    chartError,
    settingsError,
    awaitingData,
    syncing,
    noMeasurements,
    catchingUp,
}

public data class StatusText(public val label: String, public val detail: String)

public object Texts {
    public fun failure(code: FailureCode, detail: RefusalDetail?, platform: TextPlatform, sdkLevel: Int?): FailureText =
        FailureTexts.of(code, detail, platform, sdkLevel)

    public fun phaseChip(phase: RidePhase): String = RecordingTexts.phaseChip(phase)

    public fun historyPhase(phase: RidePhase, interrupted: Boolean): String =
        HistoryTexts.historyPhase(phase, interrupted)

    public fun source(kind: SourceKind, state: SourceState, context: SourceContext): SourceText =
        RecordingTexts.source(kind, state, context)

    public fun health(health: RideHealth): String = HealthTexts.health(health)

    public fun healthReason(health: RideHealth): String? = HealthTexts.reason(health)

    public fun healthComponents(results: List<ComponentResult>, provider: HealthProviderKind?): List<String> =
        HealthTexts.components(results, provider)

    public fun historyHealthLine(health: RideHealth, results: List<ComponentResult>): String? =
        HealthTexts.historyLine(health, results)

    public fun permission(
        need: PermissionNeed,
        action: PermissionAction,
        provider: HealthProviderKind?,
        platform: TextPlatform,
    ): PermissionText = RecordingTexts.permission(need, action, provider, platform)

    public fun healthSetup(options: RideOptions?, request: StartRequest, provider: HealthProviderKind?): String? =
        SettingsTexts.healthSetup(options, request, provider)

    public fun distanceSource(source: DistanceSelection, foregroundOnly: Boolean): String =
        SettingsTexts.distanceSource(source, foregroundOnly)

    public fun controllerSpeedNote(controllerSpeed: Boolean?): String? =
        ConnectionTexts.controllerSpeedNote(controllerSpeed)

    public fun connection(state: ConnectionState, display: ConnectionDisplay): String =
        ConnectionTexts.connection(state, display)

    public fun controllerFault(faultCode: Int): String = ConnectionTexts.controllerFault(faultCode)

    public fun provider(provider: HealthProviderKind): String = HealthTexts.provider(provider)

    public fun recordedOn(recordedBy: Device, useWatch: Boolean): String = HistoryTexts.recordedOn(recordedBy, useWatch)

    public fun setting(field: String): String = SettingsTexts.setting(field)

    public fun choice(field: String, value: String, platform: TextPlatform): String =
        SettingsTexts.choice(field, value, platform)

    public fun viewName(view: ViewId): String = ChartsTexts.viewName(view)

    public fun range(range: ChartRange): String = ChartsTexts.range(range)

    public fun chartStatus(state: ChartState): StatusText = ChartsTexts.status(state)

    public fun traceStatus(state: TraceState, events: Int, stopReason: TraceStopReason?): String =
        DiagnosticsTexts.traceStatus(state, events, stopReason)

    public fun storagePersistence(state: PersistenceState): String = SettingsTexts.storagePersistence(state)

    public fun rideCount(count: Int): String = HistoryTexts.rideCount(count)

    public fun lapCount(laps: Int): String = HistoryTexts.lapCount(laps)
}
