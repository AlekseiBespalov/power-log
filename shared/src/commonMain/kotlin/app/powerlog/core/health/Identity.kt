package app.powerlog.core.health

import app.powerlog.core.HealthIdentity
import app.powerlog.core.analytics.FixedPoint

object HealthIds {
    @Throws(IllegalArgumentException::class)
    fun uuid(value: String): String = value.lowercase().also { require(HealthIdentity.validRide(it)) }

    @Throws(IllegalArgumentException::class)
    fun origin(producer: String, sequence: String): String {
        require(Source.entries.any { it.name == producer })
        val n = sequence.toLongOrNull()
        require(n != null && n > 0 && n.toString() == sequence)
        return "seq:$producer:$sequence"
    }

    @Throws(IllegalArgumentException::class) fun event(eventId: String): String = "event:${uuid(eventId)}"

    @Throws(IllegalArgumentException::class)
    fun originKey(value: String): String {
        val fields = value.split(':')
        return when {
            fields.size == 2 && fields[0] == "event" -> event(fields[1])
            fields.size == 3 && fields[0] == "seq" -> origin(fields[1], fields[2])
            else -> throw IllegalArgumentException("Invalid OriginKey")
        }.also { require(it == value) }
    }

    private fun id(ride: String, suffix: String): String =
        "powerlog:${uuid(ride)}:$suffix".also { require(it.length <= 256 && it.all { c -> c.code < 128 }) }

    @Throws(IllegalArgumentException::class) fun workout(ride: String): String = id(ride, "workout")

    @Throws(IllegalArgumentException::class) fun route(ride: String): String = id(ride, "route")

    @Throws(IllegalArgumentException::class)
    fun second(ride: String, kind: HealthKind, n: Long): String {
        require(n >= 0 && kind != HealthKind.cyclingDistance)
        return id(ride, "${kind.wire}:second:$n")
    }

    @Throws(IllegalArgumentException::class)
    fun distance(ride: String, first: String, last: String): String =
        id(ride, "cycling-distance:interval:${originKey(first)}:${originKey(last)}")

    @Throws(IllegalArgumentException::class)
    fun lap(ride: String, eventId: String): String = id(ride, "lap:event:${uuid(eventId)}")

    @Throws(IllegalArgumentException::class)
    fun lapInterval(ride: String, first: String, last: String): String =
        id(ride, "lap:interval:${originKey(first)}:${originKey(last)}")

    @Throws(IllegalArgumentException::class)
    fun syncId(value: String): String {
        require(value.startsWith("powerlog:") && value.length <= 256)
        val fields = value.split(':')
        require(fields.size >= 3 && uuid(fields[1]) == fields[1])
        val rebuilt =
            when (fields[2]) {
                "workout" -> workout(fields[1])
                "route" -> route(fields[1])
                "lap" ->
                    if (fields.getOrNull(3) == "event" && fields.size == 5) lap(fields[1], fields[4])
                    else {
                        require(fields.getOrNull(3) == "interval")
                        val origins = splitOrigins(fields.drop(4))
                        lapInterval(fields[1], origins.first, origins.second)
                    }
                else -> {
                    val kind =
                        HealthKind.entries.firstOrNull { it.wire == fields[2] }
                            ?: throw IllegalArgumentException("Invalid Health kind")
                    if (kind == HealthKind.cyclingDistance) {
                        require(fields.getOrNull(3) == "interval")
                        val origins = splitOrigins(fields.drop(4))
                        distance(fields[1], origins.first, origins.second)
                    } else {
                        require(fields.size == 5 && fields[3] == "second")
                        val n = fields[4].toLong()
                        require(n.toString() == fields[4])
                        second(fields[1], kind, n)
                    }
                }
            }
        require(rebuilt == value)
        return value
    }

    private fun splitOrigins(parts: List<String>): Pair<String, String> {
        val width = if (parts.firstOrNull() == "event") 2 else 3
        return parts.take(width).joinToString(":") to parts.drop(width).joinToString(":")
    }
}

data class SecondBounds(val n: Long, val startUs: Long, val endUs: Long)

object SecondBuckets {
    @Throws(ArithmeticException::class)
    fun index(startUs: Long, readingUs: Long): Long = floorDiv(FixedPoint.subtract(readingUs, startUs), 1000000)

    @Throws(IllegalArgumentException::class)
    fun floorDiv(value: Long, divisor: Long): Long {
        require(divisor > 0)
        return value / divisor - if (value < 0 && value % divisor != 0L) 1 else 0
    }

    @Throws(ArithmeticException::class, IllegalArgumentException::class)
    fun bounds(startUs: Long, cutoffUs: Long, n: Long): SecondBounds {
        require(n >= 0)
        val a = FixedPoint.add(startUs, FixedPoint.product(n, 1000000))
        val b = minOf(cutoffUs, FixedPoint.add(a, 1000000))
        require(b > a)
        return SecondBounds(n, a, b)
    }

    @Throws(ArithmeticException::class, IllegalArgumentException::class)
    fun eligible(endUs: Long, nowUs: Long, stopped: Boolean): Boolean =
        stopped || endUs <= FixedPoint.subtract(nowUs, 10000000)

    @Throws(ArithmeticException::class, IllegalArgumentException::class)
    fun count(startUs: Long, cutoffUs: Long): Long {
        val duration = FixedPoint.subtract(cutoffUs, startUs)
        return if (duration <= 0) 0 else duration / 1000000 + if (duration % 1000000 != 0L) 1 else 0
    }
}
