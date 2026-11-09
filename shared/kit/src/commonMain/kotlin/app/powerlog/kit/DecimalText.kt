package app.powerlog.kit

import kotlin.math.abs
import kotlin.math.floor

internal object DecimalText {
    private val powersOfTen =
        DoubleArray(23).also { powers ->
            powers[0] = 1.0
            for (i in 1 until powers.size) powers[i] = powers[i - 1] * 10
        }
    private const val FAST_LIMIT = 4_503_599_627_370_496.0
    private const val FIVE_TO_THE_13 = 1_220_703_125L

    const val MAX_DECIMALS = 20

    fun fixed(value: Double, places: Int): String {
        if (!value.isFinite()) return Format.MISSING
        val decimals = places.coerceIn(0, MAX_DECIMALS)
        val sign = if (value < 0) "-" else ""
        val magnitude = abs(value)
        val scaled = magnitude * powersOfTen[decimals]
        // Below 2^52 every whole number plus one half is a double and a power of ten up to 10^22 is exact, so the
        // product rounded once stays on the exact value's side of each half unless it lands on the half itself.
        if (scaled < FAST_LIMIT) {
            val whole = floor(scaled)
            val fraction = scaled - whole
            if (fraction != 0.5)
                return sign + point((whole.toLong() + if (fraction > 0.5) 1 else 0).toString(), decimals)
        }
        return sign + rounded(exactDigits(magnitude), decimals)
    }

    fun product(quanta: Long, quantum: Long, factor: String, places: Int): String {
        val decimals = places.coerceIn(0, MAX_DECIMALS)
        val factorDigits = factor.replace(".", "")
        val factorScale = if ('.' in factor) factor.length - factor.indexOf('.') - 1 else 0
        val magnitude = Digits.of(quanta.toULongMagnitude()).multiply(factorDigits.toLong())
        val text = rounded(Exact(magnitude.text(), factorScale + quantum.toString().length - 1), decimals)
        return (if (quanta < 0) "-" else "") + text
    }

    private fun Long.toULongMagnitude(): ULong = if (this < 0) 0UL - toULong() else toULong()

    private class Exact(val digits: String, val scale: Int)

    private fun exactDigits(magnitude: Double): Exact {
        val bits = magnitude.toBits()
        val exponent = ((bits ushr 52) and 2047).toInt()
        val mantissa = (bits and 0x000fffffffffffffL) or if (exponent == 0) 0L else 1L shl 52
        val power = if (exponent == 0) -1074 else exponent - 1075
        val digits = Digits.of(mantissa.toULong())
        if (power >= 0) {
            repeat(power) { digits.multiply(2) }
            return Exact(digits.text(), 0)
        }
        var fives = -power
        while (fives >= 13) {
            digits.multiply(FIVE_TO_THE_13)
            fives -= 13
        }
        repeat(fives) { digits.multiply(5) }
        return Exact(digits.text(), -power)
    }

    private fun rounded(exact: Exact, decimals: Int): String {
        val raw = exact.digits.trimStart('0').ifEmpty { "0" }
        val keep = raw.length - exact.scale + decimals
        var kept = if (keep > 0) raw.take(keep).padEnd(keep, '0') else "0"
        if (keep >= 0 && keep < raw.length && raw[keep] >= '5') kept = increment(kept)
        return point(kept, decimals)
    }

    private fun point(digits: String, decimals: Int): String {
        val padded = digits.padStart(decimals + 1, '0')
        return if (decimals == 0) padded else padded.dropLast(decimals) + "." + padded.takeLast(decimals)
    }

    private fun increment(value: String): String {
        val digits = value.toCharArray()
        var i = digits.lastIndex
        while (i >= 0 && digits[i] == '9') {
            digits[i] = '0'
            i--
        }
        if (i < 0) return "1" + digits.concatToString()
        digits[i] = digits[i] + 1
        return digits.concatToString()
    }

    private class Digits private constructor(private val limbs: MutableList<Long>) {
        fun multiply(factor: Long): Digits {
            var carry = 0L
            for (i in limbs.indices) {
                val next = limbs[i] * factor + carry
                limbs[i] = next % LIMB
                carry = next / LIMB
            }
            while (carry > 0) {
                limbs.add(carry % LIMB)
                carry /= LIMB
            }
            return this
        }

        fun text(): String =
            limbs.indices.reversed().joinToString("") { i ->
                limbs[i].toString().let { if (i == limbs.lastIndex) it else it.padStart(9, '0') }
            }

        companion object {
            private const val LIMB = 1_000_000_000L

            fun of(value: ULong): Digits {
                val limbs = mutableListOf<Long>()
                var remaining = value
                do {
                    limbs.add((remaining % LIMB.toULong()).toLong())
                    remaining /= LIMB.toULong()
                } while (remaining != 0UL)
                return Digits(limbs)
            }
        }
    }
}
