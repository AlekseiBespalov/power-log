@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.MetricCatalog
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

@JsExport
fun presentationFormatPoint(id: String, value: Double?, exactValue: String?, speedUnit: String): String =
    MetricCatalog.formatPoint(id, value, exactValue, speedUnit)

@JsExport
fun presentationFormatDifference(
    id: String,
    a: Double?,
    b: Double?,
    aExact: String?,
    bExact: String?,
    speedUnit: String,
): String = MetricCatalog.formatDifference(id, a, b, aExact, bExact, speedUnit)

@JsExport fun presentationMetricAllowsMean(id: String): Boolean = MetricCatalog.allowsMean(id)

private fun presentationLong(text: String): Long = text.toLong().also { require(it.toString() == text) }

private fun presentationRange(text: String): MonitorRange =
    when (text) {
        "30" -> MonitorRange.seconds30
        "120" -> MonitorRange.seconds120
        "600" -> MonitorRange.seconds600
        "all" -> MonitorRange.all
        else -> throw IllegalArgumentException("Invalid monitor range")
    }

private fun MonitorRange.text(): String =
    when (this) {
        MonitorRange.seconds30 -> "30"
        MonitorRange.seconds120 -> "120"
        MonitorRange.seconds600 -> "600"
        MonitorRange.all -> "all"
    }

@JsExport
fun presentationTransportDisplay(connection: String, acquiredAt: Double?, now: Double): String =
    StreamStatus.connectionDisplay(connection, acquiredAt?.let { now - it })

@JsExport
fun presentationReadingAvailable(acquiredAt: Double?, now: Double?, cutoff: Double): Boolean =
    ReadingFreshness.available(acquiredAt, now, cutoff)

@JsExport
class ControllerPreviewResult internal constructor(private val core: ControllerPreviewView) {
    val display: String
        get() = core.display

    val faultCode: Double?
        get() = core.faultCode

    val nextDeadline: Double?
        get() = core.nextDeadline
}

@JsExport
class ControllerPreviewPresentation {
    private val core = ControllerPreview()

    fun clear() = core.clear()

    fun observe(
        connection: String,
        sequence: Double,
        acquiredAt: Double,
        faultCode: Double,
        mapped: Double?,
        now: Double,
    ): Boolean {
        if (!sequence.isFinite() || sequence < 0 || sequence > 9007199254740991.0 || sequence % 1.0 != 0.0) return false
        return core.observe(connection, sequence.toLong(), acquiredAt, faultCode, mapped, now)
    }

    val acquiredAt: Double?
        get() = core.observation?.acquiredAt

    fun view(connection: String, mapped: Double?, now: Double): ControllerPreviewResult =
        ControllerPreviewResult(core.view(connection, mapped, now))
}

@JsExport
fun presentationConnectionLabel(connection: String, display: String): String =
    StreamStatus.connectionLabel(connection, display)

@JsExport fun presentationStreamStatus(status: String?): String = StreamStatus.normalized(status)

@JsExport
fun presentationMetric(id: String, speedUnit: String): CoreMetricDeclaration =
    CoreMetricDeclaration(MetricCatalog.presentationMetric(id, speedUnit))

@JsExport
fun presentationFormatMetric(id: String, value: Double?, speedUnit: String): String =
    MetricCatalog.format(id, value, speedUnit)

@JsExport fun presentationDuration(seconds: Double?): String = PresentationFormatting.duration(seconds)

@JsExport fun presentationProvider(provider: String?): String = PresentationFormatting.provider(provider)

@JsExport
fun presentationPhysiology(label: String, provider: String?): String =
    PresentationFormatting.physiology(label, provider)

@JsExport fun presentationLocationPermission(status: String): String = PresentationFormatting.locationPermission(status)

@JsExport
fun presentationDistanceCaption(label: String, source: String?, partial: Boolean, estimated: Boolean): String =
    PresentationFormatting.distanceCaption(label, source, partial, estimated)

@JsExport
fun presentationDistanceOptionLabel(source: String, foregroundOnly: Boolean): String =
    PresentationFormatting.distanceOptionLabel(source, foregroundOnly)

@JsExport
class PresentationCapabilities(
    phoneWorkout: Boolean,
    watchWorkout: Boolean,
    phoneHealth: Boolean,
    watchHealth: Boolean,
    gps: Boolean,
    healthProvider: String?,
    foregroundOnly: Boolean,
) {
    internal val core =
        RideCapabilities(phoneWorkout, watchWorkout, phoneHealth, watchHealth, gps, healthProvider, foregroundOnly)
    val phoneWorkout: Boolean
        get() = core.phoneWorkout

    val watchWorkout: Boolean
        get() = core.watchWorkout

    val phoneHealth: Boolean
        get() = core.phoneHealth

    val watchHealth: Boolean
        get() = core.watchHealth

    val gps: Boolean
        get() = core.gps

    val healthProvider: String?
        get() = core.healthProvider

    val foregroundOnly: Boolean
        get() = core.foregroundOnly
}

@JsExport
class PresentationOptions(
    indoor: Boolean,
    useWatch: Boolean?,
    saveToHealth: Boolean?,
    recordGPS: Boolean?,
    sampleHz: Double?,
    distanceSource: String = "auto",
    batteryConsumptionSource: String = "controller",
) {
    init {
        require(jsTypeOf(indoor) == "boolean") { "indoor must be a boolean" }
        for ((field, value) in
            listOf("useWatch" to useWatch, "saveToHealth" to saveToHealth, "recordGPS" to recordGPS)) require(
            value == null || jsTypeOf(value) == "boolean"
        ) {
            "$field must be a boolean"
        }
        require(sampleHz == null || jsTypeOf(sampleHz) == "number") { "Sample rate must be numeric" }
    }

    internal val core =
        RequestedRideOptions(
            indoor,
            useWatch,
            saveToHealth,
            recordGPS,
            sampleHz,
            distanceSource,
            batteryConsumptionSource,
        )
    val indoor: Boolean
        get() = core.indoor

    val useWatch: Boolean?
        get() = core.useWatch

    val saveToHealth: Boolean?
        get() = core.saveToHealth

    val recordGPS: Boolean?
        get() = core.recordGPS

    val sampleHz: Double?
        get() = core.sampleHz

    val batteryConsumptionSource: String
        get() = core.batteryConsumptionSource
}

private fun RequestedRideOptions.js() =
    PresentationOptions(indoor, useWatch, saveToHealth, recordGPS, sampleHz, distanceSource, batteryConsumptionSource)

@JsExport
class PresentationFrozenOptions internal constructor(internal val core: FrozenRideOptions) {
    val indoor: Boolean
        get() = core.indoor

    val useWatch: Boolean
        get() = core.useWatch

    val saveToHealth: Boolean
        get() = core.saveToHealth

    val recordGPS: Boolean
        get() = core.recordGPS

    val batteryConsumptionSource: String
        get() = core.batteryConsumptionSource

    val sampleHz: Int
        get() = core.sampleHz

    val recordingOwner: String
        get() = core.recordingOwner

    val healthWriter: String?
        get() = core.healthWriter

    val distanceSource: String
        get() = core.distanceSource
}

@JsExport
fun presentationResolveOptions(
    options: PresentationOptions,
    capabilities: PresentationCapabilities,
): PresentationFrozenOptions = PresentationFrozenOptions(RideOptions.resolve(options.core, capabilities.core))

@JsExport fun presentationSampleRates(): IntArray = SampleRates.admitted.toIntArray()

@JsExport
class PresentationStartContext(
    surface: String,
    hasSession: Boolean,
    endingOwner: Boolean,
    recoveredForeignOwner: Boolean,
    unresolvedOwner: Boolean,
    finishing: Boolean,
    busy: Boolean,
    recovering: Boolean,
    deletionProbeInFlight: Boolean,
) {
    internal val core =
        RideStartContext(
            StartSurface.valueOf(surface),
            hasSession,
            endingOwner,
            recoveredForeignOwner,
            unresolvedOwner,
            finishing,
            busy,
            recovering,
            deletionProbeInFlight,
        )
}

@JsExport
class PresentationReadiness internal constructor(private val core: StartReadiness) {
    val available: Boolean
        get() = core.available

    val error: String?
        get() = core.error
}

@JsExport
fun presentationStartReadiness(
    options: PresentationFrozenOptions,
    capabilities: PresentationCapabilities,
    bikeReady: Boolean,
    watchInstalled: Boolean,
    phase: String?,
    startContext: PresentationStartContext,
    error: String?,
): PresentationReadiness =
    PresentationReadiness(
        RideOptions.readiness(
            options.core,
            capabilities.core,
            bikeReady,
            watchInstalled,
            phase,
            startContext.core,
            error,
        )
    )

@JsExport
class PresentationPermissions(
    healthAvailable: Boolean,
    provider: String?,
    requiredWrites: Array<String>?,
    writeTypes: Array<String>,
    writeAuthorization: Array<String>,
    location: String,
    servicesEnabled: Boolean,
    accuracy: String,
) {
    internal val core: PermissionStatus

    init {
        require(writeTypes.size == writeAuthorization.size && writeTypes.distinct().size == writeTypes.size)
        core =
            PermissionStatus(
                healthAvailable,
                provider,
                requiredWrites?.toList(),
                writeTypes.zip(writeAuthorization).toMap(),
                location,
                servicesEnabled,
                accuracy,
            )
    }
}

@JsExport
class PresentationPermissionAction internal constructor(private val core: PermissionAction) {
    val action: String
        get() = core.action

    val label: String
        get() = core.label

    val detail: String
        get() = core.detail

    val settingsTarget: String?
        get() = core.settingsTarget
}

@JsExport
fun presentationPermissionAction(
    status: PresentationPermissions?,
    options: PresentationOptions,
): PresentationPermissionAction = PresentationPermissionAction(SetupPresentation.permission(status?.core, options.core))

@JsExport
class PresentationSource internal constructor(private val core: SourcePresentation) {
    val label: String
        get() = core.label

    val detail: String
        get() = core.detail

    val tone: String
        get() = core.tone
}

@JsExport
class PresentationSources internal constructor(private val core: RideSourcePresentation) {
    val bike: PresentationSource
        get() = PresentationSource(core.bike)

    val heartRate: PresentationSource
        get() = PresentationSource(core.heartRate)

    val gps: PresentationSource
        get() = PresentationSource(core.gps)
}

@JsExport
fun presentationSources(
    phase: String,
    selected: PresentationFrozenOptions,
    bikeReady: Boolean,
    controllerStatus: String,
    heartStatus: String,
    gpsStatus: String,
    gpsSource: String,
    gpsAccuracy: Double?,
    healthProvider: String?,
): PresentationSources =
    PresentationSources(
        SourcePresentations.project(
            SourcePresentationInput(
                phase,
                selected.core,
                bikeReady,
                controllerStatus,
                heartStatus,
                gpsStatus,
                gpsSource,
                gpsAccuracy,
                healthProvider,
            )
        )
    )

@JsExport
fun presentationStateSources(
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
    controllerDisplay: String?,
): PresentationSources =
    PresentationSources(
        SourcePresentations.project(
            phase,
            indoor,
            useWatch,
            saveToHealth,
            recordGPS,
            bikeReady,
            controllerStatus,
            heartStatus,
            gpsStatus,
            gpsSource,
            gpsAccuracy,
            healthProvider,
            controllerDisplay,
        )
    )

@JsExport
fun presentationHealthSetup(options: PresentationOptions, capabilities: PresentationCapabilities): PresentationSource =
    PresentationSource(SetupPresentation.health(options.core, capabilities.core))

@JsExport fun presentationOwnership(foreignOwner: String?): String? = SourcePresentations.ownership(foreignOwner)

@JsExport
class PresentationActionResult internal constructor(private val core: ActionDecision) {
    val admitted: Boolean
        get() = core.admitted

    val error: String?
        get() = core.error

    val haptic: String
        get() = core.haptic

    val busy: Boolean
        get() = core.next.pending != null

    val pendingCommandId: String?
        get() = core.next.pending?.commandId

    val pendingRideId: String?
        get() = core.next.pending?.rideId
}

@JsExport
class RideActionPresentation {
    private var state = ActionPresentationState()

    fun admit(
        commandId: String,
        rideId: String?,
        action: String,
        phase: String,
        currentRideId: String?,
        foreignOwner: Boolean,
        confirmedRideId: String?,
        nativePendingAction: String?,
        startContext: PresentationStartContext,
        surface: String = "rider",
        adapterBusy: Boolean = false,
    ): PresentationActionResult {
        val result =
            RideActions.admit(
                state,
                ActionRequest(
                    commandId,
                    rideId,
                    RideAction.valueOf(action),
                    confirmedRideId?.let(::FinishConfirmation),
                ),
                phase,
                currentRideId,
                foreignOwner,
                nativePendingAction,
                startContext.core,
                RideActionSurface.valueOf(surface),
                adapterBusy,
            )
        state = result.next
        return PresentationActionResult(result)
    }

    fun availability(
        rideId: String?,
        action: String,
        phase: String,
        currentRideId: String?,
        foreignOwner: Boolean,
        confirmedRideId: String?,
        nativePendingAction: String?,
        startContext: PresentationStartContext,
        surface: String,
        adapterBusy: Boolean,
    ): PresentationActionResult =
        PresentationActionResult(
            RideActions.admit(
                state,
                ActionRequest(
                    "availability",
                    rideId,
                    RideAction.valueOf(action),
                    confirmedRideId?.let(::FinishConfirmation),
                ),
                phase,
                currentRideId,
                foreignOwner,
                nativePendingAction,
                startContext.core,
                RideActionSurface.valueOf(surface),
                adapterBusy,
            )
        )

    fun complete(commandId: String, rideId: String?, error: String?): PresentationActionResult {
        val result = RideActions.complete(state, commandId, rideId, error)
        state = result.next
        return PresentationActionResult(result)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    val busy: Boolean
        get() = state.pending != null

    val error: String?
        get() = state.error
}

@JsExport
class PresentationExternalResult internal constructor(private val core: ExternalControlDecision) {
    val outcome: String
        get() = core.outcome

    val command: String?
        get() = core.command

    val error: String?
        get() = core.error
}

@JsExport
fun presentationExternalControl(
    currentRideId: String?,
    currentToken: String,
    phase: String,
    pendingAction: String?,
    rideId: String,
    token: String,
    expectedPhase: String,
    action: String,
    confirmed: Boolean,
): PresentationExternalResult =
    PresentationExternalResult(
        RideActions.external(
            ExternalControlContext(currentRideId, currentToken, phase, pendingAction),
            ExternalControlRequest(rideId, token, expectedPhase, action),
            confirmed,
        )
    )

@JsExport
class PresentationWaitResult internal constructor(private val core: WaitPresentation) {
    val outcome: String
        get() = core.outcome

    val pending: Boolean
        get() = core.pending

    val error: String?
        get() = core.error
}

@JsExport
fun presentationWait(kind: String, beganAtUs: String, nowUs: String): PresentationWaitResult =
    PresentationWaitResult(
        PresentationTimeouts.evaluate(
            PresentationWait.valueOf(kind),
            presentationLong(beganAtUs),
            presentationLong(nowUs),
        )
    )

@JsExport
fun presentationWaitDeadline(kind: String, beganAtUs: String): String =
    PresentationTimeouts.deadline(PresentationWait.valueOf(kind), presentationLong(beganAtUs)).toString()

@JsExport
class PresentationPreset(numbers: Array<String>, charts: Array<String>, range: String, webColumns: Int) {
    internal val core = MonitorPreset(numbers.toList(), charts.toList(), presentationRange(range), webColumns)
    val numbers: Array<String>
        get() = core.numbers.toTypedArray()

    val charts: Array<String>
        get() = core.charts.toTypedArray()

    val range: String
        get() = core.range.text()

    val webColumns: Int
        get() = core.webColumns
}

private fun MonitorPreset.js() =
    PresentationPreset(numbers.toTypedArray(), charts.toTypedArray(), range.text(), webColumns)

@JsExport
class PresentationPreferences internal constructor(internal val core: MonitorPreferences) {
    val storedJson: String
        get() = Preferences.encode(core)

    val activeView: String
        get() = core.activeView.name

    val historyView: String?
        get() = core.historyView?.name

    val historyRange: String
        get() = core.historyRange.text()

    val speedUnit: String
        get() = core.speedUnit

    val distanceSource: String
        get() = core.distanceSource

    val batteryConsumptionSource: String
        get() = core.batteryConsumptionSource

    val fitPowerSource: String
        get() = core.fitPowerSource

    val sampleHz: Int
        get() = core.sampleHz

    val options: PresentationOptions
        get() = core.workoutOptions.js()

    val startOptions: PresentationOptions
        get() = core.startOptions().js()

    fun view(id: String): PresentationPreset = core.views.getValue(MonitorPresetId.valueOf(id)).js()

    fun withPreset(id: String, preset: PresentationPreset): PresentationPreferences =
        PresentationPreferences(core.withPreset(MonitorPresetId.valueOf(id), preset.core))

    fun withSelections(live: String, history: String?, range: String): PresentationPreferences =
        PresentationPreferences(
            core.withSelection(
                MonitorPresetId.valueOf(live),
                history?.let { MonitorPresetId.valueOf(it) },
                presentationRange(range),
            )
        )

    fun withOptions(options: PresentationOptions, sampleHz: Int): PresentationPreferences =
        PresentationPreferences(core.withOptions(options.core, sampleHz))

    fun withBatteryConsumptionSource(source: String): PresentationPreferences =
        PresentationPreferences(core.withBatteryConsumptionSource(source))

    fun withFitPowerSource(source: String): PresentationPreferences =
        PresentationPreferences(core.withFitPowerSource(source))

    fun withDisplay(speedUnit: String, distanceSource: String): PresentationPreferences =
        PresentationPreferences(
            MonitorPreferences(
                core.activeView,
                core.historyView,
                core.historyRange,
                speedUnit,
                distanceSource,
                core.sampleHz,
                core.workoutOptions,
                core.views,
                core.batteryConsumptionSource,
                core.fitPowerSource,
            )
        )
}

@JsExport
fun presentationDefaultPreferences(): PresentationPreferences = PresentationPreferences(Preferences.defaults())

@JsExport fun presentationPresetLabel(id: String): String = Preferences.presetLabel(MonitorPresetId.valueOf(id))

@JsExport
fun presentationParsePreferences(text: String): PresentationPreferences =
    PresentationPreferences(Preferences.decode(text))

@JsExport
class PresentationPreferenceLoad internal constructor(internal val core: PreferenceLoadDecision) {
    val preferences: PresentationPreferences
        get() = PresentationPreferences(core.preferences)

    val error: String?
        get() = core.error

    val writable: Boolean
        get() = core.writable

    val shouldSave: Boolean
        get() = core.shouldSave

    fun change(replacement: PresentationPreferences): PresentationPreferenceLoad =
        PresentationPreferenceLoad(Preferences.change(core, replacement.core))
}

@JsExport
fun presentationLoadPreferences(
    previous: PresentationPreferences,
    storedJson: String?,
    loadError: String?,
): PresentationPreferenceLoad = PresentationPreferenceLoad(Preferences.load(previous.core, storedJson, loadError))

@JsExport
fun presentationRecoveryStopOffered(phase: String, pendingAction: String?): Boolean =
    RideActions.recoveryStopOffered(phase, pendingAction)

@JsExport
class PresentationFailure internal constructor(private val core: RideFailure) {
    val code: String
        get() = core.name

    val message: String
        get() = core.message

    val nextStep: String
        get() = core.nextStep

    val text: String
        get() = core.text
}

@JsExport fun presentationFailure(code: String?): PresentationFailure = PresentationFailure(Failures.present(code))

@JsExport fun presentationFailureCodes(): Array<String> = RideFailure.entries.map { it.name }.toTypedArray()
