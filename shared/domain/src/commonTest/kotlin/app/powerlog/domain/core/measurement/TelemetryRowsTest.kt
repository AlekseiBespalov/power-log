package app.powerlog.domain.core.measurement

import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryRowsTest {
    private val ride = RideId("00000000-0000-4000-8000-000000000001")
    private val identity = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")

    private fun session(speed: Boolean) =
        ControllerSession(ride, 1, identity, ControllerCapabilities(setOf(MetricId.speedRaw), speed))

    private fun sample(seq: Long, quanta: Map<MetricId, Long>, interval: Int?) =
        TelemetrySample(ride, 1, seq, seq * 125_000, 1_000_000 + seq, interval, 1, quanta)

    @Test
    fun samplesBecomeColumnsWithNaNForAnUnreportedValue() {
        val rows =
            TelemetryRows.of(
                listOf(
                    sample(1, mapOf(MetricId.humanPowerW to 250_000L, MetricId.speedRaw to 2_520L), 1),
                    sample(2, mapOf(MetricId.humanPowerW to 251_000L), null),
                )
            )
        assertEquals(2, rows.rows)
        assertContentEquals(doubleArrayOf(125_000.0, 250_000.0), rows.elapsedUs)
        assertContentEquals(intArrayOf(1, 0), rows.activeInterval)
        assertContentEquals(doubleArrayOf(1.0, 2.0), rows.seq)
        assertEquals(setOf(MetricId.humanPowerW, MetricId.speedRaw), rows.quanta.keys)
        assertTrue(rows.quanta.getValue(MetricId.speedRaw)[1].isNaN())
    }

    @Test
    fun aValueIsItsQuantaOverTheQuantumAndControllerSpeedFollowsTheSession() {
        val rows =
            TelemetryRows.of(listOf(sample(1, mapOf(MetricId.humanPowerW to 250_500L, MetricId.speedRaw to 2_520L), 1)))
        assertEquals(250.5, rows.value(MetricId.humanPowerW, 0, session(true)))
        assertEquals(25.2, rows.value(MetricId.speedRaw, 0, session(true)))
        assertEquals(25.2 / 3.6, rows.value(MetricId.controllerSpeedMps, 0, session(true)))
        assertNull(rows.value(MetricId.controllerSpeedMps, 0, session(false)))
        assertNull(rows.value(MetricId.cadenceRpm, 0, session(true)))
    }

    @Test
    fun aControllerSpeedColumnIsReadAsItsCellsAndAnEmptyCellIsNoReading() {
        val speed = MetricCatalog.of(MetricId.controllerSpeedMps).quantum
        val rows =
            TelemetryRows(
                2,
                doubleArrayOf(0.0, 125_000.0),
                intArrayOf(1, 1),
                doubleArrayOf(1.0, 2.0),
                doubleArrayOf(1_000_000.0, 1_125_000.0),
                intArrayOf(1, 1),
                intArrayOf(1, 1),
                mapOf(
                    MetricId.speedRaw to doubleArrayOf(2_520.0, 2_520.0),
                    MetricId.controllerSpeedMps to doubleArrayOf(6.25 * speed, Double.NaN),
                ),
            )
        assertEquals(6.25, rows.value(MetricId.controllerSpeedMps, 0, session(true)))
        assertNull(rows.value(MetricId.controllerSpeedMps, 1, session(true)))
        assertNull(rows.value(MetricId.controllerSpeedMps, 0, session(false)))
    }
}
