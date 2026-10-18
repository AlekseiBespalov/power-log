package app.powerlog.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object NumericProbe {
    fun distance(): Double {
        val latitude = 0.000007848061528802385
        val longitude = 0.0000000000001
        val a = sin(latitude / 2) * sin(latitude / 2) + cos(latitude) * sin(longitude / 2) * sin(longitude / 2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bits(): String = distance().toRawBits().toULong().toString(16).padStart(16, '0')

    fun branch(): Boolean = StreamStatus.location(distance()) == "weak"
}
