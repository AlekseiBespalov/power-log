package app.powerlog.core.analytics

import app.powerlog.core.inspection.*
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.TelemetryMetric
import kotlin.test.*

class BatteryConsumptionTest {
    private fun BatteryConsumptionKernel.sample(
        second: Double,
        charge: Double? = 12.0,
        energy: Double? = 600.0,
        current: Double? = 36.0,
        voltage: Double? = 50.0,
        active: Int? = 0,
        continuity: String? = "controller/connection-1",
    ) = append(FixedPoint.timeUs(second), active, continuity, charge, energy, current, voltage)

    private fun List<BatteryConsumptionInterval>.metric(id: String) = single { it.metric == id }

    @Test
    fun originalBalancesAreIndependentFromRideTotalsAndCalculation() {
        val kernel = BatteryConsumptionKernel()
        val baseline = kernel.sample(0.0)
        assertEquals(4, baseline.size)
        assertTrue(baseline.all { it.baseline && it.startValue == 0.0 && it.endValue == 0.0 })
        val next = kernel.sample(1.0, 12.02, 601.0)
        assertEquals(0.02, next.metric("controllerChargeAh").endValue, 1e-12)
        assertEquals(1.0, next.metric("controllerEnergyWh").endValue, 1e-12)
        assertEquals(0.01, next.metric("calculatedChargeAh").endValue, 1e-12)
        assertEquals(0.5, next.metric("calculatedEnergyWh").endValue, 1e-12)
        assertTrue(BatteryConsumptionKernel().sample(0.0, 12.02, 601.0).all { it.endValue == 0.0 })
    }

    @Test
    fun boundariesRebaseWithoutCountingPausedOrResetConsumption() {
        val kernel = BatteryConsumptionKernel()
        kernel.sample(0.0)
        kernel.sample(1.0, energy = 601.0)
        val reset = kernel.sample(2.0, energy = 2.0).metric("controllerEnergyWh")
        assertTrue(reset.baseline)
        assertEquals(1.0, reset.endValue)
        assertEquals(2.0, kernel.sample(3.0, energy = 3.0).metric("controllerEnergyWh").endValue)
        assertTrue(kernel.sample(4.0, energy = 100.0, active = null).isEmpty())
        assertTrue(kernel.sample(5.0, energy = 110.0, active = 1).all { it.baseline })
        assertEquals(3.0, kernel.sample(6.0, energy = 111.0, active = 1).metric("controllerEnergyWh").endValue)
    }

    @Test
    fun countersSpanLinkLossesShorterThanTheHoldWhileCalculationRebases() {
        val kernel = BatteryConsumptionKernel()
        kernel.sample(0.0)
        val reconnected = kernel.sample(5.5, charge = 12.1, energy = 605.0, continuity = "controller/connection-2")
        val energy = reconnected.metric("controllerEnergyWh")
        assertFalse(energy.baseline)
        assertTrue(energy.indivisible)
        assertEquals(0L, energy.startUs)
        assertEquals(5.0, energy.endValue)
        assertEquals(0, energy.segment)
        assertEquals(0.1, reconnected.metric("controllerChargeAh").endValue, 1e-12)
        assertTrue(reconnected.metric("calculatedEnergyWh").baseline)
        assertTrue(reconnected.metric("calculatedChargeAh").baseline)
        assertEquals(5.0, energy.range(0, 5_500_000).amount)
        assertTrue(energy.range(1_000_000, 5_500_000).unresolvedBoundary)
        val held = kernel.sample(11.5, energy = 610.0, continuity = "controller/connection-2")
        assertTrue(held.metric("controllerEnergyWh").baseline)
        assertEquals(5.0, held.metric("controllerEnergyWh").endValue)
        val reset = kernel.sample(12.0, energy = 609.0, continuity = "controller/connection-2")
        assertTrue(reset.metric("controllerEnergyWh").baseline)
        val jump = kernel.sample(14.0, energy = 700.0, continuity = "controller/connection-3")
        assertTrue(jump.metric("controllerEnergyWh").baseline)
        assertEquals(5.0, jump.metric("controllerEnergyWh").endValue)
    }

    @Test
    fun missingInputsOnlyBreakTheirOwnMethodsAndCannotManufactureZeros() {
        val kernel = BatteryConsumptionKernel()
        assertTrue(kernel.sample(0.0, charge = null, energy = null, current = null, voltage = null).isEmpty())
        assertEquals(2, kernel.sample(1.0, charge = null, voltage = null).size)
        val third = kernel.sample(2.0, charge = 20.0, energy = 601.0, voltage = 50.0)
        assertTrue(third.metric("controllerChargeAh").baseline)
        assertTrue(third.metric("calculatedEnergyWh").baseline)
        assertFalse(third.metric("controllerEnergyWh").baseline)
        assertFalse(third.metric("calculatedChargeAh").baseline)
    }

    @Test
    fun positiveIntegralAndClippingUseZeroCrossingTriangle() {
        val kernel = BatteryConsumptionKernel()
        kernel.sample(0.0, current = -36.0)
        val interval = kernel.sample(2.0, current = 36.0).metric("calculatedChargeAh")
        assertEquals(0.005, interval.endValue, 1e-12)
        assertEquals(0.0, interval.range(0, 1_000_000).amount, 1e-12)
        assertEquals(0.00125, interval.range(1_000_000, 1_500_000).amount, 1e-12)
        assertEquals(0.00375, interval.range(1_500_000, 2_000_000).amount, 1e-12)
        val falling = BatteryConsumptionKernel()
        falling.sample(0.0, current = 36.0)
        assertEquals(0.005, falling.sample(2.0, current = -36.0).metric("calculatedChargeAh").endValue, 1e-12)
    }

    @Test
    fun counterIntervalsAreIndivisibleAndInitialZeroIsAvailable() {
        val kernel = BatteryConsumptionKernel()
        val baseline = kernel.sample(0.0).metric("controllerEnergyWh")
        val zero = BatteryConsumptionReducer("controllerEnergyWh", 0, 0)
        zero.append(baseline)
        assertTrue(zero.result(0).available)
        assertFalse(zero.result(0).partial)
        val interval = kernel.sample(2.0, energy = 604.0).metric("controllerEnergyWh")
        val clipped = interval.range(1_000_000, 2_000_000)
        assertEquals(0.0, clipped.amount)
        assertTrue(clipped.unresolvedBoundary && clipped.partial && clipped.available)
        val complete = interval.range(0, 2_000_000)
        assertEquals(4.0, complete.amount)
        assertEquals(2_000_000L, complete.coveredUs)
        assertFalse(complete.partial)
    }

    @Test
    fun checkpointPageBoundariesPreserveExactResultsAndStayBounded() {
        val uninterrupted = BatteryConsumptionKernel()
        var paged = BatteryConsumptionKernel()
        repeat(2000) { index ->
            val expected = uninterrupted.sample(index / 8.0, 100.0 + index / 10000.0, 1000.0 + index / 100.0)
            val actual = paged.sample(index / 8.0, 100.0 + index / 10000.0, 1000.0 + index / 100.0)
            assertEquals(expected, actual)
            if (index % 13 == 0) paged = BatteryConsumptionKernel(paged.checkpoint())
        }
        assertEquals(uninterrupted.checkpoint(), paged.checkpoint())
        assertTrue(paged.checkpoint().length < 2000)
    }

    @Test
    fun duplicateAndOutOfOrderSamplesBreakContinuity() {
        val kernel = BatteryConsumptionKernel()
        kernel.sample(1.0)
        assertTrue(kernel.sample(1.0).isEmpty())
        assertTrue(kernel.sample(2.0).all { it.baseline })
        assertTrue(kernel.sample(1.5).isEmpty())
        assertTrue(kernel.sample(1.6).isEmpty())
        val resumed = BatteryConsumptionKernel(kernel.checkpoint())
        assertTrue(resumed.sample(1.9).isEmpty())
        assertTrue(resumed.sample(2.5).all { it.baseline })
    }

    @Test
    fun unrepresentableCounterDoesNotBecomeAnAvailableZero() {
        val kernel = BatteryConsumptionKernel()
        val rows = kernel.sample(0.0, charge = 1e39, energy = 1e39)
        assertEquals(setOf("calculatedChargeAh", "calculatedEnergyWh"), rows.map { it.metric }.toSet())
        assertTrue(
            kernel
                .sample(1.0, charge = 2.0, energy = 3.0)
                .filter { it.metric.startsWith("controller") }
                .all { it.baseline }
        )
    }

    @Test
    fun integrationGapBoundaryAndPartialCoverage() {
        val kernel = BatteryConsumptionKernel()
        kernel.sample(0.0)
        val edge = kernel.sample(2.5).metric("calculatedChargeAh")
        assertFalse(edge.baseline)
        val reducer = BatteryConsumptionReducer("calculatedChargeAh", 0, 5_000_001)
        reducer.append(edge)
        reducer.append(kernel.sample(5.000001).metric("calculatedChargeAh"))
        val result = reducer.result(5_000_001)
        assertEquals(2.5, result.coveredSeconds)
        assertEquals(0.025, result.amount, 1e-12)
        assertTrue(result.available && result.partial)
    }

    @Test
    fun preferenceFreezeAndDerivedMetricBoundary() {
        val changed = Preferences.defaults().withBatteryConsumptionSource("calculated")
        val restored = Preferences.decode(Preferences.encode(changed))
        assertEquals("calculated", restored.batteryConsumptionSource)
        assertEquals(
            "calculated",
            restored.withSelection(MonitorPresetId.battery, null, MonitorRange.all).batteryConsumptionSource,
        )
        val frozen = RideOptions.resolve(restored.startOptions(), PlatformCapabilities.android(false))
        assertEquals("calculated", frozen.batteryConsumptionSource)
        assertFailsWith<IllegalArgumentException> { changed.withBatteryConsumptionSource("auto") }
        assertFailsWith<IllegalArgumentException> {
            Preferences.decode(Preferences.encode(changed).replace("\"batteryConsumptionSource\":\"calculated\",", ""))
        }
        BatteryConsumptionPolicy.metrics.forEach {
            assertEquals(InspectionChannel.consumption, InspectionMetrics.channel(it))
            assertTrue(InspectionMetrics.isProjected(it))
            assertFalse(TelemetryMetric.entries.any { metric -> metric.name == it })
        }
    }
}
