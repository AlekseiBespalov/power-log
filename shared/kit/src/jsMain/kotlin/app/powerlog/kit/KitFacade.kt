@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.kit

import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.ComponentResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
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
import kotlinx.serialization.KSerializer

@JsExport
public class KitFailureText internal constructor(text: FailureText) {
    public val message: String = text.message
    public val nextStep: String = text.nextStep
    public val text: String = text.text
}

@JsExport
public class KitSourceText internal constructor(text: SourceText) {
    public val label: String = text.label
    public val tone: String = text.tone.name
}

@JsExport
public class KitPermissionText internal constructor(text: PermissionText) {
    public val label: String = text.label
    public val detail: String? = text.detail
}

@JsExport
public class KitStatusText internal constructor(text: StatusText) {
    public val label: String = text.label
    public val detail: String = text.detail
}

@JsExport
public class KitDisplayUnit internal constructor(unit: DisplayUnit) {
    public val symbol: String = unit.symbol
    public val decimals: Int = unit.decimals
    public val factor: Double = unit.factor
    public val offset: Double = unit.offset
}

@JsExport
public class KitAppearance internal constructor(metric: MetricId, appearance: Appearance) {
    public val id: String = metric.serialName()
    public val label: String = appearance.label
    public val shortLabel: String = appearance.shortLabel
    public val color: String = appearance.color
    public val description: String? = appearance.description
    public val group: String = appearance.group.name
    public val step: Boolean = appearance.step
    public val zero: Boolean = appearance.zero
    public val selectable: Boolean = appearance.selectable
    public val semantics: String = appearance.semantics.name
    public val allowsMean: Boolean = appearance.allowsMean
    public val origin: String = appearance.origin.name
    public val tailUs: Double = MetricCatalog.of(metric).tailUs.toDouble()
}

@JsExport
public fun textsFailure(code: String, detail: String?, platform: String, sdkLevel: Int?): KitFailureText =
    KitFailureText(
        Texts.failure(
            FailureCode.serializer().named(code) ?: FailureCode.unknown,
            detail?.let(RefusalDetail.serializer()::named),
            platform.textPlatform(),
            sdkLevel,
        )
    )

@JsExport
public fun textsPhaseChip(phase: String): String = RidePhase.serializer().named(phase)?.let(Texts::phaseChip) ?: phase

@JsExport
public fun textsHistoryPhase(phase: String, interrupted: Boolean): String =
    RidePhase.serializer().named(phase)?.let { Texts.historyPhase(it, interrupted) } ?: phase

@JsExport
public fun textsSource(kind: String, state: String, options: dynamic, provider: String?): KitSourceText? {
    val sourceKind = SourceKind.serializer().named(kind) ?: return null
    val sourceState = SourceState.serializer().named(state) ?: return null
    val rideOptions = rideOptions(options) ?: return null
    return KitSourceText(
        Texts.source(
            sourceKind,
            sourceState,
            SourceContext(rideOptions, provider?.let(HealthProviderKind.serializer()::named)),
        )
    )
}

@JsExport public fun textsHealth(health: dynamic): String = Texts.health(rideHealth(health))

@JsExport public fun textsHealthReason(health: dynamic): String? = Texts.healthReason(rideHealth(health))

@JsExport
public fun textsHealthComponents(components: Array<dynamic>, provider: String?): Array<String> =
    Texts.healthComponents(componentResults(components), provider?.let(HealthProviderKind.serializer()::named))
        .toTypedArray()

@JsExport
public fun textsHistoryHealthLine(health: dynamic, components: Array<dynamic>): String? =
    Texts.historyHealthLine(rideHealth(health), componentResults(components))

@JsExport
public fun textsPermission(need: String, action: String, provider: String?, platform: String): KitPermissionText? {
    val permissionNeed = PermissionNeed.serializer().named(need) ?: return null
    val permissionAction = PermissionAction.serializer().named(action) ?: return null
    return KitPermissionText(
        Texts.permission(
            permissionNeed,
            permissionAction,
            provider?.let(HealthProviderKind.serializer()::named),
            platform.textPlatform(),
        )
    )
}

@JsExport
public fun textsHealthSetup(options: dynamic, request: dynamic, provider: String?): String? {
    val ride = request ?: return null
    return Texts.healthSetup(
        rideOptions(options),
        StartRequest(
            indoor = ride.indoor as Boolean,
            useWatch = ride.useWatch as Boolean,
            saveToHealth = ride.saveToHealth as Boolean,
            recordGps = ride.recordGps as Boolean?,
        ),
        provider?.let(HealthProviderKind.serializer()::named),
    )
}

@JsExport
public fun textsDistanceSource(source: String, foregroundOnly: Boolean): String =
    DistanceSelection.serializer().named(source)?.let { Texts.distanceSource(it, foregroundOnly) } ?: source

@JsExport
public fun textsControllerSpeedNote(controllerSpeed: Boolean?): String? = Texts.controllerSpeedNote(controllerSpeed)

@JsExport
public fun textsConnection(state: String, display: String): String =
    Texts.connection(
        ConnectionState.serializer().named(state) ?: ConnectionState.idle,
        ConnectionDisplay.serializer().named(display) ?: ConnectionDisplay.unavailable,
    )

@JsExport public fun textsControllerFault(faultCode: Int): String = Texts.controllerFault(faultCode)

@JsExport
public fun textsProvider(provider: String): String =
    HealthProviderKind.serializer().named(provider)?.let(Texts::provider) ?: provider

@JsExport
public fun textsRecordedOn(recordedBy: String, useWatch: Boolean): String =
    Texts.recordedOn(Device.serializer().named(recordedBy) ?: Device.iphone, useWatch)

@JsExport public fun textsSetting(field: String): String = Texts.setting(field)

@JsExport
public fun textsChoice(field: String, value: String, platform: String): String =
    Texts.choice(field, value, platform.textPlatform())

@JsExport public fun textsViewName(view: String): String = ViewId.serializer().named(view)?.let(Texts::viewName) ?: view

@JsExport
public fun textsRange(range: String): String = ChartRange.serializer().named(range)?.let(Texts::range) ?: range

@JsExport
public fun textsChartStatus(state: String): KitStatusText? =
    ChartState.entries.firstOrNull { it.name == state }?.let { KitStatusText(Texts.chartStatus(it)) }

@JsExport
public fun textsTraceStatus(state: String, events: Int, stopReason: String?): String =
    Texts.traceStatus(
        TraceState.serializer().named(state) ?: TraceState.idle,
        events,
        stopReason?.let(TraceStopReason.serializer()::named),
    )

@JsExport
public fun textsStoragePersistence(state: String): String =
    Texts.storagePersistence(PersistenceState.serializer().named(state) ?: PersistenceState.unsupported)

@JsExport public fun textsRideCount(count: Int): String = Texts.rideCount(count)

@JsExport public fun textsLapCount(laps: Int): String = Texts.lapCount(laps)

@JsExport
public fun unitsDisplay(metric: String, units: String, temperature: String): KitDisplayUnit? {
    val id = MetricId.serializer().named(metric) ?: return null
    return KitDisplayUnit(Units.display(id, units.unitSystem(), temperature.temperatureUnit()))
}

@JsExport
public fun formatMetric(metric: String, value: Double?, units: String, temperature: String): String {
    val id = MetricId.serializer().named(metric) ?: return Format.MISSING
    return Format.metric(id, value, units.unitSystem(), temperature.temperatureUnit())
}

@JsExport
public fun formatExact(metric: String, value: Double, units: String, temperature: String): String {
    val id = MetricId.serializer().named(metric) ?: return Format.MISSING
    return Format.exact(id, value, units.unitSystem(), temperature.temperatureUnit())
}

@JsExport
public fun formatDifference(metric: String, a: Double?, b: Double?, units: String, temperature: String): String {
    val id = MetricId.serializer().named(metric) ?: return Format.MISSING
    return Format.difference(id, a, b, units.unitSystem(), temperature.temperatureUnit())
}

@JsExport public fun formatDuration(us: Double?): String = Format.duration(us.micros())

@JsExport public fun formatElapsedPrecise(us: Double?): String = Format.elapsedPrecise(us.micros())

@JsExport
public fun formatDistance(distanceM: Double?, units: String): String = Format.distance(distanceM, units.unitSystem())

@JsExport public fun formatDecimal(value: Double?, decimals: Int): String = Format.decimal(value, decimals)

@JsExport
public fun formatIsoTimestamp(utcUs: Double): String = utcUs.micros()?.let(Format::isoTimestamp) ?: Format.MISSING

@JsExport
public fun formatBikeName(model: String?, advertisedName: String?, bikeId: String?): String =
    Format.bikeName(model, advertisedName, bikeId?.let(::BikeId))

@JsExport
public fun metricAppearance(metric: String): KitAppearance? =
    MetricId.serializer().named(metric)?.let { KitAppearance(it, MetricAppearance.of(it)) }

@JsExport
public fun metricAppearances(): Array<KitAppearance> =
    MetricId.entries.map { KitAppearance(it, MetricAppearance.of(it)) }.toTypedArray()

@JsExport
public fun metricCatalogShown(selected: Array<String>, available: Array<String>): Array<String> =
    MetricCatalog.shown(
            selected.mapNotNull(MetricId.serializer()::named),
            available.mapNotNull(MetricId.serializer()::named).toSet(),
        )
        .map { it.serialName() }
        .toTypedArray()

@JsExport
public fun metricCatalogStored(metric: String): String =
    MetricId.serializer().named(metric)?.let { MetricCatalog.stored(it).serialName() } ?: metric

private inline fun <reified T : Enum<T>> KSerializer<T>.named(name: String): T? {
    val index = descriptor.getElementIndex(name)
    return if (index < 0) null else enumValues<T>()[index]
}

private fun MetricId.serialName(): String = MetricId.serializer().descriptor.getElementName(ordinal)

private fun String.textPlatform(): TextPlatform =
    TextPlatform.entries.firstOrNull { it.name == this } ?: TextPlatform.ios

private fun String.unitSystem(): UnitSystem = UnitSystem.serializer().named(this) ?: UnitSystem.metric

private fun String.temperatureUnit(): TemperatureUnit =
    TemperatureUnit.serializer().named(this) ?: TemperatureUnit.celsius

private fun Double?.micros(): Long? = if (this == null || !isFinite()) null else toLong()

private fun rideOptions(options: dynamic): RideOptions? {
    if (options == null) return null
    return RideOptions(
        sampleRate = SampleRate.serializer().named(options.sampleRate as String) ?: SampleRate.hz2,
        indoor = options.indoor as Boolean,
        useWatch = options.useWatch as Boolean,
        saveToHealth = options.saveToHealth as Boolean,
        recordGps = options.recordGps as Boolean,
        distanceSource =
            DistanceSource.serializer().named(options.distanceSource as String) ?: DistanceSource.controller,
        batteryMethod = BatteryMethod.serializer().named(options.batteryMethod as String) ?: BatteryMethod.controller,
    )
}

private fun rideHealth(health: dynamic): RideHealth =
    RideHealth(
        provider = (health.provider as String?)?.let(HealthProviderKind.serializer()::named),
        outcome = HealthOutcome.serializer().named(health.outcome as String) ?: HealthOutcome.unknown,
        reason = (health.reason as String?)?.let(HealthReason.serializer()::named),
        workoutId = null,
    )

private fun componentResults(components: Array<dynamic>): List<ComponentResult> = components.mapNotNull { result ->
    val component = HealthComponent.serializer().named(result.component as String) ?: return@mapNotNull null
    val state = ComponentState.serializer().named(result.state as String) ?: return@mapNotNull null
    ComponentResult(component, state, (result.reason as String?)?.let(HealthReason.serializer()::named))
}
