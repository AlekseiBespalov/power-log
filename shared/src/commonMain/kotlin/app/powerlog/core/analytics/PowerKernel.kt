package app.powerlog.core.analytics

data class FixedPowerIntegration(val coveredUs: Long, val integralQUs: Long, val maximumQ: Long) {
    val covered: Double
        get() = FixedPoint.divide(coveredUs, 1000000)

    val integral: Double
        get() = FixedPoint.divide(integralQUs, 1000000000)

    val maximum: Double?
        get() = if (maximumQ < 0) null else FixedPoint.divide(maximumQ, 1000)

    val average: Double?
        get() = if (coveredUs == 0L) null else FixedPoint.divide(integralQUs, FixedPoint.product(coveredUs, 1000))

    fun merge(other: FixedPowerIntegration): FixedPowerIntegration =
        FixedPowerIntegration(
            FixedPoint.add(coveredUs, other.coveredUs),
            FixedPoint.add(integralQUs, other.integralQUs),
            maxOf(maximumQ, other.maximumQ),
        )
}

object FixedPowerKernel {
    fun integrate(timesUs: LongArray, valuesQ: LongArray, runs: IntArray): FixedPowerIntegration {
        require(timesUs.size == valuesQ.size && valuesQ.size == runs.size)
        var covered = 0L
        var work = 0L
        var maximum = -1L
        var previousTime = 0L
        var previousValue = 0L
        var previousRun = 0
        var hasPrevious = false
        var overflowBits = 0L
        for (index in timesUs.indices) {
            val value = valuesQ[index]
            val run = runs[index]
            if (run < 0 || value < 0L || value > 32766000L) {
                hasPrevious = false
                continue
            }
            if (value > maximum) maximum = value
            val time = timesUs[index]
            val dt = time - previousTime
            if (hasPrevious && time > previousTime && dt > 0L && dt <= 2500000L && run == previousRun) {
                val numerator = (previousValue + value) * dt
                val half = numerator ushr 1
                val contribution = half + (numerator and half and 1L)
                work += contribution
                // Every contribution fits and is nonnegative; retaining each sum's sign makes overflow absorbing.
                overflowBits = overflowBits or work
                // Int-indexed arrays cap coverage at Int.MAX_VALUE * 2,500,000, below Int64.max.
                covered += dt
            }
            previousTime = time
            previousValue = value
            previousRun = run
            hasPrevious = true
        }
        if (overflowBits < 0L) throw NumericFailure(NumericOverflow("humanPowerW", Quantity.integralQUs))
        return FixedPowerIntegration(covered, work, maximum)
    }
}
