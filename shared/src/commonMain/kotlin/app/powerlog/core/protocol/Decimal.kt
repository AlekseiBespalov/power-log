package app.powerlog.core.protocol

import kotlin.math.floor
import kotlin.math.roundToLong

internal class Natural private constructor(private val limbs: MutableList<Int>) : Comparable<Natural> {
    constructor(value: Long = 0) : this(mutableListOf()) {
        var remaining = value
        do {
            limbs.add((remaining and 65535).toInt())
            remaining = remaining ushr 16
        } while (remaining != 0L)
    }

    fun copy() = Natural(limbs.toMutableList())

    val zero
        get() = limbs.size == 1 && limbs[0] == 0

    val bits: Int
        get() = if (zero) 0 else (limbs.size - 1) * 16 + 32 - limbs.last().countLeadingZeroBits()

    private fun trim() {
        while (limbs.size > 1 && limbs.last() == 0) limbs.removeAt(limbs.lastIndex)
    }

    fun multiply(factor: Int): Natural {
        var carry = 0L
        for (i in limbs.indices) {
            val next = limbs[i].toLong() * factor + carry
            limbs[i] = (next and 65535).toInt()
            carry = next ushr 16
        }
        while (carry > 0) {
            limbs.add((carry and 65535).toInt())
            carry = carry ushr 16
        }
        trim()
        return this
    }

    fun add(value: Int): Natural {
        var carry = value
        var i = 0
        while (carry > 0) {
            if (i == limbs.size) limbs.add(0)
            val sum = limbs[i] + carry
            limbs[i++] = sum and 65535
            carry = sum ushr 16
        }
        return this
    }

    fun shift(count: Int): Natural {
        require(count >= 0)
        if (zero) return this
        repeat(count / 16) { limbs.add(0, 0) }
        if (count % 16 != 0) multiply(1 shl (count % 16))
        return this
    }

    override fun compareTo(other: Natural): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) if (limbs[i] != other.limbs[i]) return limbs[i].compareTo(other.limbs[i])
        return 0
    }

    fun subtract(other: Natural) {
        require(this >= other)
        var borrow = 0
        for (i in limbs.indices) {
            val next = limbs[i] - other.limbs.getOrElse(i) { 0 } - borrow
            limbs[i] = next and 65535
            borrow = if (next < 0) 1 else 0
        }
        trim()
    }

    fun decimal(): String {
        val remaining = copy()
        val output = StringBuilder()
        do {
            var remainder = 0
            for (i in remaining.limbs.indices.reversed()) {
                val value = remainder * 65536 + remaining.limbs[i]
                remaining.limbs[i] = value / 10
                remainder = value % 10
            }
            remaining.trim()
            output.append(('0'.code + remainder).toChar())
        } while (!remaining.zero)
        return output.reverse().toString()
    }
}

object CanonicalNumber {
    private val powersOfTen = DoubleArray(23) { 1.0 }.also { for (i in 1 until it.size) it[i] = it[i - 1] * 10 }

    // Clinger's fast path: a significand of at most 2^53 and a power of ten up to 10^22 are both exact doubles, so a
    // single IEEE multiplication or division rounds the decimal value correctly on every target.
    private fun exactProduct(digits: String, power: Int): Double? {
        if (digits.length > 16 || power !in -22..22) return null
        val significand = digits.toLong()
        if (significand > (1L shl 53)) return null
        return if (power >= 0) significand.toDouble() * powersOfTen[power]
        else significand.toDouble() / powersOfTen[-power]
    }

    internal fun parse(token: String, wireBound: Boolean = true, binary64: ((String) -> Double)? = null): Double {
        checkValue(token.length <= 4096)
        val negative = token.startsWith('-')
        val unsigned = if (negative) token.substring(1) else token
        val parts = unsigned.split('e', 'E')
        checkValue(parts.size <= 2)
        val exponent = if (parts.size == 2) parts[1].toIntOrNull() ?: invalid() else 0
        val significand = parts[0]
        val point = significand.indexOf('.')
        val fraction = if (point < 0) 0 else significand.length - point - 1
        val digits = significand.replace(".", "").trimStart('0')
        if (digits.isEmpty()) return if (negative) -0.0 else 0.0
        val powerLong = exponent.toLong() - fraction
        val order = digits.length.toLong() + powerLong - 1
        checkValue(order in -324..308)
        val power = powerLong.toInt()
        val trimmed = digits.trimEnd('0')
        val integral = power + digits.length - trimmed.length >= 0
        if (wireBound && integral) {
            val integerLength = digits.length.toLong() + power
            checkValue(integerLength <= 16)
            val integer = trimmed + "0".repeat(power + digits.length - trimmed.length)
            checkValue(integer.length < 16 || integer <= "9007199254740991")
        }
        if (binary64 != null) {
            val value = binary64(token)
            checkValue(value.isFinite() && value != 0.0)
            if (wireBound && !integral) checkValue(value >= -1e15 && value <= 1e15)
            return value
        }
        exactProduct(trimmed, power + digits.length - trimmed.length)?.let { magnitude ->
            val value = if (negative) -magnitude else magnitude
            if (wireBound && !integral) checkValue(value >= -1e15 && value <= 1e15)
            return value
        }
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
        val value = Double.fromBits(bits)
        if (wireBound && !integral) checkValue(value >= -1e15 && value <= 1e15)
        return value
    }

    private fun render(digits: String, power: Int): String {
        val order = digits.length - 1 + power
        if (order !in -6..20)
            return digits.take(1) + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" + order
        val point = digits.length + power
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }

    // A coefficient up to 10^15 and 10^scale (scale ≤ 22) are exact doubles, so one division rounds exactly like parse.
    // At such a scale the value's rounding interval is narrower than one coefficient step and lies within a quarter
    // step of the floating product, so the rounded product is the only candidate, and the first scale where it
    // round-trips gives the shortest representation.
    private fun shortDecimal(magnitude: Double): String? {
        for (scale in 1 until powersOfTen.size) {
            val scaled = magnitude * powersOfTen[scale]
            if (scaled >= 1e15) return null
            val coefficient = scaled.roundToLong()
            if (coefficient > 0 && coefficient.toDouble() / powersOfTen[scale] == magnitude)
                return render(coefficient.toString(), -scale)
        }
        return null
    }

    @Throws(ProtocolException::class)
    fun encode(value: Double): String {
        checkValue(value.isFinite())
        if (value == 0.0) return "0"
        val raw = value.toBits()
        val absolute = raw and Long.MAX_VALUE
        val sign = if (raw < 0) "-" else ""
        val magnitude = Double.fromBits(absolute)
        if (magnitude < 9007199254740992.0 && magnitude == floor(magnitude)) return sign + magnitude.toLong().toString()
        shortDecimal(magnitude)?.let {
            return sign + it
        }
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
                            parse(candidate.toString() + "e" + power, false).toBits() == absolute
                        } catch (_: ProtocolException) {
                            false
                        }
                    }
                    .minByOrNull { it.toString().trimEnd('0').length } ?: continue
            val digits = coefficient.toString().trimEnd('0')
            return sign + render(digits, power + coefficient.toString().length - digits.length)
        }
        invalid()
    }
}
