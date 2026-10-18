package app.powerlog.core.protocol

data class ProtocolTimestamp(val original: String, val comparisonUs: Long) {
    val queryMicroseconds: Long
        get() = comparisonUs
}

object Timestamps {
    private val grammar =
        Regex(
            "([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})T([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})(?:\\.([0-9]{1,9}))?(Z|\\+00:00)"
        )

    @Throws(ProtocolException::class)
    fun parse(original: String): ProtocolTimestamp {
        checkValue(original.length <= 40)
        val values = grammar.matchEntire(original)?.groupValues ?: invalid()
        val year = values[1].toInt()
        val month = values[2].toInt()
        val day = values[3].toInt()
        val hour = values[4].toInt()
        val minute = values[5].toInt()
        val second = values[6].toInt()
        checkValue(month in 1..12 && day in 1..31 && hour in 0..24 && minute in 0..59 && second in 0..59)
        val days = daysBeforeYear(year) - daysBeforeYear(1970) + monthsBefore(month, leap(year)) + day - 1
        val seconds = days * 86400L + hour * 3600L + minute * 60L + second
        checkValue(seconds >= 946684800L && seconds < 4102444800L)
        val fraction = values[7].take(6).padEnd(6, '0').toLong()
        return ProtocolTimestamp(original, seconds * 1000000 + fraction)
    }

    private fun leap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun daysBeforeYear(year: Int): Long {
        val previous = year.toLong() - 1
        return 365 * previous + floorDiv(previous, 4) - floorDiv(previous, 100) + floorDiv(previous, 400)
    }

    private fun floorDiv(value: Long, divisor: Long) =
        if (value < 0 && value % divisor != 0L) value / divisor - 1 else value / divisor

    private fun monthsBefore(month: Int, leap: Boolean): Int {
        val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var days = 0
        for (i in 0 until month - 1) days += lengths[i]
        return days
    }
}
