package app.powerlog.core.protocol

import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private object ReferenceNumber {
    fun parse(token: String): Double {
        val negative = token.startsWith('-')
        val unsigned = if (negative) token.substring(1) else token
        val parts = unsigned.split('e', 'E')
        val exponent = if (parts.size == 2) parts[1].toInt() else 0
        val significand = parts[0]
        val point = significand.indexOf('.')
        val fraction = if (point < 0) 0 else significand.length - point - 1
        val digits = significand.replace(".", "").trimStart('0')
        if (digits.isEmpty()) return if (negative) -0.0 else 0.0
        val power = exponent - fraction
        val numerator = Natural()
        for (digit in digits) numerator.multiply(10).add(digit - '0')
        val denominator = Natural(1)
        if (power >= 0) repeat(power) { numerator.multiply(10) } else repeat(-power) { denominator.multiply(10) }
        var binaryExponent = numerator.bits - denominator.bits
        val comparison =
            if (binaryExponent >= 0) numerator.compareTo(denominator.copy().shift(binaryExponent))
            else numerator.copy().shift(-binaryExponent).compareTo(denominator)
        if (comparison < 0) binaryExponent--
        checkValue(binaryExponent <= 1023)
        val shift = if (binaryExponent < -1022) 1074 else 52 - binaryExponent
        val dividend = numerator.copy()
        val divisor = denominator.copy()
        if (shift >= 0) dividend.shift(shift) else divisor.shift(-shift)
        var quotient = 0L
        for (bit in (dividend.bits - divisor.bits).coerceAtLeast(0) downTo 0) {
            val subtract = divisor.copy().shift(bit)
            if (dividend >= subtract) {
                dividend.subtract(subtract)
                quotient = quotient or (1L shl bit)
            }
        }
        val midpoint = dividend.copy().multiply(2).compareTo(divisor)
        if (midpoint > 0 || midpoint == 0 && quotient and 1L != 0L) quotient++
        checkValue(quotient != 0L)
        var bits: Long
        if (binaryExponent < -1022) bits = quotient
        else {
            if (quotient == 1L shl 53) {
                quotient = quotient ushr 1
                binaryExponent++
            }
            checkValue(binaryExponent <= 1023)
            bits = ((binaryExponent + 1023L) shl 52) or (quotient and 0x000fffffffffffffL)
        }
        if (negative) bits = bits or Long.MIN_VALUE
        return Double.fromBits(bits)
    }

    fun encode(value: Double): String {
        if (value == 0.0) return "0"
        val raw = value.toBits()
        val absolute = raw and Long.MAX_VALUE
        val exponent = ((absolute ushr 52) and 2047).toInt()
        val mantissa = (absolute and 0x000fffffffffffffL) or if (exponent == 0) 0L else 1L shl 52
        val binaryPower = if (exponent == 0) -1074 else exponent - 1075
        val integer = Natural(mantissa)
        var decimalPower = 0
        if (binaryPower >= 0) integer.shift(binaryPower)
        else {
            repeat(-binaryPower) { integer.multiply(5) }
            decimalPower = binaryPower
        }
        val exact = integer.decimal()
        for (precision in 1..17) {
            val take = minOf(precision, exact.length)
            val lower = exact.substring(0, take).toLong()
            val remainder = exact.substring(take)
            val upperFirst =
                remainder.isNotEmpty() &&
                    (remainder[0] > '5' ||
                        remainder[0] == '5' && (remainder.drop(1).any { it != '0' } || lower and 1L != 0L))
            val candidates = if (upperFirst) longArrayOf(lower + 1, lower) else longArrayOf(lower, lower + 1)
            val power = decimalPower + exact.length - take
            val coefficient =
                candidates
                    .filter { candidate ->
                        try {
                            parse(candidate.toString() + "e" + power).toBits() == absolute
                        } catch (_: ProtocolException) {
                            false
                        }
                    }
                    .minByOrNull { it.toString().trimEnd('0').length } ?: continue
            val digits = coefficient.toString().trimEnd('0')
            val adjustedPower = power + coefficient.toString().length - digits.length
            val order = digits.length - 1 + adjustedPower
            val output =
                if (order in -6..20) {
                    val point = digits.length + adjustedPower
                    when {
                        point <= 0 -> "0." + "0".repeat(-point) + digits
                        point >= digits.length -> digits + "0".repeat(point - digits.length)
                        else -> digits.substring(0, point) + "." + digits.substring(point)
                    }
                } else digits.take(1) + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" + order
            return (if (raw < 0) "-" else "") + output
        }
        error("No canonical representation")
    }
}

private fun values(random: Random, scale: Int): List<Double> = buildList {
    addAll(
        listOf(
            0.1,
            0.2,
            0.3,
            1.0 / 3,
            2.0 / 3,
            0.000001,
            0.0000001,
            1e15,
            1e16,
            1e21,
            1e22,
            1e23,
            123456789.123,
            9007199254740991.0,
            9007199254740992.0,
            9007199254740994.0,
            4503599627370495.5,
            Double.MAX_VALUE,
            Double.MIN_VALUE,
            2.2250738585072014e-308,
            1791523588.64,
            0.3674095,
            299.99999999999994,
            999999999999999.9,
            99999999999999.98,
            0.09999999999999999,
            1e-22,
            1e-23,
        )
    )
    repeat(150 / scale) { add(Double.fromBits(random.nextLong())) }
    repeat(600 / scale) { add(random.nextLong(-(1L shl 53), (1L shl 53) + 1).toDouble()) }
    repeat(600 / scale) { add(random.nextLong(-100000, 100000).toDouble()) }
    repeat(1200 / scale) { add(random.nextLong(0, 100_000_000) / 10.0.pow(random.nextInt(0, 12))) }
    repeat(600 / scale) { add(random.nextDouble(0.0, 5000.0)) }
    repeat(300 / scale) { add(random.nextDouble(-1e-6, 1e-6)) }
}
    .filter { it.isFinite() }
    .flatMap { listOf(it, -it) }

private fun decimals(random: Random, count: Int): List<Double> = buildList {
    repeat(count) {
        val digits = buildString {
            append('1' + random.nextInt(0, 9))
            repeat(random.nextInt(0, 17)) { append('0' + random.nextInt(0, 10)) }
        }
        val decimal = ReferenceNumber.parse(digits + "e" + random.nextInt(-30, 26))
        addAll(listOf(decimal, Double.fromBits(decimal.toBits() - 1), Double.fromBits(decimal.toBits() + 1)))
    }
}
    .flatMap { listOf(it, -it) }

private fun assertCanonical(values: List<Double>) {
    for (value in values) {
        assertEquals(ReferenceNumber.encode(value), CanonicalNumber.encode(value), value.toBits().toString(16))
        if (value != 0.0)
            assertEquals(value.toBits(), CanonicalNumber.parse(CanonicalNumber.encode(value), false).toBits())
    }
}

class CanonicalNumberTest {
    @Test fun encodeMatchesTheExactShortestPrinter() = assertCanonical(values(Random(20261009), 10))

    @Test
    fun encodeMatchesTheExactShortestPrinterAroundShortDecimals() = assertCanonical(decimals(Random(20261011), 60))

    @Test
    fun parseMatchesTheExactReader() {
        val random = Random(20261010)
        repeat(4000) {
            val digits = buildString {
                append('1' + random.nextInt(0, 9))
                repeat(random.nextInt(0, 19)) { append('0' + random.nextInt(0, 10)) }
            }
            val point = random.nextInt(0, digits.length + 1)
            val significand =
                if (point == digits.length) digits else digits.substring(0, point) + "." + digits.substring(point)
            val exponent = random.nextInt(-40, 41)
            val token =
                (if (random.nextBoolean()) "-" else "") +
                    (if (point == 0) "0" else "") +
                    significand +
                    (if (exponent == 0) "" else "e$exponent")
            assertEquals(ReferenceNumber.parse(token).toBits(), CanonicalNumber.parse(token, false).toBits(), token)
        }
    }
}

class CanonicalNumberVolumeTest {
    @Test fun encodeMatchesTheExactShortestPrinter() = assertCanonical(values(Random(20261009), 1))

    @Test
    fun encodeMatchesTheExactShortestPrinterAroundShortDecimals() = assertCanonical(decimals(Random(20261011), 600))
}
