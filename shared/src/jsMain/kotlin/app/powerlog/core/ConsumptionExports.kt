@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.analytics.*
import kotlin.js.JsExport

private fun consumptionTime(value: String): Long = value.toLong().also { require(it.toString() == value && it >= 0) }

@JsExport
class AnalyticsConsumptionRange internal constructor(core: BatteryConsumptionRange) {
    val amount: Double = core.amount
    val coveredUs: String = core.coveredUs.toString()
    val coveredSeconds: Double = core.coveredSeconds
    val available: Boolean = core.available
    val unresolvedBoundary: Boolean = core.unresolvedBoundary
    val partial: Boolean = core.partial
}

@JsExport
class AnalyticsConsumptionInterval(
    val metric: String,
    val startUs: String,
    val endUs: String,
    val startValue: Double,
    val endValue: Double,
    val rate0: Double?,
    val rate1: Double?,
    val indivisible: Boolean,
    val segment: Int,
    val activeInterval: Int,
    val baseline: Boolean,
) {
    internal val core =
        BatteryConsumptionInterval(
            metric,
            consumptionTime(startUs),
            consumptionTime(endUs),
            startValue,
            endValue,
            rate0,
            rate1,
            indivisible,
            segment,
            activeInterval,
            baseline,
        )

    fun range(startUs: String, endUs: String): AnalyticsConsumptionRange =
        AnalyticsConsumptionRange(core.range(consumptionTime(startUs), consumptionTime(endUs)))
}

private fun BatteryConsumptionInterval.js() =
    AnalyticsConsumptionInterval(
        metric,
        startUs.toString(),
        endUs.toString(),
        startValue,
        endValue,
        rate0,
        rate1,
        indivisible,
        segment,
        activeInterval,
        baseline,
    )

@JsExport
class AnalyticsConsumptionKernel(checkpoint: String? = null) {
    private val core = BatteryConsumptionKernel(checkpoint)

    fun checkpoint(): String = core.checkpoint()

    fun append(
        timeUs: String,
        activeInterval: Int?,
        continuityIdentity: String?,
        chargeAh: Double?,
        energyWh: Double?,
        currentA: Double?,
        voltageV: Double?,
    ): Array<AnalyticsConsumptionInterval> =
        core
            .append(consumptionTime(timeUs), activeInterval, continuityIdentity, chargeAh, energyWh, currentA, voltageV)
            .map { it.js() }
            .toTypedArray()
}

@JsExport
class AnalyticsConsumptionReducer(metric: String, startUs: String, endUs: String) {
    private val core = BatteryConsumptionReducer(metric, consumptionTime(startUs), consumptionTime(endUs))

    fun append(interval: AnalyticsConsumptionInterval) = core.append(interval.core)

    fun result(timerUs: String): AnalyticsConsumptionRange =
        AnalyticsConsumptionRange(core.result(consumptionTime(timerUs)))
}
