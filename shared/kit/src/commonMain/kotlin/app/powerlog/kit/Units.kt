package app.powerlog.kit

import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem

public data class DisplayUnit(
    public val symbol: String,
    public val decimals: Int,
    public val factor: Double,
    public val offset: Double,
)

public object Units {
    public fun display(metric: MetricId, units: UnitSystem, temperature: TemperatureUnit): DisplayUnit =
        conversion(metric, units, temperature).display

    internal class Conversion(
        symbol: String,
        decimals: Int,
        factor: Double = 1.0,
        offset: Double = 0.0,
        val exactFactor: String? = "1",
    ) {
        val display = DisplayUnit(symbol, decimals, factor, offset)
    }

    private val speed = Conversion("km/h", 1, 3.6, exactFactor = "3.6")
    private val mph = Conversion("mph", 1, 1 / 0.44704, exactFactor = null)
    private val kilometres = Conversion("km", 2, 0.001, exactFactor = "0.001")
    private val miles = Conversion("mi", 2, 1 / 1_609.344, exactFactor = null)
    private val perKilometre = Conversion("Wh/km", 1)
    private val perMile = Conversion("Wh/mi", 1, 1.609_344, exactFactor = "1.609344")
    private val celsius = Conversion("°C", 1)
    private val fahrenheit = Conversion("°F", 1, 9.0 / 5.0, 32.0, exactFactor = null)

    private fun feet(decimals: Int) = Conversion("ft", decimals, 1 / 0.3048, exactFactor = null)

    private val metric: Map<MetricId, Conversion> =
        mapOf(
            MetricId.humanPowerW to Conversion("W", 0),
            MetricId.cadenceRpm to Conversion("rpm", 0),
            MetricId.heartRateBpm to Conversion("bpm", 0),
            MetricId.pedalTorqueNm to Conversion("Nm", 1),
            MetricId.activeEnergyKcal to Conversion("kcal", 0),
            MetricId.basalEnergyKcal to Conversion("kcal", 0),
            MetricId.batteryVoltageV to Conversion("V", 2),
            MetricId.batteryCurrentA to Conversion("A", 1),
            MetricId.motorInputPowerW to Conversion("W", 0),
            MetricId.consumedAh to Conversion("Ah", 2),
            MetricId.consumedWh to Conversion("Wh", 1),
            MetricId.motorCurrentA to Conversion("A", 1),
            MetricId.motorTempC to celsius,
            MetricId.controllerTempC to celsius,
            MetricId.motorRpm to Conversion("rpm", 0),
            MetricId.throttleVoltageV to Conversion("V", 2),
            MetricId.speedMps to speed,
            MetricId.controllerSpeedMps to speed,
            MetricId.distanceMeters to kilometres,
            MetricId.gpsDistanceMeters to kilometres,
            MetricId.controllerDistanceMeters to kilometres,
            MetricId.controllerEnergyWh to Conversion("Wh", 2),
            MetricId.controllerChargeAh to Conversion("Ah", 3),
            MetricId.calculatedEnergyWh to Conversion("Wh", 2),
            MetricId.calculatedChargeAh to Conversion("Ah", 3),
            MetricId.batteryWhPerKm to perKilometre,
            MetricId.riderWhPerKm to perKilometre,
            MetricId.altitudeMeters to Conversion("m", 0),
            MetricId.horizontalAccuracyM to Conversion("m", 1),
            MetricId.verticalAccuracyM to Conversion("m", 1),
            MetricId.courseDegrees to Conversion("°", 0),
            MetricId.assistLevel to Conversion("", 0),
            MetricId.raceMode to Conversion("", 0),
            MetricId.faultCode to Conversion("", 0),
            MetricId.speedRaw to Conversion("raw", 2),
        )

    private val imperial: Map<MetricId, Conversion> =
        mapOf(
            MetricId.speedMps to mph,
            MetricId.controllerSpeedMps to mph,
            MetricId.distanceMeters to miles,
            MetricId.gpsDistanceMeters to miles,
            MetricId.controllerDistanceMeters to miles,
            MetricId.batteryWhPerKm to perMile,
            MetricId.riderWhPerKm to perMile,
            MetricId.altitudeMeters to feet(0),
            MetricId.horizontalAccuracyM to feet(1),
            MetricId.verticalAccuracyM to feet(1),
        )

    internal fun conversion(metric: MetricId, units: UnitSystem, temperature: TemperatureUnit): Conversion =
        when {
            temperature == TemperatureUnit.fahrenheit && this.metric[metric] === celsius -> fahrenheit
            units == UnitSystem.imperial -> imperial[metric] ?: this.metric.getValue(metric)
            else -> this.metric.getValue(metric)
        }
}
