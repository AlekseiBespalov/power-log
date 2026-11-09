package app.powerlog.domain.core.numeric

// The positive triangle or trapezoid of a linearly interpolated rate, in rate-hours.
internal fun positiveArea(first: Double, last: Double, durationUs: Long): Double {
    val rateArea =
        when {
            first <= 0 && last <= 0 -> 0.0
            first >= 0 && last >= 0 -> first / 2 + last / 2
            first > 0 -> first / 2 * (first / (first - last))
            else -> last / 2 * (last / (last - first))
        }
    return rateArea * (durationUs / 3600000000.0)
}
