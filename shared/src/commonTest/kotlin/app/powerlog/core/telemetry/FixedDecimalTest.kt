package app.powerlog.core.telemetry

import app.powerlog.core.analytics.Wide
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.*

private val decimals = 0..6
private val scales = doubleArrayOf(1.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6)
private const val fastLimit = 4503599627370496.0

private fun readouts(random: Random, count: Int): List<Double> = buildList {
    repeat(count * 4) { add(random.nextDouble(1.0, 10.0) * 10.0.pow(random.nextInt(-9, 10))) }
    repeat(count * 4) { add(random.nextLong(0, 1_000_000_000) / 10.0.pow(random.nextInt(0, 9))) }
    repeat(count) { add(random.nextLong(0, 1L shl 50) / 2.0.pow(random.nextInt(0, 60))) }
    repeat(count * 2) {
        val places = random.nextInt(0, 7)
        val tie = (2 * random.nextLong(0, 100_000_000) + 1) / (2 * 10.0.pow(places))
        for (step in -3L..3L) add(Double.fromBits(tie.toBits() + step))
    }
    repeat(count) { add((2 * random.nextLong(0, 1_000_000) + 1) / 2.0.pow(random.nextInt(1, 12))) }
}
    .flatMap { listOf(it, -it) }

private fun extremes(random: Random, count: Int): List<Double> = buildList {
    addAll(
        listOf(
            0.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.MIN_VALUE,
            2.2250738585072014e-308,
            Double.MAX_VALUE,
            0.5,
            2.5,
            0.005,
            1.005,
            48.15,
            48.25,
            299.99999999999994,
            999999.9999995,
            1125899906842623.5,
            2251799813685247.5,
            4503599627370495.5,
            4503599627370496.0,
            9007199254740993.0,
            999999999999999999999.0,
            1e21,
            1e22,
        )
    )
    repeat(count) { add(Double.fromBits(random.nextLong())) }
    repeat(count * 4) { add(random.nextDouble(1e15, 1e21)) }
}
    .flatMap { listOf(it, -it) }

// Rounds m × 2^e × 10^d half up in 128-bit integers; valid while the scaled magnitude stays below 2^52.
private fun integerFixed(value: Double, places: Int): String {
    val bits = abs(value).toBits()
    val exponent = (bits ushr 52).toInt()
    val significand = (bits and 0x000fffffffffffffL) or if (exponent == 0) 0L else 1L shl 52
    val power = (if (exponent == 0) -1074 else exponent - 1075) + places
    var five = 1UL
    repeat(places) { five *= 5UL }
    val product = Wide.product(significand.toULong(), five)
    val rounded =
        when {
            power >= 0 -> product.shiftLeft(power)
            power < -127 -> Wide.ZERO
            else -> product.plus(Wide.of(1UL).shiftLeft(-power - 1)).shiftRight(-power)
        }
    val digits = rounded.low.toLong().toString().padStart(places + 1, '0')
    val text = if (places == 0) digits else digits.dropLast(places) + "." + digits.takeLast(places)
    return (if (value < 0) "-" else "") + text
}

private fun assertMatches(values: List<Double>, reference: (Double, Int) -> String) {
    for (value in values) {
        for (places in decimals) {
            val expected = reference(value, places)
            MetricCatalog.fastFixed(value, places)?.let { assertEquals(expected, it, "${value.toBits()} at $places") }
            assertEquals(expected, MetricCatalog.fixed(value, places), "${value.toBits()} at $places")
        }
    }
}

class FixedDecimalTest {
    @Test
    fun fastFormattingMatchesTheExactExpansion() =
        assertMatches(readouts(Random(20261022), 30) + extremes(Random(20261023), 2), MetricCatalog::exactFixed)

    @Test
    fun negativeZeroAndNegativeValuesThatRoundToZeroKeepTheirSigns() {
        assertEquals("0.00", MetricCatalog.fixed(-0.0, 2))
        assertEquals("-0.00", MetricCatalog.fixed(-0.001, 2))
        assertEquals("-0", MetricCatalog.fixed(-0.4, 0))
        assertEquals("-1", MetricCatalog.fixed(-0.5, 0))
        assertEquals("1.00", MetricCatalog.fixed(1.005, 2))
        assertEquals("48.1", MetricCatalog.fixed(48.15, 1))
        assertEquals("48.3", MetricCatalog.fixed(48.25, 1))
    }

    @Test
    fun onlyNonFiniteValuesAndScaledMagnitudesFromTwoToThe52ndUseTheExactExpansion() {
        var exact = 0
        for (value in readouts(Random(20261024), 40) + extremes(Random(20261025), 40)) {
            for (places in decimals) {
                val fast = MetricCatalog.fastFixed(value, places) != null
                assertEquals(abs(value) * scales[places] < fastLimit, fast, "${value.toBits()} at $places")
                if (!fast) exact++
            }
        }
        assertTrue(exact > 0)
    }
}

class FixedDecimalVolumeTest {
    @Test
    fun fastFormattingMatchesTheExactExpansion() =
        assertMatches(readouts(Random(20261032), 150) + extremes(Random(20261033), 6), MetricCatalog::exactFixed)

    @Test
    fun fastFormattingMatchesAnIntegerExpansionForMillionsOfValues() {
        val values = readouts(Random(20261034), 25_000)
        assertTrue(values.size > 1_000_000)
        for ((index, value) in values.withIndex()) {
            for (places in intArrayOf(index % 7, index / 7 % 7)) {
                val context = "${value.toBits()} at $places"
                val fast = MetricCatalog.fastFixed(value, places)
                if (fast == null) {
                    assertEquals(MetricCatalog.exactFixed(value, places), MetricCatalog.fixed(value, places), context)
                    continue
                }
                assertEquals(integerFixed(value, places), fast, context)
                if (index % 101 == 0) assertEquals(MetricCatalog.exactFixed(value, places), fast, context)
            }
        }
    }
}
