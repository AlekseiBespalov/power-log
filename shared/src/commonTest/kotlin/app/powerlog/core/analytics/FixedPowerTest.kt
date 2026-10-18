package app.powerlog.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FixedPowerTest {
    @Test
    fun operandFinalizationUsesOneDivision() {
        assertEquals(
            (9007199254740993L.toDouble() / 3.0).toBits(),
            FixedPoint.divide(9007199254740993L, 3).toBits(),
        )
        assertEquals(9007199254740992.0.toBits(), FixedPoint.divide(9007199254740993L, 1).toBits())
    }

    @Test
    fun productionEdgesAndBarriers() {
        val result =
            FixedPowerKernel.integrate(
                longArrayOf(0, 1000000, 2000000, 3000000, 4000000, 6500000, 9000001),
                longArrayOf(100000, 200000, -1, 300000, 300000, 100000, 200000),
                intArrayOf(0, 0, 0, 0, 1, 1, 1),
            )
        assertEquals(FixedPowerIntegration(3500000, 650000000000, 300000), result)
        assertEquals(650.0, result.integral)
        assertEquals(650.0 / 3.5, result.average)
    }

    @Test
    fun ownedPartialMergesAreAssociative() {
        val a = FixedPowerIntegration(1, 9007199254740993L, 100000)
        val b = FixedPowerIntegration(2, 3, 300000)
        val c = FixedPowerIntegration(3, 5, 200000)
        assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
        val times = longArrayOf(0, 125000, 250000, 375000)
        val values = longArrayOf(100000, 200000, 300000, 400000)
        val whole = FixedPowerKernel.integrate(times, values, IntArray(4))
        val first =
            FixedPowerKernel.integrate(
                times.copyOfRange(0, 3),
                values.copyOfRange(0, 3),
                IntArray(3),
            )
        val last =
            FixedPowerKernel.integrate(
                times.copyOfRange(2, 4),
                values.copyOfRange(2, 4),
                IntArray(2),
            )
        assertEquals(whole, first.merge(last))
    }

    @Test
    fun eightHourC15MaximumStaysWithinInt64() {
        val count = 230400
        val result =
            FixedPowerKernel.integrate(
                LongArray(count) { it * 125000L },
                LongArray(count) { 32766000L },
                IntArray(count),
            )
        assertEquals(28799875000L, result.coveredUs)
        assertEquals(943656704250000000L, result.integralQUs)
        assertEquals(32766.0, result.average)
    }

    @Test
    fun overflowAndArrayMismatchAreRejected() {
        assertFailsWith<ArithmeticException> {
            FixedPowerIntegration(0, Long.MAX_VALUE, 0).merge(FixedPowerIntegration(0, 1, 0))
        }
        assertFailsWith<ArithmeticException> {
            FixedPowerIntegration(Long.MAX_VALUE, 0, 0).merge(FixedPowerIntegration(1, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            FixedPowerKernel.integrate(longArrayOf(0), longArrayOf(), intArrayOf())
        }
        val count = 240000
        val overflow =
            assertFailsWith<NumericFailure> {
                FixedPowerKernel.integrate(
                    LongArray(count) { it * 2500000L },
                    LongArray(count) { 32766000L },
                    IntArray(count),
                )
            }
        assertEquals(NumericOverflow("humanPowerW", Quantity.integralQUs), overflow.failure)
        assertEquals(
            FixedPowerIntegration(0, 0, 1000),
            FixedPowerKernel.integrate(
                longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
                longArrayOf(1000, 1000),
                IntArray(2),
            ),
        )
    }
}
