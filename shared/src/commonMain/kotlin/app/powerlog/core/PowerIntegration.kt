package app.powerlog.core

import app.powerlog.core.analytics.FixedPoint
import app.powerlog.core.analytics.FixedPowerKernel

data class PowerIntegration(
    val covered: Double,
    val integral: Double,
    val maximum: Double?,
    val average: Double?,
)

object PowerKernel {
    fun integrate(times: DoubleArray, values: DoubleArray, runs: IntArray): PowerIntegration {
        require(times.size == values.size && values.size == runs.size)
        val keys = LongArray(times.size) { FixedPoint.timeUs(times[it]) }
        val admitted = LongArray(values.size) { -1 }
        var maximum: Double? = null
        for (index in times.indices) {
            val value = values[index]
            if (runs[index] >= 0 && value.isFinite() && value in 0.0..32766.0) {
                if (maximum == null || value > requireNotNull(maximum)) maximum = value
                admitted[index] = FixedPoint.quantize(value, 1000)
            }
        }
        val result = FixedPowerKernel.integrate(keys, admitted, runs)
        return PowerIntegration(result.covered, result.integral, maximum, result.average)
    }
}
