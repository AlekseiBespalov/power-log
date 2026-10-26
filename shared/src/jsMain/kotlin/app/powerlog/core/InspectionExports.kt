@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.analytics.*
import app.powerlog.core.gps.*
import app.powerlog.core.inspection.*
import app.powerlog.core.protocol.Timestamps
import kotlin.js.JsExport

@JsExport
class GPSLocationInput(
    val timestamp: String,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double,
) {
    internal fun core() = GPSOriginal(Timestamps.parse(timestamp), latitude, longitude, horizontalAccuracyM)
}

@JsExport
class GPSTimelineInput(
    val epoch: String,
    val startedAtUs: String,
    val monotonicOrigin: Double,
    val epochStart: Double?,
    val stopMonotonic: Double?,
    val uncertain: Boolean,
    val startedAtSubmicrosecondNanos: Int = 0,
) {
    internal fun core() =
        GPSTimeline(
            epoch,
            startedAtUs.toLong(),
            monotonicOrigin,
            epochStart,
            stopMonotonic,
            uncertain,
            startedAtSubmicrosecondNanos,
        )
}

@JsExport
class GPSMappedFix internal constructor(internal val fix: GPSAcquisitionFix) {
    val timestamp = fix.original.timestamp.original
    val latitude = fix.original.latitude
    val longitude = fix.original.longitude
    val horizontalAccuracyM = fix.original.horizontalAccuracyM
    val elapsedSeconds = fix.elapsedSeconds
    val elapsedUs = fix.elapsedUs.toString()
    val acquisitionMonotonic = fix.acquisitionMonotonic
    val epoch = fix.epoch
    val uncertain = fix.uncertain
}

@JsExport
class GPSMappedBatch internal constructor(batch: GPSMappingBatch) {
    val fixes = batch.fixes.map(::GPSMappedFix).toTypedArray()
    val discontinuity = batch.discontinuity
}

@JsExport
class GPSAcquisitionClock {
    private val clock = GPSAcquisition()

    fun mapBatch(
        originals: Array<GPSLocationInput>,
        receiptUtcUs: String,
        monotonicSeconds: Double,
        timeline: GPSTimelineInput,
    ): GPSMappedBatch =
        GPSMappedBatch(
            clock.mapBatch(
                originals.map { it.core() },
                GPSReceipt(receiptUtcUs.toLong(), monotonicSeconds),
                timeline.core(),
            )
        )

    fun admit(fix: GPSMappedFix): Boolean = clock.admit(fix.fix)

    fun mapBatchAt(
        originals: Array<GPSLocationInput>,
        receiptTimestamp: String,
        monotonicSeconds: Double,
        timeline: GPSTimelineInput,
    ): GPSMappedBatch =
        GPSMappedBatch(
            clock.mapBatch(
                originals.map { it.core() },
                GPSReceipt(Timestamps.parse(receiptTimestamp), monotonicSeconds),
                timeline.core(),
            )
        )
}

@JsExport
class InspectionMetricPolicy(metric: String) {
    private val policy = InspectionMetrics.policy(metric)
    val channel = policy.channel.name
    val plotGapUs = policy.plotGapUs.toString()
    val integrationGapUs = policy.statistics.gapUs.toString()
    val suppressed = policy.statistics.suppressed
    val minimum = policy.statistics.minimum
    val maximum = policy.statistics.maximum
}

private fun nearbyInspectionTarget(
    target: InspectionTarget,
    metric: String,
    originals: Array<InspectionOriginalInput>,
): InspectionTarget {
    if (target.anchor != null) return target
    val near =
        InspectionOrder.nearest(target.timeUs, originals.mapNotNull { it.renderedPoint(metric) })?.takeIf {
            FixedPoint.subtract(it.timeUs, target.timeUs) in -1L..1L
        }
    return near?.let { target.copy(anchor = InspectionAnchor(it.identity(), it.timeUs)) } ?: target
}

@JsExport
class InspectionTargetInput(val timeUs: String, val observationId: String?, val anchorTimeUs: String?) {
    fun withNearbyOriginal(metric: String, originals: Array<InspectionOriginalInput>): InspectionTargetInput {
        if (observationId != null) return this
        val target = nearbyInspectionTarget(core(), metric, originals)
        return target.anchor?.let { InspectionTargetInput(timeUs, it.observationId, it.timeUs.toString()) } ?: this
    }

    internal fun core(): InspectionTarget {
        require((observationId == null) == (anchorTimeUs == null))
        return InspectionTarget(
            timeUs.toLong(),
            observationId?.let { InspectionAnchor(it, requireNotNull(anchorTimeUs).toLong()) },
        )
    }
}

@JsExport
class InspectionDistanceBoundary(
    item: AnalyticsItem,
    segment: Int,
    startUs: String,
    endUs: String,
    tieKey: String,
    plotSegment: Int = segment,
    metric: String = "distanceMeters",
) {
    init {
        require(InspectionMetrics.isProjected(metric))
    }

    internal val point =
        item.core().let { original ->
            DistanceInspectionPoint(
                InspectionPoint(
                    original.collectionUs,
                    requireNotNull(original.original(metric)),
                    original.continuity,
                ),
                segment,
                startUs.toLong(),
                endUs.toLong(),
                tieKey.toLong(),
                plotSegment,
            )
        }
}

@JsExport
fun inspectionDistanceOriginal(
    target: InspectionTargetInput,
    before: InspectionDistanceBoundary?,
    after: InspectionDistanceBoundary?,
    anchored: InspectionDistanceBoundary?,
): AnalyticsOriginal? =
    DistanceInspection.select(target.core(), before?.point, after?.point, anchored?.point)?.let(::AnalyticsOriginal)

@JsExport
class InspectionQueryResult internal constructor(result: InspectionResult) {
    val outcome =
        when (val statistics = result.statistics) {
            null -> "absent"
            is MetricOutcome.Complete -> "complete"
            is MetricOutcome.Overflow -> "numericOverflow:${statistics.failure.metric}:${statistics.failure.quantity}"
        }
    val statistics = (result.statistics as? MetricOutcome.Complete)?.result?.let(::AnalyticsStatistics)
    val selection = result.selection?.let(::AnalyticsOriginal)
    val reference = result.reference?.let(::AnalyticsOriginal)
}

@JsExport
class InspectionMetricQuery(
    metric: String,
    startUs: String,
    endUs: String,
    rideEndUs: String,
    lifecycle: Array<AnalyticsItem>,
    selection: InspectionTargetInput?,
    reference: InspectionTargetInput?,
    allowTrailing: Boolean,
) {
    private val query =
        InspectionQuery(
            metric,
            AnalysisRange(startUs.toLong(), endUs.toLong()),
            ActiveTimeline(rideEndUs.toLong(), lifecycle.map { it.core() }),
            selection?.core(),
            reference?.core(),
            allowTrailing,
        )

    fun append(item: AnalyticsItem, selected: Boolean) = query.append(item.core(), selected)

    fun appendInspection(item: AnalyticsItem, selected: Boolean) = query.appendInspection(item.core(), selected)

    fun appendRetained(item: AnalyticsItem, activeInterval: Int?, interruptionBoundary: Boolean = false) =
        query.appendRetained(item.core(), activeInterval, interruptionBoundary)

    fun appendPreparedInspection(
        item: InspectionOriginalInput,
        selected: Boolean,
        retained: Boolean,
        boundary: Boolean,
    ) {
        if (selected) {
            if (retained) query.appendRetainedInspection(item.core, boundary) else query.appendInspection(item.core)
        }
    }

    fun checkpoint(): InspectionMetricCheckpoint? = query.checkpoint()?.let { InspectionMetricCheckpoint(it) }

    fun canMerge(checkpoint: InspectionMetricCheckpoint): Boolean = query.canMerge(checkpoint.core)

    fun mergeOwned(checkpoint: InspectionMetricCheckpoint) = query.mergeOwned(checkpoint.core)

    fun restore(checkpoint: InspectionMetricCheckpoint) = query.restore(checkpoint.core)

    fun result(): InspectionQueryResult = InspectionQueryResult(query.result())
}

@JsExport
class InspectionMetricCheckpoint(payload: String?) {
    private var encoded = payload
    private var state = payload?.let(InspectionCacheCodec::readStatistics)
    internal val core: MetricCheckpoint
        get() = requireNotNull(state)

    @JsExport.Ignore
    internal constructor(value: MetricCheckpoint) : this(null) {
        state = value
    }

    val payload: String
        get() = encoded ?: InspectionCacheCodec.statistics(core).also { encoded = it }
}

@JsExport
class InspectionMetricBucket(
    metric: String,
    startUs: String,
    endUs: String,
    rideEndUs: String,
    lifecycle: Array<AnalyticsItem>,
    predecessor: AnalyticsItem?,
) {
    private val query =
        InspectionQuery(
            metric,
            AnalysisRange(startUs.toLong(), endUs.toLong()),
            ActiveTimeline(rideEndUs.toLong(), lifecycle.map { it.core() }),
            bucket = true,
            predecessor = predecessor?.core(),
        )

    fun append(item: AnalyticsItem, selected: Boolean) = query.append(item.core(), selected)

    fun appendPrepared(item: InspectionOriginalInput, selected: Boolean) = query.append(item.core, selected)

    fun inheritPredecessor(checkpoint: InspectionMetricCheckpoint) = query.inheritPredecessor(checkpoint.core)

    fun canMerge(checkpoint: InspectionMetricCheckpoint): Boolean = query.canMerge(checkpoint.core)

    fun mergeOwned(checkpoint: InspectionMetricCheckpoint) = query.mergeOwned(checkpoint.core)

    fun checkpoint(): InspectionMetricCheckpoint? = query.checkpoint()?.let { InspectionMetricCheckpoint(it) }
}

@JsExport
class InspectionCacheEncoder {
    private val encoding = InspectionCacheEncoding()

    fun statistics(checkpoint: InspectionMetricCheckpoint): String = encoding.statistics(checkpoint.core)

    fun plot(cache: InspectionPlotCache): String = encoding.plot(cache.core)
}

@JsExport
class InspectionPlotCache(payload: String?) {
    private var encoded = payload
    private var state = payload?.let(InspectionCacheCodec::readPlot)
    internal val core: InspectionPlotAggregate
        get() = requireNotNull(state)

    @JsExport.Ignore
    internal constructor(value: InspectionPlotAggregate) : this(null) {
        state = value
    }

    val payload: String
        get() = encoded ?: InspectionCacheCodec.plot(core).also { encoded = it }
}

@JsExport
class InspectionOriginalInput(item: AnalyticsItem) {
    internal val core = item.core()
    private var rendered: MutableMap<String, InspectionPoint?>? = null

    internal fun renderedPoint(metric: String): InspectionPoint? {
        val points = rendered ?: mutableMapOf<String, InspectionPoint?>().also { rendered = it }
        if (points.containsKey(metric)) return points[metric]
        return core
            .takeIf { Metrics.plotValid(it, metric) }
            ?.let { inspectionPoint(it, metric) }
            .also {
                points[metric] = it
            }
    }

    fun isReading(metric: String): Boolean = InspectionMetrics.isReading(core, metric)

    fun plotValid(metric: String): Boolean =
        InspectionMetrics.isReading(core, metric) && Metrics.plotValid(core, metric)
}

@JsExport
class InspectionPlotBucket(metric: String, startUs: String, endUs: String) {
    private val reducer =
        InspectionPlotReducer(metric, AnalysisRange(startUs.toLong(), endUs.toLong()), 1, owned = true)

    fun append(item: AnalyticsItem, selected: Boolean) = reducer.append(item.core(), selected)

    fun appendOriginal(item: InspectionOriginalInput, selected: Boolean) = reducer.append(item.core, selected)

    fun appendDistance(point: InspectionDistanceBoundary) = reducer.appendDistance(point.point)

    fun canMerge(aggregate: InspectionPlotCache): Boolean = reducer.canMerge(aggregate.core)

    fun merge(aggregate: InspectionPlotCache) = reducer.merge(aggregate.core)

    fun aggregate(): InspectionPlotCache = InspectionPlotCache(reducer.aggregate())
}

@JsExport
class InspectionPlotOriginal internal constructor(point: InspectionPlotPoint) {
    val original = AnalyticsOriginal(point.point.original)
    val timeUs = point.point.timeUs.toString()
    val segment = point.segment
}

@JsExport
class InspectionPlotQuery(
    metric: String,
    startUs: String,
    endUs: String,
    buckets: Int,
    pointLimit: Int,
    alignedSlotUs: String? = null,
) {
    internal val reducer =
        InspectionPlotReducer(
            metric,
            AnalysisRange(startUs.toLong(), endUs.toLong()),
            buckets,
            pointLimit,
            alignedSlotUs = alignedSlotUs?.toLong(),
        )

    fun append(item: AnalyticsItem, selected: Boolean, interruptionBoundary: Boolean = false) =
        reducer.append(item.core(), selected, interruptionBoundary)

    fun appendDistance(point: InspectionDistanceBoundary) = reducer.appendDistance(point.point)

    fun canMerge(aggregate: InspectionPlotCache): Boolean = reducer.canMerge(aggregate.core)

    fun merge(aggregate: InspectionPlotCache) = reducer.merge(aggregate.core)

    fun result(): Array<InspectionPlotOriginal> = reducer.result().map(::InspectionPlotOriginal).toTypedArray()
}

@JsExport
fun inspectionPlotSlotUs(startUs: String, endUs: String, buckets: Int): String? =
    InspectionPlotSlots.alignedSlotUs(AnalysisRange(startUs.toLong(), endUs.toLong()), buckets)?.toString()

@JsExport fun inspectionPlotBuckets(requested: Int, metrics: Int): Int = InspectionPlotSlots.buckets(requested, metrics)

@JsExport fun inspectionPlotPointLimit(metrics: Int): Int = InspectionPlotSlots.pointLimit(metrics)

@JsExport
class InspectionPlotSlotSummary(
    metric: String,
    maximumSlots: Int = 512,
    pointBudget: Int = InspectionPlotSlots.maximumPoints,
) {
    private val summary = InspectionPlotSummary(metric, maximumSlots, pointBudget)
    val slotUs: String
        get() = summary.slotUs.toString()

    val firstRawUs: String?
        get() = summary.firstRawUs?.toString()

    val lastRawUs: String?
        get() = summary.lastRawUs?.toString()

    val rawInputs: String
        get() = summary.rawInputs.toString()

    val retainedPoints: Int
        get() = summary.retainedPoints

    val overflowed: Boolean
        get() = summary.overflowed

    fun append(item: AnalyticsItem, selected: Boolean, interruptionBoundary: Boolean = false) =
        summary.append(item.core(), selected, interruptionBoundary)

    fun appendOriginal(item: InspectionOriginalInput, selected: Boolean, interruptionBoundary: Boolean = false) =
        summary.append(item.core, selected, interruptionBoundary)

    fun appendDistance(point: InspectionDistanceBoundary) = summary.appendDistance(point.point)

    fun canMergeInto(query: InspectionPlotQuery): Boolean = summary.canMergeInto(query.reducer)

    fun mergeInto(query: InspectionPlotQuery) = summary.mergeInto(query.reducer)
}

@JsExport
class InspectionRequestContext(
    val sourceId: String,
    val revision: String,
    val distanceSelection: String,
    val interactionEpoch: String,
    val interactionSequence: String,
) {
    internal fun core() =
        InspectionContext(
            sourceId,
            revision,
            distanceSelection,
            interactionEpoch.toLong(),
            interactionSequence.toLong(),
        )
}

@JsExport
class InspectionRequestTicket internal constructor(internal val ticket: InspectionTicket) {
    val generation = ticket.generation.toString()
}

@JsExport
class InspectionRequestGate {
    private val requests = InspectionRequests()

    fun begin(context: InspectionRequestContext): InspectionRequestTicket =
        InspectionRequestTicket(requests.begin(context.core()))

    fun cancel() = requests.cancel()

    fun accepts(ticket: InspectionRequestTicket): Boolean = requests.accepts(ticket.ticket)

    fun acceptsGeneration(ticket: InspectionRequestTicket, generation: String): Boolean =
        requests.accepts(InspectionTicket(ticket.ticket.context, generation.toLong()))
}

@JsExport
fun inspectionContextsMatch(current: InspectionRequestContext, result: InspectionRequestContext): Boolean =
    InspectionAcceptance.accepts(InspectionTicket(current.core(), 1), InspectionTicket(result.core(), 1))

@JsExport
fun inspectionHeldTail(
    metric: String,
    observationId: String,
    originalSeconds: Double,
    value: Double,
    evidenceObservationId: String,
    acquisitionMonotonic: Double?,
    monotonicNow: Double,
    elapsedNow: Double,
): Double? =
    HeldTail.render(
            metric,
            Original(observationId, observationId, "", originalSeconds, Scalar.Real64(value)),
            evidenceObservationId,
            acquisitionMonotonic,
            monotonicNow,
            elapsedNow,
        )
        ?.endSeconds

@JsExport
fun inspectionRenderedSegment(
    metric: String,
    previousSeconds: Double,
    previousValue: Double,
    seconds: Double,
    value: Double,
    explicit: Boolean?,
): Boolean =
    InspectionOrder.startsRenderedSegment(
        metric,
        InspectionPoint(
            FixedPoint.timeUs(previousSeconds),
            Original("previous", "", "", previousSeconds, Scalar.Real64(previousValue)),
            Continuity(),
        ),
        InspectionPoint(
            FixedPoint.timeUs(seconds),
            Original("point", "", "", seconds, Scalar.Real64(value)),
            Continuity(),
        ),
        explicit,
    )

@JsExport
fun inspectionRenderedOriginal(
    metric: String,
    items: Array<InspectionOriginalInput>,
    segments: IntArray,
    timeUs: String,
): AnalyticsOriginal? {
    require(items.size == segments.size)
    val target = nearbyInspectionTarget(InspectionTarget(timeUs.toLong()), metric, items)
    val query = OriginalInspection(metric, target)
    var right: Pair<InspectionPoint, Boolean?>? = null
    items.forEachIndexed { index, item ->
        val point = item.renderedPoint(metric)
        if (point != null) {
            query.append(point)
            if (point.timeUs >= target.timeUs && (right == null || point.timeUs < requireNotNull(right).first.timeUs))
                right =
                    point to
                        when (segments[index]) {
                            0 -> true
                            1 -> false
                            else -> null
                        }
        }
    }
    return query.resultForRendered(right?.second)?.let(::AnalyticsOriginal)
}
