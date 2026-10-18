package app.powerlog.core.analytics

import kotlin.test.*

class NumericReductionTest {
    @Test fun throttleHundredthsSurviveProductionReduction() = hundredths("throttleVoltageV", 0.78)

    @Test fun rawSpeedHundredthsSurviveProductionReduction() = hundredths("speedRaw", 3.77)

    private fun hundredths(metric: String, value: Double) {
        val reducer = MetricReducer(Metrics.policy(metric), AnalysisRange(0, 2_000_000))
        for (n in 0..2) reducer.append(
            MetricReading(
                n * 1_000_000L,
                Original("$n", "$n", "synthetic", n.toDouble(), Scalar.Real64(value)),
                0,
                Continuity(),
            )
        )
        val result = reducer.result()
        assertEquals(value, result.average, metric)
        assertEquals(value, result.statistics.sampleMean, metric)
        assertEquals(value * 2, result.statistics.integral, metric)
        assertEquals((value * 100).toLong() * 2_000_000, result.integralQUs, metric)
    }

    @Test
    fun primitiveReductionKeepsHalfEvenAndWideFallbacks() {
        for ((value, expected) in listOf(0.0625 to 62L, 0.1875 to 188L, 0.3125 to 312L)) {
            assertEquals(expected, FixedPoint.quantize(value, 1000))
            assertEquals(-expected, FixedPoint.quantize(-value, 1000))
        }
        assertEquals(63L, FixedPoint.quantize(Double.fromBits(0.0625.toBits() + 1), 1000))
        assertEquals(62L, FixedPoint.quantize(Double.fromBits(0.0625.toBits() - 1), 1000))
        assertEquals(0L, FixedPoint.quantize(Double.fromBits(1), Long.MAX_VALUE))
        assertEquals(0L, FixedPoint.quantize(-Double.fromBits(1), Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, FixedPoint.quantize(Scalar.Integer64(Long.MAX_VALUE), 1))
        assertFailsWith<ArithmeticException> { FixedPoint.quantize(Scalar.Integer64(Long.MAX_VALUE), 2) }
        for (offset in 0L..8L) {
            assertEquals(FixedPoint.halfEven(5L * offset, 8), FixedPoint.share(5, offset, 8))
            assertEquals(FixedPoint.halfEven(-5L * offset, 8), FixedPoint.share(-5, offset, 8))
            assertEquals(
                FixedPoint.halfEven(2L * (8 - offset) + 5L * offset, 8),
                FixedPoint.interpolate(2, 5, offset, 8),
            )
            assertEquals(
                FixedPoint.halfEven(-2L * (8 - offset) + 5L * offset, 8),
                FixedPoint.interpolate(-2, 5, offset, 8),
            )
        }
        assertEquals(4611686018427387904L, FixedPoint.share(Long.MAX_VALUE, 1, 2))
        assertEquals(-4611686018427387904L, FixedPoint.share(Long.MIN_VALUE, 1, 2))
        assertEquals(4611686018427387904L, FixedPoint.interpolate(0, Long.MAX_VALUE, 1, 2))
        assertEquals(0L, FixedPoint.interpolate(-Long.MAX_VALUE, Long.MAX_VALUE, 1, 2))
        assertEquals(Long.MIN_VALUE, FixedPoint.interpolate(Long.MIN_VALUE, Long.MIN_VALUE, 1, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, FixedPoint.interpolate(Long.MAX_VALUE, Long.MAX_VALUE, 1, Long.MAX_VALUE))
    }
}
