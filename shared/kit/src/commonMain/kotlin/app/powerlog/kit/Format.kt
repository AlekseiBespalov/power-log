package app.powerlog.kit

import app.powerlog.domain.analytics.FixedPoint
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem

public object Format {
    internal const val MISSING: String = "—"
    private const val MINUS = "−"
    private val genericNames = setOf("cycmotor", "cyc controller", "cyc bike")

    public fun metric(metric: MetricId, value: Double?, units: UnitSystem, temperature: TemperatureUnit): String {
        if (value == null || !value.isFinite()) return MISSING
        val unit = Units.display(metric, units, temperature)
        val shown = value * unit.factor
        return DecimalText.fixed(if (unit.offset == 0.0) shown else shown + unit.offset, unit.decimals)
    }

    public fun exact(metric: MetricId, value: Double, units: UnitSystem, temperature: TemperatureUnit): String {
        val conversion = Units.conversion(metric, units, temperature)
        val factor = conversion.exactFactor ?: return metric(metric, value, units, temperature)
        val quantum = MetricCatalog.of(metric).quantum
        val quanta = quantize(value, quantum) ?: return metric(metric, value, units, temperature)
        return DecimalText.product(quanta, quantum, factor, conversion.display.decimals)
    }

    public fun difference(
        metric: MetricId,
        a: Double?,
        b: Double?,
        units: UnitSystem,
        temperature: TemperatureUnit,
    ): String {
        val definition = MetricCatalog.of(metric)
        if (a == null || b == null || !definition.allowsMean) return MISSING
        val first = quantize(a, definition.quantum) ?: return MISSING
        val second = quantize(b, definition.quantum) ?: return MISSING
        val delta = second - first
        if ((second xor first) and (second xor delta) < 0) return MISSING
        val conversion = Units.conversion(metric, units, temperature)
        val decimals = conversion.display.decimals
        val text =
            conversion.exactFactor?.let { DecimalText.product(delta, definition.quantum, it, decimals) }
                ?: DecimalText.fixed(delta.toDouble() / definition.quantum * conversion.display.factor, decimals)
        return (if (delta > 0) "+" else "") + text
    }

    public fun duration(us: Long?): String {
        if (us == null) return MISSING
        val seconds = if (us > 0) us / 1_000_000 else 0
        val hours = seconds / 3_600
        return (if (hours > 0) "$hours:" else "") + two(seconds / 60 % 60) + ":" + two(seconds % 60)
    }

    public fun elapsedPrecise(us: Long?): String {
        if (us == null) return MISSING
        val magnitude = if (us < 0) 0UL - us.toULong() else us.toULong()
        val milliseconds = ((magnitude + 500UL) / 1_000UL).toLong()
        val seconds = milliseconds / 1_000
        val hours = seconds / 3_600
        return (if (us < 0) MINUS else "") +
            (if (hours > 0) "$hours:" else "") +
            two(seconds / 60 % 60) +
            ":" +
            two(seconds % 60) +
            "." +
            (milliseconds % 1_000).toString().padStart(3, '0')
    }

    public fun distance(distanceM: Double?, units: UnitSystem): String =
        if (distanceM == null || distanceM < 0) MISSING
        else metric(MetricId.distanceMeters, distanceM, units, TemperatureUnit.celsius)

    public fun decimal(value: Double?, decimals: Int): String =
        if (value == null || !value.isFinite()) MISSING else DecimalText.fixed(value, decimals)

    public fun isoTimestamp(utcUs: Long): String {
        val milliseconds = utcUs.floorDiv(1_000L)
        val days = milliseconds.floorDiv(86_400_000L)
        val ofDay = milliseconds.mod(86_400_000L)
        val shifted = days + 719_468
        val era = shifted.floorDiv(146_097L)
        val dayOfEra = shifted - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
        val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        val yearText =
            if (year in 0L..9_999L) year.toString().padStart(4, '0')
            else (if (year < 0) "-" else "+") + kotlin.math.abs(year).toString().padStart(6, '0')
        return yearText +
            "-" +
            two(month) +
            "-" +
            two(day) +
            "T" +
            two(ofDay / 3_600_000) +
            ":" +
            two(ofDay / 60_000 % 60) +
            ":" +
            two(ofDay / 1_000 % 60) +
            "." +
            (ofDay % 1_000).toString().padStart(3, '0') +
            "Z"
    }

    public fun bikeName(model: String?, advertisedName: String?, bikeId: BikeId?): String {
        val name =
            when {
                !model.isNullOrEmpty() -> "CYC $model"
                advertisedName.isNullOrEmpty() || advertisedName.lowercase() in genericNames -> "CYC bike"
                else -> advertisedName
            }
        val id = bikeId?.value
        if (id.isNullOrEmpty()) return name
        return "$name · ${tag(id)}"
    }

    private fun tag(id: String): String {
        var hash = 2_166_136_261.toInt()
        val lower = id.lowercase()
        var i = 0
        while (i < lower.length) {
            hash = (hash xor lower[i].code) * 16_777_619
            i += if (lower[i].isHighSurrogate() && i + 1 < lower.length && lower[i + 1].isLowSurrogate()) 2 else 1
        }
        return hash.toUInt().toString(16).padStart(8, '0').takeLast(6).uppercase()
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    private fun quantize(value: Double, quantum: Long): Long? =
        if (!value.isFinite()) null
        else
            try {
                FixedPoint.quantize(value, quantum)
            } catch (_: ArithmeticException) {
                null
            }
}
