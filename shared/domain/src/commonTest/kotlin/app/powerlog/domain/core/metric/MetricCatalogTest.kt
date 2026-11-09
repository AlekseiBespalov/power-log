package app.powerlog.domain.core.metric

import app.powerlog.domain.analytics.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricCatalogTest {
    private class Policy(
        val scale: Long,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val gapUs: Long = 2500000,
    )

    private val policies =
        mapOf(
            "humanPowerW" to Policy(1000, 0.0, 32766.0),
            "cadenceRpm" to Policy(10000, 0.0, 254.0),
            "heartRateBpm" to Policy(1000, 1.0, 254.0, 10000000),
            "speedMps" to Policy(1000000, 0.0, 40.0, 10000000),
            "speedAccuracyMps" to Policy(1000000, 0.0, gapUs = 10000000),
            "controllerSpeedMps" to Policy(1000000, 0.0, 40.0),
            "distanceMeters" to Policy(1000000, 0.0),
            "gpsDistanceMeters" to Policy(1000000, 0.0),
            "controllerDistanceMeters" to Policy(1000000, 0.0),
            "controllerEnergyWh" to Policy(1000000, 0.0),
            "controllerChargeAh" to Policy(1000000, 0.0),
            "calculatedEnergyWh" to Policy(1000000, 0.0),
            "calculatedChargeAh" to Policy(1000000, 0.0),
            "batteryWhPerKm" to Policy(1000000, 0.0),
            "riderWhPerKm" to Policy(1000000, 0.0),
            "altitudeMeters" to Policy(1000, -500.0, 20000.0, 10000000),
            "horizontalAccuracyM" to Policy(1000, 0.0, 50.0, 10000000),
            "verticalAccuracyM" to Policy(1000, 0.0, 20.0, 10000000),
            "courseDegrees" to Policy(1000, 0.0),
            "activeEnergyKcal" to Policy(1000000, 0.0),
            "basalEnergyKcal" to Policy(1000000, 0.0),
            "consumedAh" to Policy(10000),
            "consumedWh" to Policy(10000),
            "assistLevel" to Policy(1),
            "raceMode" to Policy(1),
            "faultCode" to Policy(1),
            "crankRevolutions" to Policy(1),
            "motorInputPowerW" to Policy(1000),
            "batteryCurrentA" to Policy(100),
            "motorCurrentA" to Policy(100),
            "pedalTorqueNm" to Policy(100),
            "motorTempC" to Policy(10),
            "controllerTempC" to Policy(10),
            "batteryVoltageV" to Policy(10),
            "throttleVoltageV" to Policy(100),
            "motorRpm" to Policy(1),
            "speedRaw" to Policy(100),
        )

    private val catalog = fixture("telemetry/metrics.json")["metrics"].list().associateBy { it["id"].text() }

    private val controller =
        listOf(
            MetricId.humanPowerW,
            MetricId.cadenceRpm,
            MetricId.pedalTorqueNm,
            MetricId.batteryVoltageV,
            MetricId.batteryCurrentA,
            MetricId.motorInputPowerW,
            MetricId.consumedAh,
            MetricId.consumedWh,
            MetricId.motorCurrentA,
            MetricId.motorTempC,
            MetricId.controllerTempC,
            MetricId.motorRpm,
            MetricId.throttleVoltageV,
            MetricId.assistLevel,
            MetricId.raceMode,
            MetricId.faultCode,
            MetricId.speedRaw,
        )
    private val location =
        listOf(
            MetricId.speedMps,
            MetricId.altitudeMeters,
            MetricId.horizontalAccuracyM,
            MetricId.verticalAccuracyM,
            MetricId.courseDegrees,
        )
    private val totals =
        listOf(
            MetricId.distanceMeters,
            MetricId.gpsDistanceMeters,
            MetricId.controllerDistanceMeters,
            MetricId.controllerEnergyWh,
            MetricId.controllerChargeAh,
            MetricId.calculatedEnergyWh,
            MetricId.calculatedChargeAh,
            MetricId.batteryWhPerKm,
            MetricId.riderWhPerKm,
        )

    @Test
    fun everyMetricHasOneDefinitionInIdOrder() {
        assertEquals(MetricId.entries, MetricCatalog.all.map { it.id })
        MetricId.entries.forEach { assertEquals(it, MetricCatalog.of(it).id) }
    }

    @Test
    fun quantaValidRangesAndIntegrationGapsAreTodaysPolicies() {
        for (definition in MetricCatalog.all) {
            val policy = policies.getValue(definition.id.name)
            assertEquals(policy.scale, definition.quantum, definition.id.name)
            assertEquals(policy.gapUs, definition.integrationGapUs, definition.id.name)
            assertEquals(policy.minimum, definition.validRange?.start, definition.id.name)
            val maximum = definition.validRange?.endInclusive?.takeIf { it.isFinite() }
            assertEquals(policy.maximum, maximum, definition.id.name)
        }
    }

    @Test
    fun plotGapsAreTodaysCatalogGaps() {
        for (definition in MetricCatalog.all) {
            val seconds =
                if (definition.id == MetricId.heartRateBpm) 30.0
                else catalog.getValue(definition.id.name)["gapSeconds"].double()
            assertEquals((seconds * 1_000_000).toLong(), definition.plotGapUs, definition.id.name)
        }
        assertEquals(30_000_000L, MetricCatalog.of(MetricId.heartRateBpm).plotGapUs)
        assertEquals(10_000_000L, MetricCatalog.of(MetricId.speedMps).plotGapUs)
        assertEquals(6_000_000L, MetricCatalog.of(MetricId.batteryWhPerKm).plotGapUs)
    }

    @Test
    fun holdsAreTheTileRule() {
        controller.forEach { assertEquals(6_000_000L, MetricCatalog.of(it).displayHoldUs, it.name) }
        assertEquals(6_000_000L, MetricCatalog.of(MetricId.controllerSpeedMps).displayHoldUs)
        assertEquals(15_000_000L, MetricCatalog.of(MetricId.heartRateBpm).displayHoldUs)
        location.forEach { assertEquals(10_000_000L, MetricCatalog.of(it).displayHoldUs, it.name) }
        assertEquals(120_000_000L, MetricCatalog.of(MetricId.activeEnergyKcal).displayHoldUs)
        assertEquals(120_000_000L, MetricCatalog.of(MetricId.basalEnergyKcal).displayHoldUs)
        totals.forEach { assertNull(MetricCatalog.of(it).displayHoldUs, it.name) }
    }

    @Test
    fun tailsAreTheHeldTail() {
        for (definition in MetricCatalog.all) {
            val expected =
                if (definition.id == MetricId.gpsDistanceMeters || definition.id == MetricId.controllerDistanceMeters)
                    2_500_000L
                else 6_000_000L
            assertEquals(expected, definition.tailUs, definition.id.name)
        }
    }

    @Test
    fun plotAndStatisticsRangesAreTodaysPlotAndMonitorPolicies() {
        assertNull(MetricCatalog.of(MetricId.humanPowerW).plotRange)
        assertEquals(0.0..32_766.0, MetricCatalog.of(MetricId.humanPowerW).statisticsRange)
        assertNull(MetricCatalog.of(MetricId.cadenceRpm).plotRange)
        assertNull(MetricCatalog.of(MetricId.altitudeMeters).plotRange)
        assertNull(MetricCatalog.of(MetricId.altitudeMeters).statisticsRange)
        for (open in listOf(MetricId.speedMps, MetricId.controllerSpeedMps, MetricId.horizontalAccuracyM)) {
            assertEquals(Double.POSITIVE_INFINITY, MetricCatalog.of(open).plotRange?.endInclusive, open.name)
            assertEquals(Double.POSITIVE_INFINITY, MetricCatalog.of(open).statisticsRange?.endInclusive, open.name)
        }
        val course = MetricCatalog.of(MetricId.courseDegrees)
        assertTrue(359.999 in course.plotRange!!)
        assertFalse(360.0 in course.plotRange!!)
        assertTrue(360.0 in course.statisticsRange!!)
        assertEquals(1.0..254.0, MetricCatalog.of(MetricId.heartRateBpm).plotRange)
    }

    @Test
    fun meansSelectionOriginsAndSemanticsFollowTodaysCatalog() {
        for (definition in MetricCatalog.all) {
            val today = catalog.getValue(definition.id.name)
            val allowsMean =
                today["semantics"].optionalText() == null &&
                    today["kind"].optionalText() != "step" &&
                    definition.id != MetricId.activeEnergyKcal &&
                    definition.id != MetricId.basalEnergyKcal
            assertEquals(allowsMean, definition.allowsMean, definition.id.name)
            val unselectable = definition.id == MetricId.speedRaw || definition.id == MetricId.basalEnergyKcal
            assertEquals(!unselectable, definition.selectable, definition.id.name)
        }
        controller.forEach { assertEquals(MetricOrigin.telemetry, MetricCatalog.of(it).origin, it.name) }
        location.forEach { assertEquals(MetricOrigin.location, MetricCatalog.of(it).origin, it.name) }
        (totals + MetricId.controllerSpeedMps).forEach {
            assertEquals(MetricOrigin.derived, MetricCatalog.of(it).origin, it.name)
        }
        listOf(MetricId.heartRateBpm, MetricId.activeEnergyKcal, MetricId.basalEnergyKcal).forEach {
            assertEquals(MetricOrigin.health, MetricCatalog.of(it).origin, it.name)
        }
        assertEquals(MetricSemantics.counter, MetricCatalog.of(MetricId.consumedWh).semantics)
        assertEquals(MetricSemantics.distance, MetricCatalog.of(MetricId.gpsDistanceMeters).semantics)
        assertEquals(MetricSemantics.cumulative, MetricCatalog.of(MetricId.calculatedChargeAh).semantics)
        assertEquals(MetricSemantics.ratio, MetricCatalog.of(MetricId.riderWhPerKm).semantics)
        assertEquals(MetricSemantics.circular, MetricCatalog.of(MetricId.courseDegrees).semantics)
        assertEquals(MetricSemantics.category, MetricCatalog.of(MetricId.faultCode).semantics)
        assertEquals(MetricSemantics.instantaneous, MetricCatalog.of(MetricId.humanPowerW).semantics)
    }

    @Test
    fun aSourceWithOnlyTheRawSpeedSignalShowsItInPlaceOfControllerSpeed() {
        val selected = listOf(MetricId.humanPowerW, MetricId.controllerSpeedMps, MetricId.cadenceRpm)
        val raw = listOf(MetricId.humanPowerW, MetricId.speedRaw, MetricId.cadenceRpm)
        assertEquals(raw, MetricCatalog.shown(selected, setOf(MetricId.speedRaw, MetricId.humanPowerW)))
        assertEquals(selected, MetricCatalog.shown(selected, setOf(MetricId.speedRaw, MetricId.controllerSpeedMps)))
        assertEquals(selected, MetricCatalog.shown(selected, setOf(MetricId.controllerSpeedMps)))
        assertEquals(selected, MetricCatalog.shown(selected, emptySet()))
        assertEquals(selected, raw.map(MetricCatalog::stored))
        assertEquals(MetricId.speedMps, MetricCatalog.stored(MetricId.speedMps))
    }
}
