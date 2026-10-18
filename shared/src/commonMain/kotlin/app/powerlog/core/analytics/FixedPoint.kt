package app.powerlog.core.analytics

enum class Quantity {
    count,
    sumQ,
    integralQUs,
    coveredUs,
    distanceQ,
    energyQ,
    ascentQ,
    descentQ,
}

data class NumericOverflow(val metric: String, val quantity: Quantity)

class NumericFailure(val failure: NumericOverflow) :
    ArithmeticException("numericOverflow(${failure.metric}, ${failure.quantity})")

sealed class Scalar {
    data class Integer64(val value: Long) : Scalar()

    data class Real64(val value: Double) : Scalar()

    data class Rational(val numerator: Long, val denominator: Long) : Scalar()

    fun real(): Double =
        when (this) {
            is Integer64 -> value.toDouble()
            is Real64 -> value
            is Rational -> FixedPoint.divide(numerator, denominator)
        }
}

internal data class Wide(val high: ULong, val low: ULong) : Comparable<Wide> {
    override fun compareTo(other: Wide): Int =
        if (high != other.high) high.compareTo(other.high) else low.compareTo(other.low)

    fun plus(other: Wide): Wide {
        val bottom = low + other.low
        val top = high + other.high
        if (top < high) throw ArithmeticException("Wide addition")
        val carry = if (bottom < low) 1UL else 0UL
        if (top + carry < top) throw ArithmeticException("Wide carry")
        return Wide(top + carry, bottom)
    }

    fun minus(other: Wide): Wide {
        require(this >= other)
        return Wide(high - other.high - if (low < other.low) 1UL else 0UL, low - other.low)
    }

    fun shiftLeft(bits: Int): Wide {
        require(bits >= 0)
        if (bits == 0) return this
        if (bits >= 128) {
            if (high != 0UL || low != 0UL) throw ArithmeticException("Wide shift")
            return ZERO
        }
        if (bits >= 64) {
            if (high != 0UL || bits != 64 && low shr (128 - bits) != 0UL) throw ArithmeticException("Wide shift")
            return Wide(low shl (bits - 64), 0UL)
        }
        if (high shr (64 - bits) != 0UL) throw ArithmeticException("Wide shift")
        return Wide((high shl bits) or (low shr (64 - bits)), low shl bits)
    }

    fun shiftRight(bits: Int): Wide =
        when {
            bits == 0 -> this
            bits >= 128 -> ZERO
            bits >= 64 -> Wide(0UL, high shr (bits - 64))
            else -> Wide(high shr bits, (low shr bits) or (high shl (64 - bits)))
        }

    fun bit(index: Int): Boolean =
        if (index < 64) low and (1UL shl index) != 0UL else high and (1UL shl (index - 64)) != 0UL

    companion object {
        val ZERO = Wide(0UL, 0UL)

        fun of(value: ULong) = Wide(0UL, value)

        fun product(a: ULong, b: ULong): Wide {
            val mask = 0xffffffffUL
            val a0 = a and mask
            val a1 = a shr 32
            val b0 = b and mask
            val b1 = b shr 32
            val p0 = a0 * b0
            val middle = a1 * b0 + (p0 shr 32)
            val middle2 = (middle and mask) + a0 * b1
            return Wide(a1 * b1 + (middle shr 32) + (middle2 shr 32), (middle2 shl 32) or (p0 and mask))
        }
    }
}

internal fun magnitude(value: Long): ULong = if (value < 0) 0UL - value.toULong() else value.toULong()

object FixedPoint {
    fun add(a: Long, b: Long): Long {
        val sum = a + b
        if ((a xor sum) and (b xor sum) < 0) throw ArithmeticException("Int64 addition")
        return sum
    }

    fun subtract(a: Long, b: Long): Long {
        val difference = a - b
        if ((a xor b) and (a xor difference) < 0) throw ArithmeticException("Int64 difference")
        return difference
    }

    fun product(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0
        if (a == Long.MIN_VALUE && b == -1L || b == Long.MIN_VALUE && a == -1L)
            throw ArithmeticException("Int64 product")
        val product = a * b
        if (product / b != a) throw ArithmeticException("Int64 product")
        return product
    }

    fun divide(numerator: Long, denominator: Long): Double {
        require(denominator > 0)
        // Long.toDouble() rounds correctly on JVM, JS and Native, so the quotient is identical on every target.
        return numerator.toDouble() / denominator.toDouble()
    }

    private fun signed(value: Wide, negative: Boolean): Long {
        val limit = if (negative) 1UL shl 63 else Long.MAX_VALUE.toULong()
        if (value.high != 0UL || value.low > limit) throw ArithmeticException("Int64 result")
        return if (negative) (0UL - value.low).toLong() else value.low.toLong()
    }

    private fun rounded(n: Wide, d: ULong, negative: Boolean): Long {
        require(d > 0UL)
        if (n.high == 0UL) {
            val q = n.low / d
            val r = n.low % d
            val increment = r > d - r || r == d - r && q and 1UL != 0UL
            return signed(Wide.of(q).plus(Wide.of(if (increment) 1UL else 0UL)), negative)
        }
        var remainder = 0UL
        var quotient = Wide.ZERO
        for (index in 127 downTo 0) {
            val carry = remainder shr 63 != 0UL
            remainder = (remainder shl 1) or if (n.bit(index)) 1UL else 0UL
            val taken = carry || remainder >= d
            if (taken) remainder -= d
            if (taken)
                quotient =
                    if (index >= 64) Wide(quotient.high or (1UL shl (index - 64)), quotient.low)
                    else Wide(quotient.high, quotient.low or (1UL shl index))
        }
        if (remainder > d - remainder || remainder == d - remainder && quotient.low and 1UL != 0UL)
            quotient = quotient.plus(Wide.of(1UL))
        return signed(quotient, negative)
    }

    fun halfEven(numerator: Long, denominator: Long): Long {
        require(denominator > 0)
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        val absoluteRemainder = if (remainder < 0) -remainder else remainder
        val increment =
            absoluteRemainder > denominator - absoluteRemainder ||
                absoluteRemainder == denominator - absoluteRemainder && quotient and 1L != 0L
        return if (increment) add(quotient, if (numerator < 0) -1 else 1) else quotient
    }

    fun share(valueQ: Long, overlapUs: Long, durationUs: Long): Long {
        require(overlapUs >= 0 && durationUs > 0 && overlapUs <= durationUs)
        if (overlapUs == 0L) return 0L
        if (overlapUs == durationUs) return valueQ
        if (valueQ <= Long.MAX_VALUE / overlapUs && valueQ >= Long.MIN_VALUE / overlapUs)
            return halfEven(valueQ * overlapUs, durationUs)
        return rounded(Wide.product(magnitude(valueQ), overlapUs.toULong()), durationUs.toULong(), valueQ < 0)
    }

    fun interpolate(v0: Long, v1: Long, offset: Long, duration: Long): Long {
        require(duration > 0 && offset >= 0L && offset <= duration)
        if (offset == 0L) return v0
        if (offset == duration) return v1
        if (v0 == v1) return v0
        val left = duration - offset
        if (
            v0 <= Long.MAX_VALUE / left &&
                v0 >= Long.MIN_VALUE / left &&
                v1 <= Long.MAX_VALUE / offset &&
                v1 >= Long.MIN_VALUE / offset
        ) {
            val a = v0 * left
            val b = v1 * offset
            val sum = a + b
            if ((a xor sum) and (b xor sum) >= 0) return halfEven(sum, duration)
        }
        val a = Wide.product(magnitude(v0), (duration - offset).toULong())
        val b = Wide.product(magnitude(v1), offset.toULong())
        if ((v0 < 0) == (v1 < 0)) return rounded(a.plus(b), duration.toULong(), v0 < 0)
        return if (a >= b) rounded(a.minus(b), duration.toULong(), v0 < 0)
        else rounded(b.minus(a), duration.toULong(), v1 < 0)
    }

    fun trapezoid(v0: Long, v1: Long, durationUs: Long): Long {
        require(durationUs > 0)
        val sum = v0 + v1
        val sumFits = (v0 xor sum) and (v1 xor sum) >= 0
        if (sumFits && sum >= -80000000L && sum <= 80000000L && durationUs <= 10000000L)
            return halfEven(sum * durationUs, 2)
        if (sumFits && sum <= Long.MAX_VALUE / durationUs && sum >= Long.MIN_VALUE / durationUs)
            return halfEven(sum * durationUs, 2)
        val a = Wide.product(magnitude(v0), durationUs.toULong())
        val b = Wide.product(magnitude(v1), durationUs.toULong())
        if ((v0 < 0) == (v1 < 0)) return rounded(a.plus(b), 2UL, v0 < 0)
        return if (a >= b) rounded(a.minus(b), 2UL, v0 < 0) else rounded(b.minus(a), 2UL, v1 < 0)
    }

    fun quantize(value: Scalar, scale: Long): Long {
        require(scale > 0)
        return when (value) {
            is Scalar.Integer64 -> product(value.value, scale)
            is Scalar.Rational -> {
                require(value.denominator > 0)
                rounded(
                    Wide.product(magnitude(value.numerator), scale.toULong()),
                    value.denominator.toULong(),
                    value.numerator < 0,
                )
            }
            is Scalar.Real64 -> quantize(value.value, scale)
        }
    }

    fun quantize(value: Double, scale: Long): Long {
        require(value.isFinite() && scale > 0)
        if (value >= -9223372036854775808.0 && value < 9223372036854775808.0) {
            val integer = value.toLong()
            if (integer.toDouble() == value) return product(integer, scale)
        }
        val bits = value.toBits()
        val exponentBits = ((bits ushr 52) and 2047).toInt()
        val significand = (bits and 0xfffffffffffffL).toULong() or if (exponentBits == 0) 0UL else 1UL shl 52
        val exponent = if (exponentBits == 0) -1074 else exponentBits - 1075
        if (significand <= ULong.MAX_VALUE / scale.toULong() && exponent < 0) {
            val numerator = significand * scale.toULong()
            val shift = -exponent
            if (shift > 64) return 0L
            val quotient = if (shift == 64) 0UL else numerator shr shift
            val remainder = if (shift == 64) numerator else numerator and ((1UL shl shift) - 1UL)
            val half = 1UL shl (shift - 1)
            val rounded = quotient + if (remainder > half || remainder == half && quotient and 1UL != 0UL) 1UL else 0UL
            val limit = if (bits < 0) 1UL shl 63 else Long.MAX_VALUE.toULong()
            if (rounded > limit) throw ArithmeticException("Int64 result")
            return if (bits < 0) (0UL - rounded).toLong() else rounded.toLong()
        }
        val n = Wide.product(significand, scale.toULong())
        if (n == Wide.ZERO) return 0
        if (exponent >= 0) {
            if (exponent >= 128) throw ArithmeticException("Int64 admission")
            return signed(n.shiftLeft(exponent), bits < 0)
        }
        val shift = -exponent
        if (shift > 128) return 0
        var q = n.shiftRight(shift)
        val halfBit = n.bit(shift - 1)
        val remainder = n.minus(q.shiftLeft(shift))
        val exactHalf = if (shift == 128) Wide(1UL shl 63, 0UL) else Wide.of(1UL).shiftLeft(shift - 1)
        if (halfBit && (remainder > exactHalf || q.low and 1UL != 0UL)) q = q.plus(Wide.of(1UL))
        return signed(q, bits < 0)
    }

    fun timeUs(seconds: Double): Long {
        require(seconds.isFinite())
        val value = seconds * 1_000_000.0
        require(value >= -9223372036854775808.0 && value < 9223372036854775808.0)
        val truncated = value.toLong()
        return if (value < truncated.toDouble()) subtract(truncated, 1) else truncated
    }
}

private fun accumulationOverflow(total: Long, amount: ULong, overflow: Boolean): Boolean =
    overflow || amount > Long.MAX_VALUE.toULong() || amount > (Long.MAX_VALUE - total).toULong()

data class SignedSum(
    val positive: Long = 0,
    val negative: Long = 0,
    val positiveOverflow: Boolean = false,
    val negativeOverflow: Boolean = false,
) {
    fun add(value: Long): SignedSum {
        val amount = magnitude(value)
        return if (value < 0) {
            val fails = accumulationOverflow(negative, amount, negativeOverflow)
            copy(negative = if (fails) negative else negative + amount.toLong(), negativeOverflow = fails)
        } else {
            val fails = accumulationOverflow(positive, value.toULong(), positiveOverflow)
            copy(positive = if (fails) positive else positive + value, positiveOverflow = fails)
        }
    }

    fun merge(other: SignedSum): SignedSum {
        val merged = add(other.positive).add(-other.negative)
        return merged.copy(
            positiveOverflow = merged.positiveOverflow || other.positiveOverflow,
            negativeOverflow = merged.negativeOverflow || other.negativeOverflow,
        )
    }

    fun value(metric: String, quantity: Quantity): Long {
        if (positiveOverflow || negativeOverflow) throw NumericFailure(NumericOverflow(metric, quantity))
        return FixedPoint.subtract(positive, negative)
    }
}

internal class CheckedSum(initial: SignedSum = SignedSum()) {
    var positive = initial.positive
        private set

    var negative = initial.negative
        private set

    var positiveOverflow = initial.positiveOverflow
        private set

    var negativeOverflow = initial.negativeOverflow
        private set

    fun add(value: Long) {
        if (value < 0L) {
            val amount = magnitude(value)
            if (accumulationOverflow(negative, amount, negativeOverflow)) {
                negativeOverflow = true
            } else {
                negative += amount.toLong()
            }
        } else if (accumulationOverflow(positive, value.toULong(), positiveOverflow)) {
            positiveOverflow = true
        } else {
            positive += value
        }
    }

    fun merge(other: SignedSum) {
        add(other.positive)
        add(-other.negative)
        positiveOverflow = positiveOverflow || other.positiveOverflow
        negativeOverflow = negativeOverflow || other.negativeOverflow
    }

    fun snapshot() = SignedSum(positive, negative, positiveOverflow, negativeOverflow)

    fun value(metric: String, quantity: Quantity): Long {
        if (positiveOverflow || negativeOverflow) throw NumericFailure(NumericOverflow(metric, quantity))
        return FixedPoint.subtract(positive, negative)
    }
}
