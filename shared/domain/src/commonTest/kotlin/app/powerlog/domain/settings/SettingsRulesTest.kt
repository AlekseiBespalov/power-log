package app.powerlog.domain.settings

import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRulesTest {
    private val unknown = LocaleFacts(null, null, null)

    @Test
    fun theDefaultsAreTodaysPreferences() {
        val document = SettingsRules.defaults(unknown)
        assertEquals(
            RideSettings(
                SampleRate.hz2,
                DistanceSelection.auto,
                BatteryMethod.controller,
                false,
                false,
                true,
                null,
                false,
            ),
            document.ride,
        )
        val app = document.app
        assertEquals(FitPowerSource.rider, app.fitPower)
        assertEquals(ViewId.ride, app.activeView)
        assertEquals(null, app.historyView)
        assertEquals(ChartRange.all, app.historyRange)
        assertEquals(
            ViewPreset(
                listOf(
                    MetricId.humanPowerW,
                    MetricId.cadenceRpm,
                    MetricId.motorInputPowerW,
                    MetricId.controllerSpeedMps,
                ),
                listOf(MetricId.humanPowerW, MetricId.motorInputPowerW, MetricId.cadenceRpm),
                ChartRange.seconds120,
                2,
            ),
            app.views[ViewId.ride],
        )
        assertEquals(ChartRange.seconds120, app.views.getValue(ViewId.battery).range)
        assertEquals(ChartRange.seconds600, app.views.getValue(ViewId.temperature).range)
        assertEquals(ViewId.entries.toSet(), app.views.keys)
    }

    @Test
    fun unitsFollowTheReportedSystemElseTheRegion() {
        fun units(region: String?, system: MeasurementSystem?) =
            SettingsRules.defaults(LocaleFacts(region, system, null)).app.units
        assertEquals(UnitSystem.metric, units(null, null))
        assertEquals(UnitSystem.metric, units("DE", null))
        listOf("US", "LR", "MM", "GB", "gb").forEach { assertEquals(UnitSystem.imperial, units(it, null), it) }
        assertEquals(UnitSystem.imperial, units("DE", MeasurementSystem.uk))
        assertEquals(UnitSystem.imperial, units("DE", MeasurementSystem.us))
        assertEquals(UnitSystem.metric, units("US", MeasurementSystem.metric))
    }

    @Test
    fun temperatureFollowsTheReportedUnitElseTheRegion() {
        fun temperature(region: String?, unit: TemperatureUnit?) =
            SettingsRules.defaults(LocaleFacts(region, null, unit)).app.temperature
        listOf("US", "BS", "BZ", "KY", "PR", "PW").forEach {
            assertEquals(TemperatureUnit.fahrenheit, temperature(it, null), it)
        }
        assertEquals(TemperatureUnit.celsius, temperature("GB", null))
        assertEquals(TemperatureUnit.celsius, temperature("US", TemperatureUnit.celsius))
        assertEquals(TemperatureUnit.fahrenheit, temperature(null, TemperatureUnit.fahrenheit))
    }

    @Test
    fun aMissingFieldComesFromTheDefaults() {
        val defaults = SettingsRules.defaults(unknown)
        assertEquals(defaults, SettingsRules.sanitize(SettingsDraft(null, null), unknown))
        val draft =
            SettingsDraft(
                RideSettingsDraft(SampleRate.hz8, null, null, true, null, null, null, null),
                AppSettingsDraft(UnitSystem.imperial, null, null, null, ViewId.battery, null, null),
            )
        val document = SettingsRules.sanitize(draft, unknown)
        assertEquals(defaults.ride.copy(sampleRate = SampleRate.hz8, indoor = true), document.ride)
        assertEquals(defaults.app.copy(units = UnitSystem.imperial, activeView = ViewId.battery), document.app)
    }

    @Test
    fun presetsKeepOnlyKnownSelectableMetricsOnceAndTheirColumnsInRange() {
        val draft =
            SettingsDraft(
                null,
                AppSettingsDraft(
                    null,
                    null,
                    null,
                    mapOf(
                        ViewId.ride to
                            ViewPresetDraft(
                                listOf(
                                    "cadenceRpm",
                                    "speedRaw",
                                    "glucose",
                                    "cadenceRpm",
                                    "basalEnergyKcal",
                                    "heartRateBpm",
                                ),
                                emptyList(),
                                ChartRange.seconds30,
                                4,
                            ),
                        ViewId.battery to ViewPresetDraft(null, listOf("batteryVoltageV"), null, 3),
                    ),
                    null,
                    null,
                    null,
                ),
            )
        val views = SettingsRules.sanitize(draft, unknown).app.views
        val defaults = SettingsRules.defaults(unknown).app.views
        assertEquals(
            ViewPreset(listOf(MetricId.cadenceRpm, MetricId.heartRateBpm), emptyList(), ChartRange.seconds30, 2),
            views[ViewId.ride],
        )
        assertEquals(
            defaults.getValue(ViewId.battery).copy(charts = listOf(MetricId.batteryVoltageV), webColumns = 3),
            views[ViewId.battery],
        )
        assertEquals(defaults[ViewId.temperature], views[ViewId.temperature])
    }

    @Test
    fun aDocumentRoundTripsThroughItsDraft() {
        val document = SettingsRules.defaults(LocaleFacts("US", null, null))
        val changed = document.copy(ride = document.ride.copy(recordGps = false, sampleRate = SampleRate.hz4))
        assertEquals(changed, SettingsRules.sanitize(SettingsDraft.of(changed), unknown))
    }
}
