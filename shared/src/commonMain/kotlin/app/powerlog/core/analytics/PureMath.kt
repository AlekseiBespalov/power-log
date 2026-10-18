package app.powerlog.core.analytics

object PureMath {
    const val PI = 3.141592653589793
    private const val HALF_PI = 1.5707963267948966
    private const val QUARTER_PI = 0.7853981633974483

    fun abs(x: Double): Double = if (x < 0) -x else x

    private fun angle(x: Double): Double {
        require(x.isFinite() && abs(x) <= 8 * PI)
        var a = x
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    fun sin(x: Double): Double {
        var a = angle(x)
        if (a > HALF_PI) a = PI - a
        if (a < -HALF_PI) a = -PI - a
        val square = a * a
        var term = a
        var sum = a
        for (n in 1..13) {
            term *= -square / ((2 * n).toDouble() * (2 * n + 1))
            sum += term
        }
        return sum
    }

    fun cos(x: Double): Double {
        var a = abs(angle(x))
        val sign = if (a > HALF_PI) -1 else 1
        if (a > HALF_PI) a = PI - a
        val square = a * a
        var term = 1.0
        var sum = 1.0
        for (n in 1..14) {
            term *= -square / ((2 * n - 1).toDouble() * (2 * n))
            sum += term
        }
        return sign * sum
    }

    fun sqrt(x: Double): Double {
        require(x >= 0 && x.isFinite())
        if (x == 0.0) return x
        if (x < 2.2250738585072014e-308) return sqrt(x * 18014398509481984.0) / 134217728.0
        var guess = Double.fromBits((x.toBits() ushr 1) + 0x1ff8000000000000L)
        repeat(8) { guess = (guess + x / guess) * 0.5 }
        return guess
    }

    private fun atan(x: Double): Double {
        if (x > 1) return HALF_PI - atan(1 / x)
        val reduce = x > 0.41421356237309503
        val a = if (reduce) (x - 1) / (x + 1) else x
        val square = a * a
        var term = a
        var sum = a
        for (n in 1..27) {
            term *= -square
            sum += term / (2 * n + 1)
        }
        return if (reduce) QUARTER_PI + sum else sum
    }

    fun atan2(y: Double, x: Double): Double {
        require(x.isFinite() && y.isFinite())
        if (x == 0.0) return if (y > 0) HALF_PI else if (y < 0) -HALF_PI else 0.0
        val a = atan(abs(y / x))
        return if (x > 0) {
            if (y < 0) -a else a
        } else {
            if (y < 0) a - PI else PI - a
        }
    }

    fun haversine(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double {
        val p0 = lat0 * PI / 180
        val p1 = lat1 * PI / 180
        val dp = p1 - p0
        val dl = (lon1 - lon0) * PI / 180
        val s0 = sin(dp / 2)
        val s1 = sin(dl / 2)
        val h = s0 * s0 + cos(p0) * cos(p1) * (s1 * s1)
        return 6371008.8 * 2 * atan2(sqrt(maxOf(0.0, minOf(1.0, h))), sqrt(maxOf(0.0, 1 - h)))
    }
}
