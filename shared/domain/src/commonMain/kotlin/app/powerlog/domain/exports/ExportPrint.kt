package app.powerlog.domain.exports

import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.Platform

public object ExportPrint {
    public fun timestamp(utcUs: Long): String {
        val millis = utcUs.floorDiv(MICROS_PER_MILLI).mod(MILLIS_PER_SECOND)
        return clock(utcUs) + "." + digits(millis, 3) + "Z"
    }

    public fun healthTimestamp(utcUs: Long, platform: Platform): String {
        val micros = utcUs.mod(MICROS_PER_SECOND)
        val fraction =
            when {
                platform == Platform.ios -> "." + digits(micros, 6) + "000"
                micros == 0L -> ""
                micros % MICROS_PER_MILLI == 0L -> "." + digits(micros / MICROS_PER_MILLI, 3)
                else -> "." + digits(micros, 6)
            }
        return clock(utcUs) + fraction + "Z"
    }

    public fun seconds(us: Long): Double = us / 1e6

    public fun provider(platform: Platform): HealthProviderKind? =
        when (platform) {
            Platform.ios -> HealthProviderKind.appleHealth
            Platform.android -> HealthProviderKind.healthConnect
            Platform.web -> null
        }

    public fun eventProducer(platform: Platform): Producer =
        if (platform == Platform.web) Producer.browser else Producer.phone

    private fun clock(utcUs: Long): String {
        val second = utcUs.floorDiv(MICROS_PER_SECOND)
        val days = second.floorDiv(SECONDS_PER_DAY)
        val time = second.mod(SECONDS_PER_DAY)
        val (year, month, day) = civil(days)
        return digits(year, 4) +
            "-" +
            digits(month, 2) +
            "-" +
            digits(day, 2) +
            "T" +
            digits(time / 3_600, 2) +
            ":" +
            digits(time / 60 % 60, 2) +
            ":" +
            digits(time % 60, 2)
    }

    // Howard Hinnant's days-from-civil inverse: days since 1970-01-01 to the proleptic Gregorian date.
    private fun civil(days: Long): Triple<Long, Long, Long> {
        val z = days + 719_468
        val era = z.floorDiv(146_097)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shifted = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * shifted + 2) / 5 + 1
        val month = if (shifted < 10) shifted + 3 else shifted - 9
        return Triple(yearOfEra + era * 400 + if (month <= 2) 1 else 0, month, day)
    }

    private fun digits(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private const val MICROS_PER_MILLI = 1_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val SECONDS_PER_DAY = 86_400L
}
