package app.powerlog.kit

import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.time.Utc
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    private val catalog = fixture("telemetry/metrics.json")["metrics"].list().associateBy { it["id"].text() }

    private val metric = UnitSystem.metric
    private val imperial = UnitSystem.imperial
    private val celsius = TemperatureUnit.celsius

    @Test
    fun distancesShowInKilometresOrMilesWithTwoDecimals() {
        for (row in fixture("presentation/units.json")["distance"].list()) {
            val units = UnitSystem.valueOf(row["units"].text())
            val distance = row["distanceM"].let { if (it == J.Null) null else it.double() }
            assertEquals(row["text"].text(), Format.distance(distance, units), "$row")
            assertEquals(row["symbol"].text(), Units.display(MetricId.distanceMeters, units, celsius).symbol, "$row")
        }
    }

    @Test
    fun convertedQuantitiesShowInTheirUnitAndDecimals() {
        for (row in fixture("presentation/units.json")["metrics"].list()) {
            val id = MetricId.valueOf(row["metric"].text())
            val units = UnitSystem.valueOf(row["units"].text())
            val temperature = TemperatureUnit.valueOf(row["temperature"].text())
            assertEquals(row["text"].text(), Format.metric(id, row["value"].double(), units, temperature), "$row")
            assertEquals(row["symbol"].text(), Units.display(id, units, temperature).symbol, "$row")
        }
    }

    @Test
    fun metricUnitsKeepTodaysCatalogAndImperialOnlyConvertsDisplay() {
        for (id in MetricId.entries) {
            val today = catalog.getValue(id.name)
            val decimals = today["decimals"].long().toInt()
            val unit = Units.display(id, metric, celsius)
            assertEquals(DisplayUnit(today["unit"].text(), decimals, today["scale"].double(), 0.0), unit, id.name)
            val converted = Units.display(id, imperial, TemperatureUnit.fahrenheit)
            assertEquals(decimals, converted.decimals, id.name)
        }
        assertEquals(
            DisplayUnit("°F", 1, 1.8, 32.0),
            Units.display(MetricId.controllerTempC, metric, TemperatureUnit.fahrenheit),
        )
        assertEquals(
            DisplayUnit("W", 0, 1.0, 0.0),
            Units.display(MetricId.humanPowerW, imperial, TemperatureUnit.fahrenheit),
        )
    }

    @Test
    fun aMissingOrNonFiniteValueIsADashNeverZero() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("—", Format.metric(MetricId.humanPowerW, value, metric, celsius))
            assertEquals("—", Format.decimal(value, 1))
            assertEquals("—", Format.difference(MetricId.batteryVoltageV, 1.0, value, metric, celsius))
        }
        assertEquals("—", Format.exact(MetricId.batteryVoltageV, Double.NaN, metric, celsius))
        assertEquals("—", Format.duration(null))
        assertEquals("—", Format.elapsedPrecise(null))
    }

    @Test
    fun exactOriginalsAreDecimalExactOnTerminatingFactorsAndRoundedOtherwise() {
        assertEquals("1.01", Format.exact(MetricId.distanceMeters, 1_005.0, metric, celsius))
        assertEquals("1.00", Format.metric(MetricId.distanceMeters, 1_004.999_999, metric, celsius))
        assertEquals("0.5", Format.exact(MetricId.speedMps, 0.125, metric, celsius))
        assertEquals("36.50", Format.exact(MetricId.batteryVoltageV, 36.5, metric, celsius))
        assertEquals("250", Format.exact(MetricId.humanPowerW, 249.5, metric, celsius))
        assertEquals("-0.01", Format.exact(MetricId.distanceMeters, -5.0, metric, celsius))
        assertEquals("22.4", Format.exact(MetricId.controllerSpeedMps, 10.0, imperial, celsius))
        assertEquals("212.0", Format.exact(MetricId.motorTempC, 100.0, metric, TemperatureUnit.fahrenheit))
        assertEquals("19.9", Format.exact(MetricId.batteryWhPerKm, 12.34, imperial, celsius))
    }

    @Test
    fun aDifferenceIsTakenOnTheQuantaWithASignWhenPositive() {
        assertEquals("+0.20", Format.difference(MetricId.batteryVoltageV, 36.5, 36.7, metric, celsius))
        assertEquals("-0.20", Format.difference(MetricId.batteryVoltageV, 36.7, 36.5, metric, celsius))
        assertEquals("0.00", Format.difference(MetricId.batteryVoltageV, 36.5, 36.5, metric, celsius))
        assertEquals("+3.6", Format.difference(MetricId.speedMps, 1.0, 2.0, metric, celsius))
        assertEquals("+2.2", Format.difference(MetricId.speedMps, 1.0, 2.0, imperial, celsius))
        assertEquals("+1.8", Format.difference(MetricId.motorTempC, 20.0, 21.0, metric, TemperatureUnit.fahrenheit))
        assertEquals("—", Format.difference(MetricId.consumedWh, 1.0, 2.0, metric, celsius))
    }

    @Test
    fun durationsAreMinutesUnderAnHourThenHours() {
        assertEquals("00:00", Format.duration(0))
        assertEquals("00:00", Format.duration(-5_000_000))
        assertEquals("00:59", Format.duration(59_999_999))
        assertEquals("59:59", Format.duration(3_599_999_999))
        assertEquals("1:00:00", Format.duration(3_600_000_000))
        assertEquals("27:46:40", Format.duration(100_000_000_000))
    }

    @Test
    fun preciseElapsedTimesRoundToTheMillisecondAndMarkNegatives() {
        assertEquals("00:00.000", Format.elapsedPrecise(0))
        assertEquals("00:01.500", Format.elapsedPrecise(1_499_500))
        assertEquals("00:01.499", Format.elapsedPrecise(1_499_499))
        assertEquals("−00:02.250", Format.elapsedPrecise(-2_250_000))
        assertEquals("−00:00.000", Format.elapsedPrecise(-400))
        assertEquals("1:01:01.001", Format.elapsedPrecise(3_661_001_000))
    }

    @Test
    fun decimalsAndTimestamps() {
        assertEquals("12.3", Format.decimal(12.345, 1))
        assertEquals("-2", Format.decimal(-1.5, 0))
        assertEquals("1.01", Format.decimal(1.005_000_000_000_000_1, 2))
        assertEquals(
            "2026-11-02T14:05:06.789Z",
            Format.isoTimestamp(requireNotNull(Utc.parseUs("2026-11-02T14:05:06.789999Z"))),
        )
        assertEquals("1970-01-01T00:00:00.000Z", Format.isoTimestamp(0))
        assertEquals("1969-12-31T23:59:59.999Z", Format.isoTimestamp(-1))
        assertEquals("2000-02-29T00:00:00.000Z", Format.isoTimestamp(951_782_400_000_000))
    }

    @Test
    fun bikeNamesTakeTheModelOrTheAdvertisedNameAndAStableTag() {
        val id = BikeId("AA:BB:CC:DD:EE:FF")
        val tag = Format.bikeName(null, null, id).substringAfter(" · ")
        assertEquals(6, tag.length)
        assertEquals("CYC X6 · $tag", Format.bikeName("X6", "CYCMOTOR", id))
        assertEquals("CYC bike · $tag", Format.bikeName(null, "cyc controller", id))
        assertEquals("CYC bike · $tag", Format.bikeName("", "CYCMOTOR", id))
        assertEquals("Trail bike · $tag", Format.bikeName(null, "Trail bike", BikeId("aa:bb:cc:dd:ee:ff")))
        assertEquals("CYC X12", Format.bikeName("X12", null, null))
        assertEquals("CYC bike · 47E90B", Format.bikeName(null, null, BikeId("abc")))
        assertEquals("9E986F", tag)
    }
}
