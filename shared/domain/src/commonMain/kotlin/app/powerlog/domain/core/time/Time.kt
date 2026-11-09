package app.powerlog.domain.core.time

import app.powerlog.domain.analytics.FixedPoint
import kotlinx.serialization.Serializable

public data class ClockReading(public val monoUs: Long, public val utcUs: Long)

@Serializable public data class RideTiming(public val utcUs: Long, public val elapsedUs: Long, public val timerUs: Long)

@Serializable public data class TimeRange(public val startUs: Long, public val endUs: Long)

public data class UtcRange(public val startUtcUs: Long, public val endUtcUs: Long)

public object Utc {
    private val grammar =
        Regex(
            "([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})T([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})(?:\\.([0-9]{1,9}))?(Z|\\+00:00)"
        )

    private const val FIRST_SECOND = 946_684_800L
    private const val END_SECOND = 4_102_444_800L

    public fun parseUs(text: String): Long? {
        val values = grammar.matchEntire(text)?.groupValues ?: return null
        val year = values[1].toInt()
        val month = values[2].toInt()
        val day = values[3].toInt()
        val hour = values[4].toInt()
        val minute = values[5].toInt()
        val second = values[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..24 || minute !in 0..59 || second !in 0..59) return null
        val days = daysBeforeYear(year) - daysBeforeYear(1970) + daysBeforeMonth(month, leap(year)) + day - 1
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second
        if (seconds < FIRST_SECOND || seconds >= END_SECOND) return null
        return seconds * 1_000_000L + values[7].take(6).padEnd(6, '0').toLong()
    }

    private fun leap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun daysBeforeYear(year: Int): Long {
        val previous = year.toLong() - 1
        return 365 * previous + previous.floorDiv(4) - previous.floorDiv(100) + previous.floorDiv(400)
    }

    private fun daysBeforeMonth(month: Int, leap: Boolean): Int {
        val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var days = 0
        for (index in 0 until month - 1) days += lengths[index]
        return days
    }
}

public object Micros {
    private val decimal = Regex("(-?)([0-9]+)(?:\\.([0-9]+))?")

    public fun of(seconds: Double): Long = FixedPoint.timeUs(seconds)

    public fun parse(text: String): Long? {
        val (sign, whole, fraction) = decimal.matchEntire(text)?.destructured ?: return null
        val seconds = whole.toLongOrNull() ?: return null
        val micros = fraction.take(6).padEnd(6, '0').toLong()
        if (seconds > (Long.MAX_VALUE - micros) / 1_000_000) return null
        val magnitude = seconds * 1_000_000 + micros
        return if (sign.isEmpty()) magnitude else -magnitude
    }
}
