package app.powerlog.domain.charts

import app.powerlog.domain.core.analysis.battery.BatteryConsumptionState
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.battery.ConsumptionTotals
import app.powerlog.domain.core.analysis.distance.ControllerDistanceState
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.DistanceTotals
import app.powerlog.domain.core.analysis.distance.GpsDistanceState
import app.powerlog.domain.core.analysis.energyperdistance.EnergyPerDistancePolicy
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricState
import app.powerlog.domain.core.analysis.statistics.accurate
import app.powerlog.domain.core.analysis.statistics.reads
import app.powerlog.domain.core.analysis.statistics.value
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.time.TimeRange
import kotlin.math.abs
import kotlinx.serialization.Serializable

public object PlotSlots {
    public const val maxPoints: Int = 16_384

    private const val SECOND_US = 1_000_000L
    private const val ORIGINALS_PER_BUCKET = 4

    public fun buckets(requested: Int, metrics: Int): Int {
        require(requested in 1..512) { "a plot asks for 1 to 512 buckets: $requested" }
        return maxOf(1, minOf(requested, maxPoints / maxOf(1, metrics) / ORIGINALS_PER_BUCKET - 2))
    }

    public fun alignedSlotUs(range: TimeRange, buckets: Int): Long? {
        require(buckets in 1..512) { "a plot has 1 to 512 buckets: $buckets" }
        val span = range.endUs - range.startUs
        if (span < SECOND_US * buckets) return null
        var slot = SECOND_US
        while (slot * buckets < span) slot *= 2
        return slot
    }
}

@Serializable
public data class PlotPoint(
    public val first: OriginalRef,
    public val min: OriginalRef,
    public val max: OriginalRef,
    public val last: OriginalRef,
    public val segment: Int,
)

@Serializable
public data class PlotAggregate(
    public val points: List<PlotPoint>,
    public val statistics: MetricState,
    public val resume: DerivedResume?,
)

@Serializable
public sealed interface DerivedResume {
    @Serializable
    public data class Distance(
        public val controller: ControllerDistanceState?,
        public val gps: GpsDistanceState?,
        public val totals: DistanceTotals,
    ) : DerivedResume

    @Serializable
    public data class Consumption(public val state: BatteryConsumptionState, public val totals: ConsumptionTotals) :
        DerivedResume
}

public class PlotReducer(
    private val metric: MetricId,
    private val range: TimeRange,
    private val slotUs: Long?,
    private val buckets: Int,
) : TelemetryConsumer {
    private val definition = MetricCatalog.of(metric)
    private val gapUs = if (metric in distances) DISTANCE_GAP_US else definition.plotGapUs
    private val statistics = MetricReducer(metric, range, true)
    private val points = mutableListOf<PlotPoint>()
    private val bucket = mutableListOf<PlotOriginal>()
    private var bucketIndex: Long? = null
    private var bucketSegment = 0
    private var segment = 0
    private var previous: PlotOriginal? = null
    private var broken = false
    private var predecessor: PlotOriginal? = null
    private var successor: PlotOriginal? = null
    private var viewport = false
    private var capacity = false
    private var arrivals = 0L
    private var lastDerived: OriginalRef? = null

    init {
        require(buckets in 1..512) { "a plot has 1 to 512 buckets: $buckets" }
        require(slotUs == null || slotUs > 0) { "a plot slot is positive: $slotUs" }
    }

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        if (definition.origin != MetricOrigin.telemetry && metric != MetricId.controllerSpeedMps) return
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            if (metric !in session.capabilities.metrics) continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            val order = rows.sessionNo[row].toLong() * SEQ_SPAN + rows.seq[row].toLong()
            add(
                elapsedUs,
                original(
                    rows.utcUs[row].toLong(),
                    elapsedUs,
                    rows.value(metric, row, session),
                    order,
                    rows.activeInterval[row],
                    lineRun(rows, row, session),
                ),
            )
        }
        statistics.append(rows, from, to, sessions)
    }

    public fun append(fact: Fact) {
        when (fact) {
            is Fact.Location ->
                if (definition.origin == MetricOrigin.location) {
                    val fix = fact.fix
                    val value = fix.value(metric)?.takeIf { fix.accurate(metric) }
                    add(
                        fix.elapsedUs,
                        original(fix.utcUs, fix.elapsedUs, value, fix.seq, fix.activeInterval ?: 0, 0),
                    )
                    statistics.append(fact)
                }
            is Fact.Health -> {
                val sample = fact.sample
                if (definition.origin == MetricOrigin.health && sample.reads(metric)) {
                    val order = arrivals++
                    add(
                        sample.elapsedUs,
                        original(sample.utcUs, sample.elapsedUs, sample.value, order, sample.activeInterval ?: 0, 0),
                    )
                    statistics.append(fact)
                }
            }
            is Fact.Event -> Unit
        }
    }

    public fun distance(interval: DistanceInterval) {
        if (metric !in distances) return
        val start = interval.start
        val last = lastDerived
        if (last == null || last.elapsedUs != start.elapsedUs || last.valueQ != start.valueQ)
            add(start.elapsedUs, derived(start, interval.activeInterval))
        add(interval.end.elapsedUs, derived(interval.end, interval.activeInterval, start.elapsedUs))
        lastDerived = interval.end
    }

    public fun battery(interval: ConsumptionInterval) {
        if (interval.metric.name != metric.name) return
        val start =
            OriginalRef(interval.utc.startUtcUs, interval.range.startUs, quantize(interval.startValue) ?: return)
        val end = OriginalRef(interval.utc.endUtcUs, interval.range.endUs, quantize(interval.endValue) ?: return)
        val last = lastDerived
        if (interval.baseline || last == null || last.elapsedUs != start.elapsedUs || last.valueQ != start.valueQ)
            add(start.elapsedUs, derived(start, interval.activeInterval))
        if (!interval.baseline) add(end.elapsedUs, derived(end, interval.activeInterval))
        lastDerived = end
    }

    public fun merge(aggregate: PlotAggregate) {
        flush()
        startViewport()
        val tile = aggregate.points
        if (tile.isEmpty()) return
        val old = previous
        val head = tile.first()
        val headOriginal =
            PlotOriginal(
                head.first,
                head.first.valueQ / definition.quantum.toDouble(),
                Long.MIN_VALUE,
                UNKNOWN,
                UNKNOWN,
            )
        val base = if (old != null && head.segment == 0 && starts(old, headOriginal)) segment + 1 else segment
        for (point in tile) {
            points += point.copy(segment = base + point.segment)
            if (points.size * ORIGINALS_PER_POINT > PlotSlots.maxPoints) capacity = true
        }
        val last = tile.last()
        segment = base + last.segment
        previous =
            PlotOriginal(last.last, last.last.valueQ / definition.quantum.toDouble(), Long.MAX_VALUE, UNKNOWN, UNKNOWN)
        broken = false
        bucketIndex = null
    }

    public fun aggregate(): PlotAggregate {
        flush()
        startViewport()
        return PlotAggregate(points.toList(), statistics.state(), null)
    }

    public fun result(): Bounded<List<PlotPoint>> {
        flush()
        startViewport()
        if (capacity) return Bounded.Capacity
        val before = predecessor?.let { single(it, 0) }
        val after = successor?.let { next ->
            val old = previous
            single(next, if (old != null && (broken || starts(old, next))) segment + 1 else segment)
        }
        return Bounded.Done(listOfNotNull(before) + points + listOfNotNull(after))
    }

    internal fun plotted(utcUs: Long, elapsedUs: Long, value: Double?, order: Long, activeInterval: Int, run: Int) =
        add(elapsedUs, original(utcUs, elapsedUs, value, order, activeInterval, run))

    internal fun replay(elapsedUs: Long, original: PlotOriginal?) = add(elapsedUs, original)

    internal fun seed(
        points: List<PlotPoint>,
        open: List<PlotOriginal>,
        segment: Int,
        previous: PlotOriginal?,
        broken: Boolean,
    ) {
        viewport = true
        this.points += points
        bucket += open
        if (open.isNotEmpty()) {
            bucketIndex = index(open.first().ref.elapsedUs)
            bucketSegment = segment
        }
        this.segment = segment
        this.previous = previous
        this.broken = broken
    }

    internal fun original(
        utcUs: Long,
        elapsedUs: Long,
        value: Double?,
        order: Long,
        activeInterval: Int,
        run: Int,
    ): PlotOriginal? {
        if (value == null || !value.isFinite() || definition.plotRange?.contains(value) == false) return null
        val valueQ = quantize(value) ?: return null
        return PlotOriginal(OriginalRef(utcUs, elapsedUs, valueQ), value, order, activeInterval, run)
    }

    private fun derived(ref: OriginalRef, activeInterval: Int, fromUs: Long? = null): PlotOriginal? {
        val value = ref.valueQ / definition.quantum.toDouble()
        if (definition.plotRange?.contains(value) == false) return null
        return PlotOriginal(ref, value, arrivals++, activeInterval, 0, fromUs)
    }

    private fun quantize(value: Double): Long? =
        try {
            FixedPoint.quantize(value, definition.quantum)
        } catch (_: ArithmeticException) {
            null
        }

    private fun add(elapsedUs: Long, original: PlotOriginal?) {
        if (elapsedUs < range.startUs) {
            if (original == null) broken = true
            else {
                if (broken || predecessor?.ref?.elapsedUs != elapsedUs) predecessor = original
                broken = false
            }
            return
        }
        startViewport()
        if (elapsedUs >= range.endUs) {
            if (original != null && successor == null) successor = original
            else if (original == null && successor == null) broken = true
            return
        }
        if (original == null) {
            broken = true
            return
        }
        val old = previous
        if (old != null && (broken || starts(old, original))) segment++
        broken = false
        previous = original
        val index = index(elapsedUs)
        if (index != bucketIndex || bucketSegment != segment) flush()
        bucketIndex = index
        bucketSegment = segment
        bucket += original
    }

    private fun startViewport() {
        if (viewport) return
        viewport = true
        previous = predecessor
    }

    internal fun starts(old: PlotOriginal, next: PlotOriginal, course: Boolean = true): Boolean =
        next.ref.elapsedUs - old.ref.elapsedUs >= gapUs && next.fromUs != old.ref.elapsedUs ||
            old.activeInterval != UNKNOWN &&
                next.activeInterval != UNKNOWN &&
                (old.activeInterval != next.activeInterval || old.run != next.run) ||
            course && metric == MetricId.courseDegrees && abs(next.value - old.value) > HALF_TURN_DEGREES

    private fun index(elapsedUs: Long): Long {
        if (slotUs != null) return elapsedUs / slotUs
        val width = range.endUs - range.startUs
        return if (width == 0L) 0L else minOf(buckets - 1L, (elapsedUs - range.startUs) * buckets / width)
    }

    private fun flush() {
        if (bucket.isEmpty()) return
        val first = bucket.first()
        val last = bucket.last()
        val low = bucket.minWith(compareBy<PlotOriginal> { it.value }.thenBy { it.ref.elapsedUs }.thenBy { it.order })
        val high =
            bucket.maxWith(
                compareBy<PlotOriginal> { it.value }.thenByDescending { it.ref.elapsedUs }.thenByDescending { it.order }
            )
        points += PlotPoint(first.ref, low.ref, high.ref, last.ref, bucketSegment)
        bucket.clear()
        if (points.size * ORIGINALS_PER_POINT > PlotSlots.maxPoints) capacity = true
    }

    private fun single(original: PlotOriginal, segment: Int): PlotPoint =
        PlotPoint(original.ref, original.ref, original.ref, original.ref, segment)

    private companion object {
        const val SEQ_SPAN = 1L shl 40
        const val HALF_TURN_DEGREES = 180.0
        const val ORIGINALS_PER_POINT = 4
        const val DISTANCE_GAP_US = 6_000_000L
        const val UNKNOWN = -1
        val distances = setOf(MetricId.distanceMeters, MetricId.gpsDistanceMeters, MetricId.controllerDistanceMeters)
    }
}

// Only an imported ride's lines break where its run changes: a recorded ride's run also changes at every reconnect.
internal fun lineRun(rows: TelemetryRows, row: Int, session: ControllerSession): Int =
    if (session.identity == null) rows.run[row] else 0

internal class PlotOriginal(
    val ref: OriginalRef,
    val value: Double,
    val order: Long,
    val activeInterval: Int,
    val run: Int,
    // a distance interval's end: its start, which the line joins whatever the gap
    val fromUs: Long? = null,
)

public object PlotTiles {
    public const val baseSlotUs: Long = 16_000_000L

    public fun level(slotUs: Long): Int? {
        if (slotUs < baseSlotUs || slotUs % baseSlotUs != 0L) return null
        val ratio = slotUs / baseSlotUs
        if (ratio and (ratio - 1) != 0L) return null
        return ratio.countTrailingZeroBits()
    }

    public fun parts(range: TimeRange, level: Int): LongRange {
        val slotUs = baseSlotUs shl level
        if (range.endUs <= range.startUs) return LongRange.EMPTY
        return range.startUs.floorDiv(slotUs)..(range.endUs - 1).floorDiv(slotUs)
    }
}

public class EnergyPerDistancePlotReducer
internal constructor(
    private val metric: MetricId,
    range: TimeRange,
    buckets: Int,
    slotUs: Long?,
    private val lane: WhPerKmLane,
    private val series: EnergyPerDistanceSeries?,
    private val cutUs: Long,
) : TelemetryConsumer {
    public constructor(
        metric: MetricId,
        source: DistanceSource,
        range: TimeRange,
        buckets: Int,
        slotUs: Long?,
        smoothingUs: Long,
    ) : this(metric, range, buckets, slotUs, WhPerKmLane(metric, source, smoothingUs), null, Long.MIN_VALUE)

    internal val plot = PlotReducer(metric, range, slotUs, buckets)

    init {
        require(metric == MetricId.batteryWhPerKm || metric == MetricId.riderWhPerKm) {
            "$metric is not a Wh/km metric"
        }
    }

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            val reading = lane.reading(rows, row, session)
            plot.plotted(reading.utcUs, reading.elapsedUs, reading.value, reading.order, reading.activeInterval, 0)
            if (series != null && reading.elapsedUs < cutUs) series.fold(rows, row, session)
        }
    }

    public fun append(fact: Fact) {
        if (fact !is Fact.Location) return
        lane.speed(fact.fix)
        if (series != null && fact.fix.elapsedUs < cutUs) series.fold(fact.fix)
    }

    public fun result(): Bounded<List<PlotPoint>> = plot.result()
}

public class EnergyPerDistanceSeries(
    private val metric: MetricId,
    private val source: DistanceSource,
    private val smoothingUs: Long,
) {
    private class Fold(
        val index: Long,
        val segment: Int,
        var first: PlotOriginal,
        var min: PlotOriginal,
        var max: PlotOriginal,
        var last: PlotOriginal,
    )

    private val lane = WhPerKmLane(metric, source, smoothingUs)
    private val folds = mutableListOf<Fold>()
    private var foldSlotUs = SECOND_US
    private var segment = 0
    private var previous: PlotOriginal? = null
    private var broken = false
    private var log: MutableList<Pair<Long, PlotOriginal?>>? = mutableListOf()
    private var through = -1L
    private val gapProbe = PlotReducer(metric, TimeRange(0, Long.MAX_VALUE), SECOND_US, 1)

    public val throughUs: Long
        get() = through

    public fun begin(endUs: Long, buckets: Int, slotUs: Long?): EnergyPerDistancePlotReducer {
        val reducer =
            EnergyPerDistancePlotReducer(
                metric,
                TimeRange(0, endUs),
                buckets,
                slotUs,
                lane.copy(),
                this,
                endUs - CUT_MARGIN_US,
            )
        if (slotUs == null) {
            val replay = requireNotNull(log) { "an exact-width Wh/km plot ends before ${EXACT_WIDTH_US / SECOND_US} s" }
            for ((elapsedUs, original) in replay) reducer.plot.replay(elapsedUs, original)
        } else seed(reducer.plot, slotUs)
        return reducer
    }

    internal fun fold(rows: TelemetryRows, row: Int, session: ControllerSession) {
        val reading = lane.reading(rows, row, session)
        add(
            reading.elapsedUs,
            gapProbe.original(
                reading.utcUs,
                reading.elapsedUs,
                reading.value,
                reading.order,
                reading.activeInterval,
                0,
            ),
        )
    }

    internal fun fold(fix: LocationFix) {
        lane.speed(fix)
        through = maxOf(through, fix.elapsedUs)
    }

    private fun add(elapsedUs: Long, original: PlotOriginal?) {
        through = maxOf(through, elapsedUs)
        log?.add(elapsedUs to original)
        if (through >= EXACT_WIDTH_US) log = null
        if (original == null) {
            broken = true
            return
        }
        val old = previous
        if (old != null && (broken || gapProbe.starts(old, original))) segment++
        broken = false
        previous = original
        val index = elapsedUs / foldSlotUs
        val last = folds.lastOrNull()
        if (last != null && last.index == index && last.segment == segment) {
            last.last = original
            if (lower(original, last.min)) last.min = original
            if (higher(original, last.max)) last.max = original
        } else folds += Fold(index, segment, original, original, original, original)
        if (folds.size > MAX_FOLDS) coarsen()
    }

    private fun coarsen() {
        foldSlotUs *= 2
        val merged = mutableListOf<Fold>()
        for (fold in folds) {
            val index = fold.index / 2
            val last = merged.lastOrNull()
            if (last != null && last.index == index && last.segment == fold.segment) merge(last, fold)
            else merged += Fold(index, fold.segment, fold.first, fold.min, fold.max, fold.last)
        }
        folds.clear()
        folds += merged
    }

    private fun seed(plot: PlotReducer, slotUs: Long) {
        require(slotUs % foldSlotUs == 0L) { "a Wh/km series holds ${foldSlotUs} µs slots, not a divisor of $slotUs" }
        val factor = slotUs / foldSlotUs
        val merged = mutableListOf<Fold>()
        for (fold in folds) {
            val index = fold.index / factor
            val last = merged.lastOrNull()
            if (last != null && last.index == index && last.segment == fold.segment) merge(last, fold)
            else merged += Fold(index, fold.segment, fold.first, fold.min, fold.max, fold.last)
        }
        val open = merged.lastOrNull()?.takeIf { it.segment == segment && !broken }
        val closed = if (open == null) merged else merged.dropLast(1)
        val points = closed.map { PlotPoint(it.first.ref, it.min.ref, it.max.ref, it.last.ref, it.segment) }
        val originals = open?.let {
            listOf(it.first, it.min, it.max, it.last)
                .distinct()
                .sortedWith(compareBy({ it.ref.elapsedUs }, { it.order }))
        }
        plot.seed(points, originals.orEmpty(), segment, previous, broken)
    }

    private fun merge(into: Fold, fold: Fold) {
        into.last = fold.last
        if (lower(fold.min, into.min)) into.min = fold.min
        if (higher(fold.max, into.max)) into.max = fold.max
    }

    private fun lower(next: PlotOriginal, kept: PlotOriginal): Boolean =
        next.value < kept.value ||
            next.value == kept.value &&
                (next.ref.elapsedUs < kept.ref.elapsedUs ||
                    next.ref.elapsedUs == kept.ref.elapsedUs && next.order < kept.order)

    private fun higher(next: PlotOriginal, kept: PlotOriginal): Boolean =
        next.value > kept.value ||
            next.value == kept.value &&
                (next.ref.elapsedUs < kept.ref.elapsedUs ||
                    next.ref.elapsedUs == kept.ref.elapsedUs && next.order < kept.order)

    private companion object {
        const val SECOND_US = 1_000_000L
        const val CUT_MARGIN_US = 2_500_000L
        const val EXACT_WIDTH_US = 512_000_000L
        const val MAX_FOLDS = 2_048
    }
}

internal class WhPerKmReading(
    val utcUs: Long,
    val elapsedUs: Long,
    val value: Double?,
    val order: Long,
    val activeInterval: Int,
)

internal class WhPerKmLane(
    private val metric: MetricId,
    private val source: DistanceSource,
    private val smoothingUs: Long,
) {
    private class Reading(val time: Long, val value: Double)

    private class Speed(val time: Long, val mps: Double, val activeInterval: Int)

    private val smoothing = ArrayDeque<Reading>()
    private var area = 0.0
    private var started = false
    private var activeInterval = 0
    private var run = 0
    private var gps: Speed? = null
    private val power = if (metric == MetricId.batteryWhPerKm) MetricId.motorInputPowerW else MetricId.humanPowerW
    private val powerRange = MetricCatalog.of(power).validRange

    init {
        require(smoothingUs in EnergyPerDistancePolicy.smoothingUs) {
            "Wh/km smooths over one of the policy's spans: $smoothingUs"
        }
    }

    fun copy(): WhPerKmLane {
        val copy = WhPerKmLane(metric, source, smoothingUs)
        copy.smoothing.addAll(smoothing)
        copy.area = area
        copy.started = started
        copy.activeInterval = activeInterval
        copy.run = run
        copy.gps = gps
        return copy
    }

    fun speed(fix: LocationFix) {
        val interval = fix.activeInterval
        val mps = fix.speedMps
        val accuracy = fix.speedAccuracyMps
        val valid =
            interval != null &&
                mps != null &&
                mps.isFinite() &&
                speedRange?.contains(mps) != false &&
                (accuracy == null || accuracy.isFinite() && accuracy >= 0)
        gps = if (valid) Speed(fix.elapsedUs, requireNotNull(mps), requireNotNull(interval)) else null
    }

    fun reading(rows: TelemetryRows, row: Int, session: ControllerSession): WhPerKmReading {
        val time = rows.elapsedUs[row].toLong()
        val interval = rows.activeInterval[row]
        val run = rows.run[row]
        val speed =
            if (source == DistanceSource.controller)
                rows.value(MetricId.controllerSpeedMps, row, session)?.takeIf { controllerRange?.contains(it) != false }
            else gps?.takeIf { it.activeInterval == interval && time - it.time in 0..GPS_HOLD_US }?.mps
        val watts = if (power in session.capabilities.metrics) rows.value(power, row, session) else null
        val valid =
            interval != 0 &&
                speed != null &&
                speed > 0 &&
                watts != null &&
                watts >= 0 &&
                watts.isFinite() &&
                powerRange?.contains(watts) != false
        val raw = if (valid) requireNotNull(watts) / (requireNotNull(speed) * KMH_PER_MPS) else null
        val order = rows.sessionNo[row].toLong() * SEQ_SPAN + rows.seq[row].toLong()
        return WhPerKmReading(
            rows.utcUs[row].toLong(),
            time,
            smooth(time, raw?.takeIf { it.isFinite() }, interval, run),
            order,
            interval,
        )
    }

    private fun smooth(time: Long, raw: Double?, interval: Int, run: Int): Double? {
        val previous = smoothing.lastOrNull()
        val interrupted =
            !started ||
                activeInterval != interval ||
                this.run != run ||
                previous != null && time - previous.time !in 1..EnergyPerDistancePolicy.controllerIntervalUs
        if (raw == null || interrupted) {
            smoothing.clear()
            area = 0.0
        }
        started = true
        activeInterval = interval
        this.run = run
        if (raw == null || smoothingUs == 0L) return raw
        smoothing.lastOrNull()?.let { area += (it.value + raw) * (time - it.time) / 2 }
        smoothing.addLast(Reading(time, raw))
        val start = time - smoothingUs
        while (smoothing.size > 2 && smoothing[1].time <= start) {
            val a = smoothing.removeFirst()
            val b = smoothing.first()
            area -= (a.value + b.value) * (b.time - a.time) / 2
        }
        val first = smoothing.first()
        var covered = area
        if (first.time < start && smoothing.size > 1) {
            val second = smoothing[1]
            val boundary =
                first.value +
                    (second.value - first.value) * (start - first.time).toDouble() / (second.time - first.time)
            covered -= (first.value + boundary) * (start - first.time) / 2
        }
        val span = time - maxOf(start, first.time)
        return if (span > 0) maxOf(0.0, covered / span) else raw
    }

    private companion object {
        val speedRange = MetricCatalog.of(MetricId.speedMps).validRange
        val controllerRange = MetricCatalog.of(MetricId.controllerSpeedMps).validRange
        const val GPS_HOLD_US = 10_000_000L
        const val KMH_PER_MPS = 3.6
        const val SEQ_SPAN = 1L shl 40
    }
}
