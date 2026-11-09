package app.powerlog.application.settings

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.ApiStream
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.settings.AppSettings
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.ViewId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public interface SettingsApi {
    public val state: ApiStream<Empty, SettingsView>

    public suspend fun change(input: SettingsChange): Outcome<Unit>
}

@Serializable
public data class SettingsView(
    public val ride: RideSettings,
    public val app: AppSettings,
    public val effective: RideOptions?,
    public val state: SettingsDocumentState,
    public val failure: ApiFailure?,
)

@Serializable
public enum class SettingsDocumentState {
    ok,
    unreadable,
    saveFailed,
}

@Serializable
public sealed interface SettingsChange {
    @Serializable
    @SerialName("sampleRate")
    public data class SampleRateTo(public val value: SampleRate) : SettingsChange

    @Serializable
    @SerialName("distance")
    public data class DistanceTo(public val value: DistanceSelection) : SettingsChange

    @Serializable @SerialName("battery") public data class BatteryTo(public val value: BatteryMethod) : SettingsChange

    @Serializable
    @SerialName("toggle")
    public data class Toggle(public val field: RideToggle, public val on: Boolean) : SettingsChange

    @Serializable @SerialName("recordGps") public data class RecordGpsTo(public val value: Boolean?) : SettingsChange

    @Serializable @SerialName("units") public data class UnitsTo(public val value: UnitSystem) : SettingsChange

    @Serializable
    @SerialName("temperature")
    public data class TemperatureTo(public val value: TemperatureUnit) : SettingsChange

    @Serializable
    @SerialName("fitPower")
    public data class FitPowerTo(public val value: FitPowerSource) : SettingsChange

    @Serializable @SerialName("activeView") public data class ActiveViewTo(public val value: ViewId) : SettingsChange

    @Serializable @SerialName("historyView") public data class HistoryViewTo(public val value: ViewId?) : SettingsChange

    @Serializable
    @SerialName("historyRange")
    public data class HistoryRangeTo(public val value: ChartRange) : SettingsChange

    @Serializable
    @SerialName("preset")
    public data class Preset(
        public val view: ViewId,
        public val numbers: List<MetricId>?,
        public val charts: List<MetricId>?,
        public val range: ChartRange?,
        public val webColumns: Int?,
    ) : SettingsChange

    @Serializable @SerialName("viewReset") public data class ViewReset(public val view: ViewId) : SettingsChange

    @Serializable @SerialName("reset") public data object Reset : SettingsChange
}

@Serializable
public enum class RideToggle {
    indoor,
    useWatch,
    saveToHealth,
}
