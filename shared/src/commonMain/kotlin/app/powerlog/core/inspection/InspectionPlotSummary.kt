package app.powerlog.core.inspection

import app.powerlog.core.analytics.*

object InspectionPlotSlots {
    const val maximumPoints = 16384
    private const val secondUs = 1000000L

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun alignedSlotUs(range: AnalysisRange, buckets: Int): Long? {
        require(buckets in 1..512)
        val span = FixedPoint.subtract(range.endUs, range.startUs)
        if (span < secondUs * buckets) return null
        var slot = secondUs
        while (FixedPoint.product(slot, buckets.toLong()) < span) slot = FixedPoint.product(slot, 2)
        return slot
    }

    fun pointLimit(metrics: Int): Int = maximumPoints / maxOf(1, metrics)

    @Throws(IllegalArgumentException::class)
    fun buckets(requested: Int, metrics: Int): Int {
        require(requested in 1..512)
        return maxOf(1, minOf(requested, pointLimit(metrics) / 4 - 2))
    }
}

class InspectionPlotSummary
@Throws(IllegalArgumentException::class)
constructor(
    val metric: String,
    val maximumSlots: Int = 512,
    val pointBudget: Int = InspectionPlotSlots.maximumPoints,
    val chunkInputs: Int = 64,
) {
    @Throws(IllegalArgumentException::class) constructor(metric: String) : this(metric, 512)

    private enum class Form {
        item,
        compact,
        distance,
        compactDistance,
    }

    private sealed class Input(val timeUs: Long, val form: Form) {
        abstract fun feed(reducer: InspectionPlotReducer)
    }

    private class ItemInput(val item: AnalysisItem, val boundary: Boolean) : Input(item.collectionUs, Form.item) {
        override fun feed(reducer: InspectionPlotReducer) = reducer.append(item, true, boundary)
    }

    private class CompactInput(val point: InspectionCompactPoint) : Input(point.timeUs, Form.compact) {
        override fun feed(reducer: InspectionPlotReducer) = reducer.appendCompact(point)
    }

    private class DistanceInput(val boundary: DistanceInspectionPoint) : Input(boundary.point.timeUs, Form.distance) {
        override fun feed(reducer: InspectionPlotReducer) = reducer.appendDistance(boundary)
    }

    private class CompactDistanceInput(val point: InspectionCompactPoint, val plotSegment: Int, val tieKey: Long) :
        Input(point.timeUs, Form.compactDistance) {
        override fun feed(reducer: InspectionPlotReducer) = reducer.appendCompactDistance(point, plotSegment, tieKey)
    }

    private class Reduction(
        val aggregate: InspectionPlotAggregate,
        val first: InspectionPoint?,
        val last: InspectionPoint?,
    )

    private class Slot(val index: Long, val reduction: Reduction)

    private var form: Form? = null
    private var callIdentity: ((Long) -> String)? = null
    private val scopedIdentity: (Long) -> String = { checkNotNull(callIdentity)(it) }
    private val completed = mutableListOf<Slot>()
    private var openIndex = 0L
    private var open: Reduction? = null
    private val buffer = mutableListOf<Input>()
    private var aggregatePoints = 0

    var slotUs = 1000000L
        private set

    var firstRawUs: Long? = null
        private set

    var lastRawUs: Long? = null
        private set

    var rawInputs = 0L
        private set

    var overflowed = false
        private set

    val retainedPoints: Int
        get() = aggregatePoints + buffer.size

    init {
        require(maximumSlots in 1..512 && pointBudget in 1..InspectionPlotSlots.maximumPoints && chunkInputs >= 1)
        InspectionMetrics.plotGapUs(metric)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun append(item: AnalysisItem, selected: Boolean = true, interruptionBoundary: Boolean = false) {
        require(!InspectionMetrics.isProjected(metric)) {
            "Distance plotting requires store segments and numeric point IDs"
        }
        if (!selected || !InspectionMetrics.isReading(item, metric)) return
        admit(ItemInput(item, interruptionBoundary))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendCompact(point: InspectionCompactPoint, identity: (Long) -> String, selected: Boolean = true) {
        require(!InspectionMetrics.isProjected(metric))
        if (!selected) return
        callIdentity = identity
        try {
            admit(CompactInput(point))
        } finally {
            callIdentity = null
        }
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendDistance(boundary: DistanceInspectionPoint) {
        require(InspectionMetrics.isProjected(metric) && Metrics.plotValid(metric, boundary.point.value))
        admit(DistanceInput(boundary))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendCompactDistance(point: InspectionCompactPoint, plotSegment: Int, tieKey: Long) {
        require(InspectionMetrics.isProjected(metric) && Metrics.plotValid(metric, point.value))
        admit(CompactDistanceInput(point, plotSegment, tieKey))
    }

    fun canMergeInto(reducer: InspectionPlotReducer): Boolean {
        if (overflowed || reducer.metric != metric) return false
        val slot = reducer.alignedSlotUs ?: return false
        if (slot % slotUs != 0L) return false
        val first = firstRawUs ?: return true
        if (first < reducer.range.startUs || requireNotNull(lastRawUs) > reducer.range.endUs) return false
        if (compact() && reducer.identity == null) return false
        val head = completed.firstOrNull()?.reduction ?: open
        return if (head != null) reducer.canMerge(head.aggregate) else reducer.admits(buffer.first().timeUs)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun mergeInto(reducer: InspectionPlotReducer) {
        require(canMergeInto(reducer)) { "The destination slot must be a multiple of the summary slot over its inputs" }
        for (slot in completed) merge(reducer, rebound(slot.reduction, reducer))
        open?.let { merge(reducer, rebound(it, reducer)) }
        for (input in buffer) input.feed(reducer)
    }

    private fun compact(): Boolean = form == Form.compact || form == Form.compactDistance

    private fun merge(reducer: InspectionPlotReducer, reduction: Reduction) =
        reducer.merge(reduction.aggregate, reduction.first, reduction.last)

    private fun reduction(reducer: InspectionPlotReducer) =
        Reduction(reducer.aggregate(), reducer.firstPoint, reducer.lastPoint)

    private fun rebound(reduction: Reduction, reducer: InspectionPlotReducer): Reduction {
        val aggregate = reduction.aggregate
        if (!compact() || aggregate.points.isEmpty()) return reduction
        val identity = requireNotNull(reducer.identity)
        return Reduction(
            InspectionPlotAggregate(
                metric,
                aggregate.range,
                aggregate.points.map { InspectionPlotPoint(it.point.withIdentity(identity), it.segment, it.tieKey) },
                aggregate.firstRawUs,
                aggregate.lastRawUs,
                aggregate.leadingBarrier,
                aggregate.trailingBarrier,
                aggregate.completeSegments,
            ),
            reduction.first?.withIdentity(identity),
            reduction.last?.withIdentity(identity),
        )
    }

    private fun admit(input: Input) {
        if (overflowed) throw InspectionCapacityException()
        require(form == null || form == input.form) { "A plot summary takes one kind of input" }
        val last = lastRawUs
        require(last == null || input.timeUs >= last) { "Plot originals must be ordered and disjoint" }
        val first = firstRawUs ?: input.timeUs
        val level =
            maxOf(slotUs, InspectionPlotSlots.alignedSlotUs(AnalysisRange(first, input.timeUs), maximumSlots) ?: slotUs)
        try {
            if (level != slotUs) coarsen(level)
            val index = input.timeUs / slotUs
            when {
                last == null -> openIndex = index
                index != openIndex -> {
                    completed.add(Slot(openIndex, reduce(slotRange(openIndex))))
                    open = null
                    openIndex = index
                }
                buffer.size >= chunkInputs && input.timeUs > last ->
                    open = reduce(AnalysisRange(slotRange(openIndex).startUs, input.timeUs))
            }
            buffer.add(input)
            if (retainedPoints > pointBudget) throw InspectionCapacityException()
        } catch (failure: Throwable) {
            overflowed = true
            completed.clear()
            open = null
            buffer.clear()
            aggregatePoints = 0
            throw failure
        }
        form = input.form
        firstRawUs = first
        lastRawUs = input.timeUs
        rawInputs++
    }

    private fun owned(range: AnalysisRange) =
        InspectionPlotReducer(
            metric,
            range,
            1,
            InspectionPlotSlots.maximumPoints,
            owned = true,
            identity = scopedIdentity,
        )

    private fun reduce(range: AnalysisRange): Reduction {
        val reducer = owned(range)
        open?.let { merge(reducer, it) }
        for (input in buffer) input.feed(reducer)
        val reduction = reduction(reducer)
        aggregatePoints += reduction.aggregate.points.size - (open?.aggregate?.points?.size ?: 0)
        buffer.clear()
        return reduction
    }

    private fun coarsen(level: Long) {
        val factor = level / slotUs
        val openEnd = open?.aggregate?.range?.endUs ?: slotRange(openIndex).startUs
        val children = completed.toList()
        val siblings = mutableListOf<Reduction>()
        completed.clear()
        slotUs = level
        openIndex /= factor
        var start = 0
        while (start < children.size) {
            val parent = children[start].index / factor
            var end = start + 1
            while (end < children.size && children[end].index / factor == parent) end++
            val group = children.subList(start, end).map { it.reduction }
            if (parent == openIndex) siblings += group
            else if (group.size == 1) completed.add(Slot(parent, group[0]))
            else completed.add(Slot(parent, combine(slotRange(parent), group)))
            start = end
        }
        if (siblings.isNotEmpty())
            open = combine(AnalysisRange(slotRange(openIndex).startUs, openEnd), siblings + listOfNotNull(open))
        aggregatePoints = completed.sumOf { it.reduction.aggregate.points.size } + (open?.aggregate?.points?.size ?: 0)
    }

    private fun combine(range: AnalysisRange, reductions: List<Reduction>): Reduction {
        val reducer = owned(range)
        reductions.forEach { merge(reducer, it) }
        return reduction(reducer)
    }

    // Slots follow the reducer's truncating `timeUs / slotUs`, so slot 0 spans (-slotUs, slotUs).
    private fun slotRange(index: Long): AnalysisRange =
        when {
            index > 0 -> FixedPoint.product(index, slotUs).let { AnalysisRange(it, FixedPoint.add(it, slotUs)) }
            index == 0L -> AnalysisRange(1 - slotUs, slotUs)
            else ->
                AnalysisRange(
                    FixedPoint.add(FixedPoint.product(index - 1, slotUs), 1),
                    FixedPoint.add(FixedPoint.product(index, slotUs), 1),
                )
        }
}
