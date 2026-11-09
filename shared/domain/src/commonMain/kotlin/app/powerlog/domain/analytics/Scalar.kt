package app.powerlog.domain.analytics

import app.powerlog.domain.core.numeric.FixedPoint

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
