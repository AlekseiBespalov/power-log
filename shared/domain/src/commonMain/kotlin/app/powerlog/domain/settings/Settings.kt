package app.powerlog.domain.settings

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.SampleRate
import kotlinx.serialization.Serializable

@Serializable
public enum class ViewId {
    ride,
    battery,
    temperature,
}

@Serializable
public enum class ChartRange {
    seconds30,
    seconds120,
    seconds600,
    all,
}

@Serializable
public data class ViewPreset(
    public val numbers: List<MetricId>,
    public val charts: List<MetricId>,
    public val range: ChartRange,
    public val webColumns: Int,
)

@Serializable
public data class AppSettings(
    public val units: UnitSystem,
    public val temperature: TemperatureUnit,
    public val fitPower: FitPowerSource,
    public val views: Map<ViewId, ViewPreset>,
    public val activeView: ViewId,
    public val historyView: ViewId?,
    public val historyRange: ChartRange,
)

@Serializable public data class SettingsDocument(public val ride: RideSettings, public val app: AppSettings)

@Serializable
public data class LocaleFacts(
    public val region: String?,
    public val measurementSystem: MeasurementSystem?,
    public val temperature: TemperatureUnit?,
)

@Serializable
public enum class MeasurementSystem {
    metric,
    us,
    uk,
}

@Serializable
public enum class PersistenceState {
    persisted,
    notPersisted,
    unsupported,
}

public data class SettingsDraft(public val ride: RideSettingsDraft?, public val app: AppSettingsDraft?) {
    public companion object {
        public fun of(settings: SettingsDocument): SettingsDraft {
            val ride = settings.ride
            val app = settings.app
            return SettingsDraft(
                RideSettingsDraft(
                    ride.sampleRate,
                    ride.distance,
                    ride.battery,
                    ride.indoor,
                    ride.useWatch,
                    ride.saveToHealth,
                    ride.recordGps,
                    ride.syncBeeps,
                ),
                AppSettingsDraft(
                    units = app.units,
                    temperature = app.temperature,
                    fitPower = app.fitPower,
                    views =
                        app.views.mapValues { (_, preset) ->
                            ViewPresetDraft(
                                preset.numbers.map { it.name },
                                preset.charts.map { it.name },
                                preset.range,
                                preset.webColumns,
                            )
                        },
                    activeView = app.activeView,
                    historyView = app.historyView,
                    historyRange = app.historyRange,
                ),
            )
        }
    }
}

public data class RideSettingsDraft(
    public val sampleRate: SampleRate?,
    public val distance: DistanceSelection?,
    public val battery: BatteryMethod?,
    public val indoor: Boolean?,
    public val useWatch: Boolean?,
    public val saveToHealth: Boolean?,
    public val recordGps: Boolean?,
    public val syncBeeps: Boolean?,
)

public data class AppSettingsDraft(
    public val units: UnitSystem?,
    public val temperature: TemperatureUnit?,
    public val fitPower: FitPowerSource?,
    public val views: Map<ViewId, ViewPresetDraft>?,
    public val activeView: ViewId?,
    public val historyView: ViewId?,
    public val historyRange: ChartRange?,
)

public data class ViewPresetDraft(
    public val numbers: List<String>?,
    public val charts: List<String>?,
    public val range: ChartRange?,
    public val webColumns: Int?,
)

public object SettingsPolicy {
    public val webColumns: IntRange = 1..3
}

public object SettingsRules {
    private val imperialRegions = setOf("US", "LR", "MM", "GB")
    private val fahrenheitRegions = setOf("US", "BS", "BZ", "KY", "PR", "PW")

    private val views: Map<ViewId, ViewPreset> =
        mapOf(
            ViewId.ride to
                ViewPreset(
                    numbers =
                        listOf(
                            MetricId.humanPowerW,
                            MetricId.cadenceRpm,
                            MetricId.motorInputPowerW,
                            MetricId.controllerSpeedMps,
                        ),
                    charts = listOf(MetricId.humanPowerW, MetricId.motorInputPowerW, MetricId.cadenceRpm),
                    range = ChartRange.seconds120,
                    webColumns = 2,
                ),
            ViewId.battery to
                ViewPreset(
                    numbers = listOf(MetricId.batteryVoltageV, MetricId.batteryCurrentA, MetricId.motorInputPowerW),
                    charts = listOf(MetricId.batteryVoltageV, MetricId.batteryCurrentA, MetricId.motorInputPowerW),
                    range = ChartRange.seconds120,
                    webColumns = 2,
                ),
            ViewId.temperature to
                ViewPreset(
                    numbers = listOf(MetricId.motorTempC, MetricId.controllerTempC, MetricId.motorInputPowerW),
                    charts = listOf(MetricId.motorTempC, MetricId.controllerTempC, MetricId.motorInputPowerW),
                    range = ChartRange.seconds600,
                    webColumns = 2,
                ),
        )

    public fun defaults(locale: LocaleFacts): SettingsDocument {
        val region = locale.region?.uppercase()
        val units =
            when (locale.measurementSystem) {
                MeasurementSystem.metric -> UnitSystem.metric
                MeasurementSystem.us,
                MeasurementSystem.uk -> UnitSystem.imperial
                null -> if (region in imperialRegions) UnitSystem.imperial else UnitSystem.metric
            }
        val temperature =
            locale.temperature
                ?: if (region in fahrenheitRegions) TemperatureUnit.fahrenheit else TemperatureUnit.celsius
        return SettingsDocument(
            ride =
                RideSettings(
                    sampleRate = SampleRate.hz2,
                    distance = DistanceSelection.auto,
                    battery = BatteryMethod.controller,
                    indoor = false,
                    useWatch = false,
                    saveToHealth = true,
                    recordGps = null,
                    syncBeeps = false,
                ),
            app =
                AppSettings(
                    units = units,
                    temperature = temperature,
                    fitPower = FitPowerSource.rider,
                    views = views,
                    activeView = ViewId.ride,
                    historyView = null,
                    historyRange = ChartRange.all,
                ),
        )
    }

    public fun sanitize(draft: SettingsDraft, locale: LocaleFacts): SettingsDocument {
        val defaults = defaults(locale)
        val ride = draft.ride
        val app = draft.app
        val rideDefaults = defaults.ride
        val appDefaults = defaults.app
        return SettingsDocument(
            ride =
                RideSettings(
                    sampleRate = ride?.sampleRate ?: rideDefaults.sampleRate,
                    distance = ride?.distance ?: rideDefaults.distance,
                    battery = ride?.battery ?: rideDefaults.battery,
                    indoor = ride?.indoor ?: rideDefaults.indoor,
                    useWatch = ride?.useWatch ?: rideDefaults.useWatch,
                    saveToHealth = ride?.saveToHealth ?: rideDefaults.saveToHealth,
                    recordGps = ride?.recordGps ?: rideDefaults.recordGps,
                    syncBeeps = ride?.syncBeeps ?: rideDefaults.syncBeeps,
                ),
            app =
                AppSettings(
                    units = app?.units ?: appDefaults.units,
                    temperature = app?.temperature ?: appDefaults.temperature,
                    fitPower = app?.fitPower ?: appDefaults.fitPower,
                    views =
                        ViewId.entries.associateWith { preset(app?.views?.get(it), appDefaults.views.getValue(it)) },
                    activeView = app?.activeView ?: appDefaults.activeView,
                    historyView = app?.historyView ?: appDefaults.historyView,
                    historyRange = app?.historyRange ?: appDefaults.historyRange,
                ),
        )
    }

    private fun preset(draft: ViewPresetDraft?, default: ViewPreset): ViewPreset =
        ViewPreset(
            numbers = draft?.numbers?.let(::metrics) ?: default.numbers,
            charts = draft?.charts?.let(::metrics) ?: default.charts,
            range = draft?.range ?: default.range,
            webColumns = draft?.webColumns?.takeIf { it in SettingsPolicy.webColumns } ?: default.webColumns,
        )

    private fun metrics(names: List<String>): List<MetricId> =
        names
            .mapNotNull { name -> MetricId.entries.firstOrNull { it.name == name } }
            .filter { MetricCatalog.of(it).selectable }
            .distinct()
}
