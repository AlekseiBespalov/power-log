package app.powerlog.core.telemetry

import app.powerlog.core.analytics.Wide
import app.powerlog.core.protocol.CanonicalNumber
import app.powerlog.core.protocol.Natural
import kotlin.math.abs
import kotlin.math.floor

enum class MetricGroup {
    Rider,
    Battery,
    Motor,
    Route,
    Status,
}

enum class MetricKind {
    step
}

enum class MetricSemantics {
    cumulative,
    counter,
    distance,
    category,
    circular,
    ratio,
}

data class MetricDeclaration(
    val id: String,
    val label: String,
    val shortLabel: String,
    val unit: String,
    val group: MetricGroup,
    val color: String,
    val decimals: Int,
    val gapSeconds: Double,
    val scale: Double = 1.0,
    val kind: MetricKind? = null,
    val zero: Boolean? = null,
    val semantics: MetricSemantics? = null,
    val description: String? = null,
    val plotGapSeconds: Double? = null,
)

object MetricCatalog {
    private val declarations =
        listOf(
            MetricDeclaration(
                "humanPowerW",
                "Rider power",
                "Rider",
                "W",
                MetricGroup.Rider,
                "#ff8735",
                0,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "cadenceRpm",
                "Cadence",
                "Cadence",
                "rpm",
                MetricGroup.Rider,
                "#ad98e4",
                0,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "heartRateBpm",
                "Heart rate",
                "Heart rate",
                "bpm",
                MetricGroup.Rider,
                "#fb7185",
                0,
                15.0,
                1.0,
                null,
                null,
                null,
                plotGapSeconds = 30.0,
            ),
            MetricDeclaration(
                "pedalTorqueNm",
                "Pedal torque",
                "Torque",
                "Nm",
                MetricGroup.Rider,
                "#e9b65a",
                1,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "activeEnergyKcal",
                "Active energy",
                "Active energy",
                "kcal",
                MetricGroup.Rider,
                "#f6b26b",
                0,
                120.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "basalEnergyKcal",
                "Resting energy",
                "Resting energy",
                "kcal",
                MetricGroup.Rider,
                "#b8a184",
                0,
                120.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "batteryVoltageV",
                "Battery voltage",
                "Voltage",
                "V",
                MetricGroup.Battery,
                "#f4cf5b",
                2,
                6.0,
                1.0,
                null,
                null,
                null,
            ),
            MetricDeclaration(
                "batteryCurrentA",
                "Battery current",
                "Battery current",
                "A",
                MetricGroup.Battery,
                "#57c6f5",
                1,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "motorInputPowerW",
                "Battery input power",
                "Battery input",
                "W",
                MetricGroup.Battery,
                "#3d8bfd",
                0,
                6.0,
                1.0,
                null,
                true,
                null,
                description =
                    "Calculated by Power Log as battery voltage × current; electrical input, not mechanical motor output.",
            ),
            MetricDeclaration(
                "consumedAh",
                "Charge counter (controller)",
                "Charge counter (controller)",
                "Ah",
                MetricGroup.Battery,
                "#cbb48f",
                2,
                6.0,
                1.0,
                null,
                true,
                MetricSemantics.counter,
                description = "Original controller counter; may include consumption from earlier rides.",
            ),
            MetricDeclaration(
                "consumedWh",
                "Energy counter (controller)",
                "Energy counter (controller)",
                "Wh",
                MetricGroup.Battery,
                "#e2ae71",
                1,
                6.0,
                1.0,
                null,
                true,
                MetricSemantics.counter,
                description = "Original controller counter; may include consumption from earlier rides.",
            ),
            MetricDeclaration(
                "motorCurrentA",
                "Motor current",
                "Motor current",
                "A",
                MetricGroup.Motor,
                "#e693c1",
                1,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "motorTempC",
                "Motor temperature",
                "Motor",
                "°C",
                MetricGroup.Motor,
                "#ff9875",
                1,
                6.0,
                1.0,
                null,
                null,
                null,
            ),
            MetricDeclaration(
                "controllerTempC",
                "Controller temperature",
                "Controller",
                "°C",
                MetricGroup.Motor,
                "#6bd4c5",
                1,
                6.0,
                1.0,
                null,
                null,
                null,
            ),
            MetricDeclaration(
                "motorRpm",
                "Motor speed",
                "Motor speed",
                "rpm",
                MetricGroup.Motor,
                "#84b1f5",
                0,
                6.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "throttleVoltageV",
                "Throttle voltage",
                "Throttle",
                "V",
                MetricGroup.Motor,
                "#d3a8e6",
                2,
                6.0,
                1.0,
                null,
                null,
                null,
            ),
            MetricDeclaration(
                "speedMps",
                "GPS speed",
                "GPS",
                "km/h",
                MetricGroup.Route,
                "#91c77a",
                1,
                10.0,
                3.6,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "controllerSpeedMps",
                "Controller speed",
                "Controller",
                "km/h",
                MetricGroup.Route,
                "#8799ac",
                1,
                6.0,
                3.6,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "distanceMeters",
                "Ride distance",
                "Distance",
                "km",
                MetricGroup.Route,
                "#aed195",
                2,
                120.0,
                0.001,
                null,
                true,
                MetricSemantics.distance,
            ),
            MetricDeclaration(
                "gpsDistanceMeters",
                "Distance (GPS)",
                "Distance (GPS)",
                "km",
                MetricGroup.Route,
                "#a9d18e",
                2,
                2.5,
                0.001,
                zero = true,
                semantics = MetricSemantics.distance,
                description = "Calculated by Power Log from accepted phone GPS positions.",
            ),
            MetricDeclaration(
                "controllerDistanceMeters",
                "Distance (controller)",
                "Distance (controller)",
                "km",
                MetricGroup.Route,
                "#6dbab7",
                2,
                2.5,
                0.001,
                zero = true,
                semantics = MetricSemantics.distance,
                description = "Calculated by Power Log from controller speed; not a controller odometer.",
            ),
            MetricDeclaration(
                "controllerEnergyWh",
                "Energy used (controller)",
                "Energy used (controller)",
                "Wh",
                MetricGroup.Battery,
                "#f3c260",
                2,
                6.0,
                1.0,
                zero = true,
                semantics = MetricSemantics.cumulative,
                description = "Ride consumption from changes in the controller energy counter, starting at zero.",
            ),
            MetricDeclaration(
                "controllerChargeAh",
                "Charge used (controller)",
                "Charge used (controller)",
                "Ah",
                MetricGroup.Battery,
                "#e0b36a",
                3,
                6.0,
                1.0,
                zero = true,
                semantics = MetricSemantics.cumulative,
                description = "Ride consumption from changes in the controller charge counter, starting at zero.",
            ),
            MetricDeclaration(
                "calculatedEnergyWh",
                "Energy used (calc)",
                "Energy used (calc)",
                "Wh",
                MetricGroup.Battery,
                "#8dbfdf",
                2,
                6.0,
                1.0,
                zero = true,
                semantics = MetricSemantics.cumulative,
                description =
                    "Calculated by Power Log by integrating positive battery voltage × current during this ride.",
            ),
            MetricDeclaration(
                "calculatedChargeAh",
                "Charge used (calc)",
                "Charge used (calc)",
                "Ah",
                MetricGroup.Battery,
                "#9eafd9",
                3,
                6.0,
                1.0,
                zero = true,
                semantics = MetricSemantics.cumulative,
                description = "Calculated by Power Log by integrating positive battery current during this ride.",
            ),
            MetricDeclaration(
                "batteryWhPerKm",
                "Battery consumption",
                "Battery consumption",
                "Wh/km",
                MetricGroup.Battery,
                "#3d8bfd",
                1,
                6.0,
                zero = true,
                semantics = MetricSemantics.ratio,
                description =
                    "Battery power divided by speed; ride average uses the selected battery energy and distance.",
            ),
            MetricDeclaration(
                "riderWhPerKm",
                "Rider contribution",
                "Rider contribution",
                "Wh/km",
                MetricGroup.Rider,
                "#ff8735",
                1,
                6.0,
                zero = true,
                semantics = MetricSemantics.ratio,
                description =
                    "Rider power divided by speed; ride average uses accumulated pedalling work and distance.",
            ),
            MetricDeclaration(
                "altitudeMeters",
                "Altitude",
                "Altitude",
                "m",
                MetricGroup.Route,
                "#b6bcd0",
                0,
                10.0,
                1.0,
                null,
                null,
                null,
            ),
            MetricDeclaration(
                "horizontalAccuracyM",
                "GPS accuracy",
                "GPS accuracy",
                "m",
                MetricGroup.Route,
                "#94a3b8",
                1,
                10.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "verticalAccuracyM",
                "Altitude accuracy",
                "Altitude accuracy",
                "m",
                MetricGroup.Route,
                "#acb4ce",
                1,
                10.0,
                1.0,
                null,
                true,
                null,
            ),
            MetricDeclaration(
                "courseDegrees",
                "Course",
                "Course",
                "°",
                MetricGroup.Route,
                "#73aaa7",
                0,
                10.0,
                1.0,
                null,
                null,
                MetricSemantics.circular,
            ),
            MetricDeclaration(
                "assistLevel",
                "Assist level",
                "Assist",
                "",
                MetricGroup.Status,
                "#dcad77",
                0,
                6.0,
                1.0,
                MetricKind.step,
                true,
                null,
            ),
            MetricDeclaration(
                "raceMode",
                "Race mode",
                "Race mode",
                "",
                MetricGroup.Status,
                "#ed9c94",
                0,
                6.0,
                1.0,
                MetricKind.step,
                true,
                null,
            ),
            MetricDeclaration(
                "faultCode",
                "Controller fault code",
                "Fault code",
                "",
                MetricGroup.Status,
                "#d7dde5",
                0,
                6.0,
                1.0,
                MetricKind.step,
                true,
                null,
            ),
            MetricDeclaration(
                "speedRaw",
                "Controller speed signal (uncalibrated)",
                "Speed signal",
                "raw",
                MetricGroup.Route,
                "#8799ac",
                2,
                6.0,
                1.0,
                null,
                null,
                null,
            ),
        )
    private val exactIntegerPattern = Regex("-?(0|[1-9][0-9]*)")
    private val exactScalePattern = Regex("[0-9]+(\\.[0-9]+)?")
    private val powersOfTen = DoubleArray(23) { 1.0 }.also { for (i in 1 until it.size) it[i] = it[i - 1] * 10 }
    private val fastFixedLimit = (1L shl 52).toDouble()
    val all: List<MetricDeclaration>
        get() = declarations.toList()

    @Throws(IllegalArgumentException::class)
    fun metric(id: String): MetricDeclaration =
        declarations.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown metric: $id")

    @Throws(IllegalArgumentException::class) fun format(id: String, value: Double?): String = format(id, value, "km/h")

    @Throws(IllegalArgumentException::class)
    fun format(id: String, value: Double?, speedUnit: String): String {
        val declaration = presentationMetric(id, speedUnit)
        if (value == null || !value.isFinite()) return "—"
        return fixed(value * declaration.scale, declaration.decimals)
    }

    fun acceptsSpeedUnit(value: String): Boolean = value in setOf("km/h", "mph", "m/s")

    @Throws(IllegalArgumentException::class)
    fun presentationMetric(id: String, speedUnit: String = "km/h"): MetricDeclaration {
        require(acceptsSpeedUnit(speedUnit)) { "Invalid speed unit" }
        val metric = metric(id)
        if (id !in setOf("speedMps", "controllerSpeedMps")) return metric
        val scale =
            when (speedUnit) {
                "mph" -> 1 / 0.44704
                "m/s" -> 1.0
                else -> metric.scale
            }
        return metric.copy(unit = speedUnit, scale = scale)
    }

    @Throws(IllegalArgumentException::class)
    fun formatExact(id: String, original: String, speedUnit: String = "km/h"): String? {
        val metric = presentationMetric(id, speedUnit)
        if (!exactInteger(original)) return null
        return exactText(metric, original)
    }

    private fun exactInteger(value: String): Boolean =
        value != "-0" &&
            exactIntegerPattern.matches(value) &&
            (value.toLongOrNull() != null || value.toULongOrNull() != null)

    @Throws(IllegalArgumentException::class)
    fun formatPoint(id: String, value: Double?, exactValue: String?, speedUnit: String): String =
        exactValue?.let { formatExact(id, it, speedUnit) } ?: format(id, value, speedUnit)

    @Throws(IllegalArgumentException::class)
    fun allowsMean(id: String): Boolean {
        val declaration = metric(id)
        return declaration.semantics == null &&
            declaration.kind != MetricKind.step &&
            id !in setOf("activeEnergyKcal", "basalEnergyKcal")
    }

    @Throws(IllegalArgumentException::class)
    fun formatDifference(
        id: String,
        a: Double?,
        b: Double?,
        aExact: String?,
        bExact: String?,
        speedUnit: String,
    ): String {
        val metric = presentationMetric(id, speedUnit)
        if (a == null || b == null || !allowsMean(id)) return "—"
        if (aExact != null && bExact != null && exactInteger(aExact) && exactInteger(bExact)) {
            val aMagnitude = Wide.of(aExact.removePrefix("-").toULong())
            val bMagnitude = Wide.of(bExact.removePrefix("-").toULong())
            val sameSign = aExact.startsWith('-') == bExact.startsWith('-')
            val negative = if (sameSign) (bMagnitude < aMagnitude) != bExact.startsWith('-') else bExact.startsWith('-')
            val magnitude =
                if (!sameSign) bMagnitude.plus(aMagnitude)
                else if (bMagnitude >= aMagnitude) bMagnitude.minus(aMagnitude) else aMagnitude.minus(bMagnitude)
            val integer = Natural()
            for (bit in 127 downTo 0) integer.multiply(2).add(if (magnitude.bit(bit)) 1 else 0)
            val difference = (if (negative && !integer.zero) "-" else "") + integer.decimal()
            exactText(metric, difference)?.let {
                return (if (!negative && !integer.zero) "+" else "") + it
            }
        }
        val delta = b * metric.scale - a * metric.scale
        return if (delta.isFinite()) (if (delta > 0) "+" else "") + fixed(delta, metric.decimals) else "—"
    }

    private fun exactText(metric: MetricDeclaration, original: String): String? {
        val scale = CanonicalNumber.encode(metric.scale)
        if (!exactScalePattern.matches(scale)) return null
        val parts = scale.split('.')
        val fraction = parts.getOrElse(1) { "" }
        val a = original.removePrefix("-").reversed()
        val b = (parts[0] + fraction).reversed()
        val digits = IntArray(a.length + b.length + 1)
        for (i in a.indices) for (j in b.indices) digits[i + j] += (a[i] - '0') * (b[j] - '0')
        for (i in 0 until digits.lastIndex) {
            digits[i + 1] += digits[i] / 10
            digits[i] %= 10
        }
        val raw = digits.reversed().joinToString("").trimStart('0').ifEmpty { "0" }
        val keep = raw.length - fraction.length + metric.decimals
        var rounded = if (keep > 0) raw.take(keep).padEnd(keep, '0') else "0"
        if (keep >= 0 && keep < raw.length && raw[keep] >= '5') rounded = increment(rounded)
        val padded = rounded.padStart(metric.decimals + 1, '0')
        val text =
            if (metric.decimals == 0) padded
            else padded.dropLast(metric.decimals) + "." + padded.takeLast(metric.decimals)
        return (if (original.startsWith('-')) "-" else "") + text
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

    internal fun fixed(value: Double, decimals: Int): String = fastFixed(value, decimals) ?: exactFixed(value, decimals)

    // A power of ten up to 10^22 is exact, so the scaled magnitude is the exact product rounded once. Below 2^52 every
    // whole number plus one half is a double and rounding preserves order, so the exact product lies on the same side
    // of that half as the scaled magnitude unless the scaled magnitude is the half itself.
    internal fun fastFixed(value: Double, decimals: Int): String? {
        if (decimals !in powersOfTen.indices) return null
        val magnitude = abs(value)
        val scaled = magnitude * powersOfTen[decimals]
        if (!(scaled < fastFixedLimit)) return null
        val whole = floor(scaled)
        val fraction = scaled - whole
        val up = if (fraction != 0.5) fraction > 0.5 else reachesHalf(magnitude, decimals, whole.toLong())
        val digits = (whole.toLong() + if (up) 1 else 0).toString().padStart(decimals + 1, '0')
        val text = if (decimals == 0) digits else digits.dropLast(decimals) + "." + digits.takeLast(decimals)
        return (if (value < 0) "-" else "") + text
    }

    // m × 2^e × 10^d ≥ whole + 1/2 exactly when m × 5^d ≥ (2 × whole + 1) × 2^-(e + d + 1). Below 2^52 the shift is
    // not negative (e + d + 1 > 0 would need m × 2^e × 10^d ≥ 2^52) and both sides stay below 2^106.
    private fun reachesHalf(magnitude: Double, decimals: Int, whole: Long): Boolean {
        val bits = magnitude.toBits()
        val exponent = (bits ushr 52).toInt()
        val significand = (bits and 0x000fffffffffffffL) or if (exponent == 0) 0L else 1L shl 52
        val power = if (exponent == 0) -1074 else exponent - 1075
        var five = 1UL
        repeat(decimals) { five *= 5UL }
        val half = Wide.of((2 * whole + 1).toULong()).shiftLeft(-(power + decimals + 1))
        return Wide.product(significand.toULong(), five) >= half
    }

    internal fun exactFixed(value: Double, decimals: Int): String {
        if (!value.isFinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (abs(value) >= 1e21) return CanonicalNumber.encode(value).replace("e", "e+")
        val bits = abs(value).toBits()
        val exponent = ((bits ushr 52) and 2047).toInt()
        val mantissa = (bits and 0x000fffffffffffffL) or if (exponent == 0) 0L else 1L shl 52
        val binaryPower = if (exponent == 0) -1074 else exponent - 1075
        val exact = Natural(mantissa)
        if (binaryPower >= 0) exact.shift(binaryPower) else repeat(-binaryPower) { exact.multiply(5) }
        val digits = exact.decimal()
        val keep = digits.length + minOf(0, binaryPower) + decimals
        var rounded = if (keep > 0) digits.take(keep).padEnd(keep, '0') else "0"
        if (keep >= 0 && keep < digits.length && digits[keep] >= '5') {
            rounded = increment(rounded)
        }
        val padded = rounded.padStart(decimals + 1, '0')
        val text = if (decimals == 0) padded else padded.dropLast(decimals) + "." + padded.takeLast(decimals)
        return (if (value < 0) "-" else "") + text
    }
}
