package app.powerlog.core.analytics

import app.powerlog.core.inspection.InspectionCapacityException
import app.powerlog.core.inspection.InspectionPlotReducer
import app.powerlog.core.inspection.InspectionPlotSlots
import app.powerlog.core.inspection.InspectionPlotSummary

// Every whole-ride read that ends at or after 512 s gets an aligned slot, because a series has at most 512 buckets.
private const val exactWidthUs = 512_000_000L

/**
 * Keeps the whole-ride energy/distance series incremental for one ride and parameter set: a series-free checkpoint at
 * the committed cut and, per lane, a plot summary of the readings fed before it. Each read feeds only the rows after
 * the cut through [begin].
 */
class EnergyPerDistanceSeries
@Throws(IllegalArgumentException::class)
constructor(
    private val controllerSpeed: Boolean,
    private val smoothingSeconds: Int,
    private val includeTotals: Boolean,
    pointBudget: Int = InspectionPlotSlots.maximumPoints,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        controllerSpeed: Boolean,
        smoothingSeconds: Int,
        includeTotals: Boolean,
    ) : this(controllerSpeed, smoothingSeconds, includeTotals, InspectionPlotSlots.maximumPoints)

    private val summaries = EnergyPerDistancePolicy.metrics.map { InspectionPlotSummary(it, 512, pointBudget) }
    private var log: List<MutableList<AnalysisItem>>? = EnergyPerDistancePolicy.metrics.map { mutableListOf() }
    private var checkpoint: EnergyPerDistanceCheckpoint? = null
    private var broken = false
    internal var current: EnergyPerDistanceSeriesRead? = null
        private set

    // An unfinished read that has fed the summaries leaves them ahead of the committed checkpoint.
    val valid: Boolean
        get() = !broken && current?.appended != true

    val throughUs: Long
        get() = checkpoint?.throughUs ?: -1

    val retainedPoints: Int
        get() = summaries.sumOf { it.retainedPoints } + (log?.sumOf { it.size } ?: 0)

    init {
        require(EnergyPerDistancePolicy.validSmoothing(smoothingSeconds))
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun begin(endUs: Long, timerUs: Long, buckets: Int, alignedSlotUs: Long?): EnergyPerDistanceSeriesRead {
        if (current?.appended == true) {
            broken = true
            current = null
        }
        check(!broken) { "The energy/distance series must be rebuilt" }
        val query =
            checkpoint?.let {
                EnergyPerDistanceQuery(it, 0, endUs, timerUs, controllerSpeed, smoothingSeconds, includeTotals)
            } ?: EnergyPerDistanceQuery(0, endUs, timerUs, controllerSpeed, smoothingSeconds, includeTotals)
        val plots =
            EnergyPerDistancePolicy.metrics.mapIndexed { lane, metric ->
                val plot =
                    InspectionPlotReducer(metric, AnalysisRange(0, endUs), buckets, alignedSlotUs = alignedSlotUs)
                if (alignedSlotUs != null) summaries[lane].mergeInto(plot)
                else {
                    val replay = requireNotNull(log) { "Exact-width series need a read ending before 512 s" }
                    for (reading in replay[lane]) plot.append(reading)
                }
                plot
            }
        return EnergyPerDistanceSeriesRead(this, query, plots).also { current = it }
    }

    internal fun keep(lane: Int, reading: AnalysisItem) {
        if (broken) return
        try {
            summaries[lane].append(reading)
            // Exact-width reads end before 512 s, so the log never replays a later reading.
            if (reading.collectionUs < exactWidthUs) log?.get(lane)?.add(reading)
        } catch (_: Throwable) {
            broken = true
        }
    }

    internal fun fail(invalidate: Boolean) {
        current = null
        if (invalidate) broken = true
    }

    internal fun commit(next: EnergyPerDistanceCheckpoint?, appended: Boolean) {
        current = null
        if (next == null) {
            if (appended) broken = true
            return
        }
        if (broken) return
        checkpoint = next
        if (next.throughUs >= exactWidthUs) log = null
    }
}

/**
 * One read of an [EnergyPerDistanceSeries]: the same inputs as [EnergyPerDistanceQuery], fed from the committed cut on
 * and plotted with the buckets that [EnergyPerDistanceSeries.begin] was given. [cut] marks where the next committed cut
 * falls; [resultJson] returns the series query's JSON and commits that cut.
 */
class EnergyPerDistanceSeriesRead
internal constructor(
    private val series: EnergyPerDistanceSeries,
    private val query: EnergyPerDistanceQuery,
    private val plots: List<InspectionPlotReducer>,
) {
    // A checkpoint must precede the end by more than the margin, so a reading from end − margin on can only belong to a
    // read that is never cut; it stays out of the summaries.
    private val keptBeforeUs = query.endUs - EnergyPerDistancePolicy.checkpointMarginUs
    private var next: EnergyPerDistanceCheckpoint? = null

    internal var appended = false
        private set

    init {
        query.readings = { lane, reading ->
            plots[lane].append(reading)
            if (next == null && reading.collectionUs < keptBeforeUs) {
                appended = true
                series.keep(lane, reading)
            }
        }
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun appendDistance(startUs: Long, endUs: Long, meters: Double, speed0: Double?, speed1: Double?) = feed {
        query.appendDistance(startUs, endUs, meters, speed0, speed1)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun appendBattery(
        startUs: Long,
        endUs: Long,
        wattHours: Double,
        rate0: Double?,
        rate1: Double?,
        indivisible: Boolean,
        timestamp: String,
    ) = feed { query.appendBattery(startUs, endUs, wattHours, rate0, rate1, indivisible, timestamp) }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun appendSpeed(timeUs: Long, mps: Double?, accuracy: Double?, activeInterval: Int?) = feed {
        query.appendSpeed(timeUs, mps, accuracy, activeInterval)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun appendTelemetry(
        timeUs: Long,
        watts: Double?,
        batteryWatts: Double?,
        speedMps: Double?,
        activeInterval: Int?,
        continuity: String?,
        timestamp: String,
    ) = feed { query.appendTelemetry(timeUs, watts, batteryWatts, speedMps, activeInterval, continuity, timestamp) }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun cut() = feed {
        check(next == null) { "An energy/distance series read is cut once" }
        next = query.checkpoint()
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, InspectionCapacityException::class)
    fun resultJson(): String {
        val json = feed {
            query.resultJson(EnergyPerDistancePolicy.metrics.zip(plots) { metric, plot -> metric to plot.result() })
        }
        series.commit(next, appended)
        return json
    }

    private inline fun <T> feed(block: () -> T): T {
        check(series.current === this) { "This energy/distance series read has ended" }
        try {
            return block()
        } catch (failure: Throwable) {
            series.fail(failure !is InspectionCapacityException || appended)
            throw failure
        }
    }
}
