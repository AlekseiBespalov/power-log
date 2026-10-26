@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.analytics.EnergyPerDistanceCheckpoint
import app.powerlog.core.analytics.EnergyPerDistancePolicy
import app.powerlog.core.analytics.EnergyPerDistanceQuery
import app.powerlog.core.analytics.EnergyPerDistanceSeries
import app.powerlog.core.analytics.EnergyPerDistanceSeriesRead
import app.powerlog.core.inspection.InspectionPlotSlots
import kotlin.js.JsExport

@JsExport fun analyticsEnergyContextSeconds(seconds: Int): Int = EnergyPerDistancePolicy.contextSeconds(seconds)

@JsExport fun analyticsEnergyCheckpointMarginUs(): String = EnergyPerDistancePolicy.checkpointMarginUs.toString()

@JsExport
class AnalyticsEnergyPerDistanceCheckpoint internal constructor(internal val core: EnergyPerDistanceCheckpoint) {
    val throughUs = core.throughUs.toString()
}

@JsExport
class AnalyticsEnergyPerDistance(
    startUs: String,
    endUs: String,
    buckets: Int?,
    timerUs: String,
    controllerSpeed: Boolean,
    smoothingSeconds: Int,
    includeTotals: Boolean = true,
    checkpoint: AnalyticsEnergyPerDistanceCheckpoint? = null,
    alignedSlotUs: String? = null,
) {
    private val core =
        when {
            alignedSlotUs != null -> {
                require(buckets != null && checkpoint == null) { "An aligned energy/distance series needs buckets" }
                EnergyPerDistanceQuery(
                    startUs.toLong(),
                    endUs.toLong(),
                    buckets,
                    alignedSlotUs.toLong(),
                    timerUs.toLong(),
                    controllerSpeed,
                    smoothingSeconds,
                    includeTotals,
                )
            }
            checkpoint != null -> {
                require(buckets == null) { "A resumed energy/distance query has no series" }
                EnergyPerDistanceQuery(
                    checkpoint.core,
                    startUs.toLong(),
                    endUs.toLong(),
                    timerUs.toLong(),
                    controllerSpeed,
                    smoothingSeconds,
                    includeTotals,
                )
            }
            buckets == null ->
                EnergyPerDistanceQuery(
                    startUs.toLong(),
                    endUs.toLong(),
                    timerUs.toLong(),
                    controllerSpeed,
                    smoothingSeconds,
                    includeTotals,
                )
            else ->
                EnergyPerDistanceQuery(
                    startUs.toLong(),
                    endUs.toLong(),
                    buckets,
                    timerUs.toLong(),
                    controllerSpeed,
                    smoothingSeconds,
                    includeTotals,
                )
        }

    fun distance(start: String, end: String, meters: Double, speed0: Double?, speed1: Double?) =
        core.appendDistance(start.toLong(), end.toLong(), meters, speed0, speed1)

    fun battery(
        start: String,
        end: String,
        wh: Double,
        rate0: Double?,
        rate1: Double?,
        indivisible: Boolean,
        timestamp: String,
    ) = core.appendBattery(start.toLong(), end.toLong(), wh, rate0, rate1, indivisible, timestamp)

    fun speed(time: String, mps: Double?, accuracy: Double?, active: Int?) =
        core.appendSpeed(time.toLong(), mps, accuracy, active)

    fun telemetry(
        time: String,
        watts: Double?,
        batteryWatts: Double?,
        speedMps: Double?,
        active: Int?,
        continuity: String?,
        timestamp: String,
    ) = core.appendTelemetry(time.toLong(), watts, batteryWatts, speedMps, active, continuity, timestamp)

    fun checkpoint(): AnalyticsEnergyPerDistanceCheckpoint = AnalyticsEnergyPerDistanceCheckpoint(core.checkpoint())

    fun resultJson(): String = core.resultJson()
}

@JsExport
class AnalyticsEnergyPerDistanceSeries(
    controllerSpeed: Boolean,
    smoothingSeconds: Int,
    includeTotals: Boolean,
    pointBudget: Int = InspectionPlotSlots.maximumPoints,
) {
    private val core = EnergyPerDistanceSeries(controllerSpeed, smoothingSeconds, includeTotals, pointBudget)

    val valid: Boolean
        get() = core.valid

    val throughUs: String
        get() = core.throughUs.toString()

    val retainedPoints: Int
        get() = core.retainedPoints

    fun begin(
        endUs: String,
        timerUs: String,
        buckets: Int,
        alignedSlotUs: String?,
    ): AnalyticsEnergyPerDistanceSeriesRead =
        AnalyticsEnergyPerDistanceSeriesRead(
            core.begin(endUs.toLong(), timerUs.toLong(), buckets, alignedSlotUs?.toLong())
        )
}

@JsExport
class AnalyticsEnergyPerDistanceSeriesRead internal constructor(private val core: EnergyPerDistanceSeriesRead) {
    fun distance(start: String, end: String, meters: Double, speed0: Double?, speed1: Double?) =
        core.appendDistance(start.toLong(), end.toLong(), meters, speed0, speed1)

    fun battery(
        start: String,
        end: String,
        wh: Double,
        rate0: Double?,
        rate1: Double?,
        indivisible: Boolean,
        timestamp: String,
    ) = core.appendBattery(start.toLong(), end.toLong(), wh, rate0, rate1, indivisible, timestamp)

    fun speed(time: String, mps: Double?, accuracy: Double?, active: Int?) =
        core.appendSpeed(time.toLong(), mps, accuracy, active)

    fun telemetry(
        time: String,
        watts: Double?,
        batteryWatts: Double?,
        speedMps: Double?,
        active: Int?,
        continuity: String?,
        timestamp: String,
    ) = core.appendTelemetry(time.toLong(), watts, batteryWatts, speedMps, active, continuity, timestamp)

    fun cut() = core.cut()

    fun resultJson(): String = core.resultJson()
}
