package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import app.powerlog.core.telemetry.MetricCatalog
import kotlin.math.abs

enum class InspectionChannel {
    telemetry,
    location,
    health,
    distance,
    consumption,
}

data class InspectionPolicy(
    val metric: String,
    val plotGapUs: Long,
    val statistics: MetricPolicy,
    val channel: InspectionChannel,
)

object InspectionMetrics {
    fun isProjected(metric: String): Boolean =
        metric in listOf("distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters") ||
            BatteryConsumptionPolicy.isMetric(metric)

    private val plotGapsUs: Map<String, Long> =
        MetricCatalog.all.associate { it.id to FixedPoint.timeUs(it.gapSeconds) }

    @Throws(IllegalArgumentException::class)
    fun policy(metric: String): InspectionPolicy {
        require(!InspectionMetrics.isProjected(metric)) { "Distance queries use original distance intervals" }
        return InspectionPolicy(metric, plotGapUs(metric), Metrics.monitorPolicy(metric), channel(metric))
    }

    @Throws(IllegalArgumentException::class)
    fun channel(metric: String): InspectionChannel {
        Metrics.policy(metric)
        return when (metric) {
            "heartRateBpm",
            "activeEnergyKcal",
            "basalEnergyKcal" -> InspectionChannel.health
            "speedMps",
            "speedAccuracyMps",
            "altitudeMeters",
            "horizontalAccuracyM",
            "verticalAccuracyM",
            "courseDegrees" -> InspectionChannel.location
            "distanceMeters",
            "gpsDistanceMeters",
            "controllerDistanceMeters" -> InspectionChannel.distance
            "controllerEnergyWh",
            "controllerChargeAh",
            "calculatedEnergyWh",
            "calculatedChargeAh" -> InspectionChannel.consumption
            else -> InspectionChannel.telemetry
        }
    }

    fun isReading(item: AnalysisItem, metric: String): Boolean =
        item.kind == channel(metric).name && item.isReadingOf(metric)

    @Throws(IllegalArgumentException::class)
    fun plotGapUs(metric: String): Long {
        Metrics.policy(metric)
        plotGapsUs[metric]?.let {
            return it
        }
        return when (metric) {
            "speedAccuracyMps" -> 10000000
            else -> 6000000
        }
    }
}

data class InspectionAnchor(val observationId: String, val timeUs: Long)

data class InspectionTarget(val timeUs: Long, val anchor: InspectionAnchor? = null)

data class InspectionCompactPoint(
    val reference: Long,
    val timeUs: Long,
    val value: Scalar?,
    val continuity: Continuity,
    val producer: String,
    val sequence: Long,
    val accuracy: Scalar? = null,
    val interruptionBoundary: Boolean = false,
)

class InspectionPoint
private constructor(
    val timeUs: Long,
    private val retainedOriginal: Original?,
    val continuity: Continuity,
    val producer: String,
    val sequence: Long,
    val reference: MetricValueReference?,
    val interruptionBoundary: Boolean,
) {
    constructor(
        timeUs: Long,
        original: Original,
        continuity: Continuity,
        producer: String = "",
        sequence: Long = 0,
        interruptionBoundary: Boolean = false,
    ) : this(timeUs, original, continuity, producer, sequence, null, interruptionBoundary)

    constructor(
        point: InspectionCompactPoint,
        identity: (Long) -> String,
    ) : this(
        point.timeUs,
        null,
        point.continuity,
        point.producer,
        point.sequence,
        MetricValueReference(point.reference, point.value, identity),
        point.interruptionBoundary,
    )

    val original: Original
        get() = requireNotNull(retainedOriginal) { "Hydrate a compact reference after reduction" }

    val value: Scalar
        get() = requireNotNull(retainedOriginal?.value ?: reference?.value)

    internal val key: Any
        get() = reference?.reference ?: original.observationId

    internal fun identity(): String = retainedOriginal?.observationId ?: requireNotNull(reference).observationId()

    override fun equals(other: Any?): Boolean =
        other is InspectionPoint &&
            timeUs == other.timeUs &&
            retainedOriginal == other.retainedOriginal &&
            continuity == other.continuity &&
            producer == other.producer &&
            sequence == other.sequence &&
            reference?.reference == other.reference?.reference &&
            reference?.value == other.reference?.value &&
            interruptionBoundary == other.interruptionBoundary

    override fun hashCode(): Int =
        listOf(
                timeUs,
                retainedOriginal,
                continuity,
                producer,
                sequence,
                reference?.reference,
                reference?.value,
                interruptionBoundary,
            )
            .hashCode()
}

internal fun inspectionPoint(item: AnalysisItem, metric: String, continuity: Continuity = item.continuity) =
    InspectionPoint(item.collectionUs, requireNotNull(item.original(metric)), continuity, item.producer, item.sequence)

object InspectionOrder {
    fun anchorMatches(target: InspectionTarget, point: InspectionPoint): Boolean {
        val anchor = target.anchor ?: return false
        return point.original.observationId == anchor.observationId &&
            FixedPoint.subtract(point.timeUs, anchor.timeUs) in -1L..1L &&
            FixedPoint.subtract(point.timeUs, target.timeUs) in -1L..1L
    }

    fun nearest(timeUs: Long, points: List<InspectionPoint>): InspectionPoint? = points.minWithOrNull { a, b ->
        val order = abs(FixedPoint.subtract(a.timeUs, timeUs)).compareTo(abs(FixedPoint.subtract(b.timeUs, timeUs)))
        if (order != 0) order else compare(a, b)
    }

    fun compare(a: InspectionPoint, b: InspectionPoint): Int =
        a.timeUs.compareTo(b.timeUs).takeIf { it != 0 }
            ?: if (a.key == b.key) 0 else compareUtf8(a.identity(), b.identity())

    fun startsSegment(
        metric: String,
        previous: InspectionPoint,
        point: InspectionPoint,
        course: Boolean = false,
    ): Boolean =
        previous.continuity.clockEpoch != point.continuity.clockEpoch ||
            previous.continuity.interruptionIndex != point.continuity.interruptionIndex ||
            FixedPoint.subtract(point.timeUs, previous.timeUs) >= InspectionMetrics.plotGapUs(metric) ||
            course && metric == "courseDegrees" && abs(point.value.real() - previous.value.real()) > 180

    fun startsRenderedSegment(
        metric: String,
        previous: InspectionPoint,
        point: InspectionPoint,
        explicit: Boolean?,
    ): Boolean =
        point.timeUs < previous.timeUs ||
            (explicit ?: startsSegment(metric, previous, point)) ||
            metric == "courseDegrees" && abs(point.value.real() - previous.value.real()) > 180
}

class OriginalInspection
@Throws(IllegalArgumentException::class)
constructor(private val metric: String, private val target: InspectionTarget) {
    private var anchored: InspectionPoint? = null
    private var before: InspectionPoint? = null
    private var after: InspectionPoint? = null

    init {
        InspectionMetrics.plotGapUs(metric)
    }

    @Throws(ArithmeticException::class)
    fun append(point: InspectionPoint, interruptionBoundary: Boolean = false) {
        fun neighbourOrder(a: InspectionPoint, b: InspectionPoint): Int =
            if (interruptionBoundary && point.timeUs != target.timeUs)
                compareUtf8(a.producer, b.producer).takeIf { it != 0 } ?: a.sequence.compareTo(b.sequence)
            else InspectionOrder.compare(a, b)
        if (InspectionOrder.anchorMatches(target, point)) anchored = point
        if (point.timeUs <= target.timeUs) {
            val old = before
            if (
                old == null ||
                    point.timeUs > old.timeUs ||
                    point.timeUs == old.timeUs &&
                        (if (interruptionBoundary && point.timeUs < target.timeUs) neighbourOrder(point, old) > 0
                        else neighbourOrder(point, old) < 0)
            )
                before = point
        }
        if (point.timeUs >= target.timeUs) {
            val old = after
            if (
                old == null || point.timeUs < old.timeUs || point.timeUs == old.timeUs && neighbourOrder(point, old) < 0
            )
                after = point
        }
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun result(allowTrailing: Boolean = false, crossedInterruption: Boolean = false): Original? =
        selectionResult(allowTrailing, crossedInterruption, null)

    internal fun resultForRendered(retainedContinuity: Boolean?): Original? =
        selectionResult(false, false, retainedContinuity)

    private fun selectionResult(
        allowTrailing: Boolean,
        crossedInterruption: Boolean,
        retainedContinuity: Boolean?,
    ): Original? {
        anchored?.let {
            return it.original
        }
        val left = before
        val right = after
        listOfNotNull(left, right)
            .filter { it.timeUs == target.timeUs }
            .minWithOrNull { a, b -> InspectionOrder.compare(a, b) }
            ?.let {
                return it.original
            }
        if (
            left == null ||
                right == null && !allowTrailing ||
                crossedInterruption ||
                right != null &&
                    (retainedContinuity == false ||
                        retainedContinuity == null && InspectionOrder.startsSegment(metric, left, right))
        )
            return null
        val gap = InspectionMetrics.plotGapUs(metric)
        return InspectionOrder.nearest(
                target.timeUs,
                listOfNotNull(left, right).filter {
                    retainedContinuity == true || abs(FixedPoint.subtract(it.timeUs, target.timeUs)) < gap
                },
            )
            ?.original
    }

    internal fun crosses(interruptions: List<InterruptionClose>): Boolean {
        val point = before ?: return false
        return interruptions.withIndex().any { (index, close) ->
            point.timeUs <= close.cutoffUs &&
                target.timeUs > close.cutoffUs &&
                point.continuity.interruptionIndex <= index
        }
    }
}

data class DistanceInspectionPoint(
    val point: InspectionPoint,
    val segment: Int,
    val startUs: Long,
    val endUs: Long,
    val tieKey: Long,
    val plotSegment: Int = segment,
)

object DistanceInspection {
    fun plotSegment(previousSegment: Int, previous: InspectionPoint?, next: InspectionPoint): Int =
        if (previous == null) 0
        else
            previousSegment +
                if (
                    previous.continuity.clockEpoch != next.continuity.clockEpoch ||
                        previous.continuity.interruptionIndex != next.continuity.interruptionIndex ||
                        FixedPoint.subtract(next.timeUs, previous.timeUs) >= HeldTail.gapUs("distanceMeters")
                )
                    1
                else 0

    @Throws(ArithmeticException::class)
    fun select(
        target: InspectionTarget,
        before: DistanceInspectionPoint?,
        after: DistanceInspectionPoint?,
        anchored: DistanceInspectionPoint? = null,
    ): Original? {
        if (anchored != null && InspectionOrder.anchorMatches(target, anchored.point)) return anchored.point.original
        val points = listOfNotNull(before, after)
        val order =
            Comparator<DistanceInspectionPoint> { a, b ->
                abs(FixedPoint.subtract(a.point.timeUs, target.timeUs))
                    .compareTo(abs(FixedPoint.subtract(b.point.timeUs, target.timeUs)))
                    .takeIf { it != 0 }
                    ?: a.point.timeUs.compareTo(b.point.timeUs).takeIf { it != 0 }
                    ?: a.tieKey.compareTo(b.tieKey)
            }
        points
            .filter { it.point.timeUs == target.timeUs }
            .minWithOrNull(order)
            ?.let {
                return it.point.original
            }
        if (
            before == null ||
                after == null ||
                before.segment != after.segment ||
                after.endUs <= after.startUs ||
                target.timeUs < after.startUs ||
                target.timeUs > after.endUs
        )
            return null
        return points.minWithOrNull(order)?.point?.original
    }
}

data class InspectionResult(val statistics: MetricOutcome?, val selection: Original?, val reference: Original?)

class InspectionQuery
@Throws(IllegalArgumentException::class)
constructor(
    val metric: String,
    val range: AnalysisRange,
    private val timeline: ActiveTimeline,
    private val selection: InspectionTarget? = null,
    private val reference: InspectionTarget? = null,
    private val allowTrailing: Boolean = false,
    val bucket: Boolean = false,
    predecessor: AnalysisItem? = null,
    compactPredecessor: InspectionCompactPoint? = null,
    private val identity: ((Long) -> String)? = null,
) {
    private val policy = InspectionMetrics.policy(metric).statistics

    private fun reading(item: AnalysisItem) =
        monitorReading(
            item.copy(continuity = item.continuity.copy(interruptionIndex = timeline.interruptionIndex(item))),
            metric,
            timeline.membership(item),
        )

    private fun reading(point: InspectionCompactPoint) =
        MetricReading(
            point.timeUs,
            null,
            timeline.membership(point.timeUs, point.producer, point.sequence, point.continuity.clockEpoch),
            point.continuity.copy(
                interruptionIndex =
                    timeline.interruptionIndex(
                        point.timeUs,
                        point.producer,
                        point.sequence,
                        point.continuity.clockEpoch,
                    )
            ),
            accuracy = point.accuracy,
            reference = MetricValueReference(point.reference, point.value, requireNotNull(identity)),
        )

    private var predecessorAdmission =
        predecessor
            ?.takeIf { InspectionMetrics.isReading(it, metric) }
            ?.let {
                val seed = MetricReducer(policy, AnalysisRange(it.collectionUs, it.collectionUs))
                seed.append(reading(it))
                seed.checkpoint()?.previous
            }
            ?: compactPredecessor?.let {
                val seed = MetricReducer(policy, AnalysisRange(it.timeUs, it.timeUs))
                seed.append(reading(it))
                seed.checkpoint()?.previous
            }
    private var reducer = MetricReducer(policy, range, predecessorAdmission, bucket = bucket)
    private val cursor = selection?.let { OriginalInspection(metric, it) }
    private val baseline = reference?.let { OriginalInspection(metric, it) }
    private var present = false
    private var previousTime: Long? = null
    private var mergedThrough: Long? = null
    private var firstOwned: MetricReading? = null
    private var lastOwned: MetricReading? = null

    private fun owns(timeUs: Long): Boolean =
        timeUs >= range.startUs && (if (bucket) timeUs < range.endUs else timeUs <= range.endUs)

    private fun appendReading(reading: MetricReading) {
        if (bucket && !owns(reading.timeUs)) return
        require(
            previousTime?.let { reading.timeUs >= it } != false && mergedThrough?.let { reading.timeUs >= it } != false
        ) {
            "Inspection originals must be ordered and disjoint"
        }
        previousTime = reading.timeUs
        present = true
        if (owns(reading.timeUs)) {
            if (firstOwned == null) firstOwned = reading
            lastOwned = reading
        }
        reducer.append(reading)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun append(item: AnalysisItem, selected: Boolean = true) {
        if (!selected || !InspectionMetrics.isReading(item, metric)) return
        appendReading(reading(item))
        appendInspection(item)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun appendRetained(item: AnalysisItem, activeInterval: Int?, interruptionBoundary: Boolean = false) {
        if (!InspectionMetrics.isReading(item, metric)) return
        appendReading(monitorReading(item, metric, activeInterval?.takeIf { item.collectionUs <= timeline.endUs }))
        appendRetainedInspection(item, interruptionBoundary)
    }

    fun appendRetainedInspection(item: AnalysisItem, interruptionBoundary: Boolean = false) {
        if (!InspectionMetrics.isReading(item, metric) || !Metrics.plotValid(item, metric)) return
        val point = inspectionPoint(item, metric, item.continuity)
        cursor?.append(point, interruptionBoundary)
        baseline?.append(point, interruptionBoundary)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun appendCompact(point: InspectionCompactPoint, selected: Boolean = true) {
        if (selected) appendReading(reading(point))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun appendInspection(item: AnalysisItem, selected: Boolean = true) {
        if (!selected || !InspectionMetrics.isReading(item, metric) || !Metrics.plotValid(item, metric)) return
        val point =
            inspectionPoint(item, metric, item.continuity.copy(interruptionIndex = timeline.interruptionIndex(item)))
        val boundary = timeline.interruptions.any { it.cutoffUs == item.collectionUs }
        cursor?.append(point, boundary)
        baseline?.append(point, boundary)
    }

    fun checkpoint(): MetricCheckpoint? =
        reducer.checkpoint()?.let {
            it.copy(
                firstRaw = firstOwned,
                lastRaw = lastOwned,
                previous = it.previous?.takeIf { p -> owns(p.reading.timeUs) },
            )
        }

    fun inheritPredecessor(checkpoint: MetricCheckpoint) {
        require(
            bucket &&
                !present &&
                previousTime == null &&
                mergedThrough == null &&
                checkpoint.bucket &&
                checkpoint.metric == metric &&
                checkpoint.range.startUs == range.startUs
        )
        predecessorAdmission = checkpoint.leadingPredecessor
        reducer = MetricReducer(policy, range, predecessorAdmission, bucket = true)
    }

    fun canMerge(checkpoint: MetricCheckpoint): Boolean =
        checkpoint.metric == metric &&
            (!bucket || checkpoint.bucket) &&
            checkpoint.count >= 0 &&
            checkpoint.range.startUs >= range.startUs &&
            checkpoint.range.endUs <= range.endUs &&
            (checkpoint.bucket || checkpoint.range == range && !present) &&
            (checkpoint.leadingPredecessor == null ||
                checkpoint.leadingPredecessor.reading.timeUs >= range.startUs ||
                bucket &&
                    checkpoint.range.startUs == range.startUs &&
                    checkpoint.leadingPredecessor == predecessorAdmission) &&
            previousTime?.let { checkpoint.range.startUs >= it } != false &&
            mergedThrough?.let { checkpoint.range.startUs >= it } != false

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun mergeOwned(checkpoint: MetricCheckpoint) {
        require(canMerge(checkpoint)) { "Checkpoint needs raw boundary originals" }
        reducer.mergeOwned(checkpoint)
        present = true
        if (firstOwned == null) firstOwned = checkpoint.firstRaw
        if (checkpoint.lastRaw != null) lastOwned = checkpoint.lastRaw
        previousTime = checkpoint.lastRaw?.timeUs ?: previousTime
        mergedThrough = checkpoint.range.endUs
    }

    @Throws(IllegalArgumentException::class)
    fun restore(checkpoint: MetricCheckpoint) {
        require(!present && checkpoint.metric == metric && checkpoint.range == range && checkpoint.bucket == bucket)
        reducer = MetricReducer(policy, range, checkpoint = checkpoint, bucket = bucket)
        present = true
        firstOwned = checkpoint.firstRaw
        lastOwned = checkpoint.lastRaw
        previousTime = checkpoint.lastRaw?.timeUs
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun result(): InspectionResult =
        InspectionResult(
            if (present)
                when (val outcome = metricOutcome(reducer)) {
                    is MetricOutcome.Complete ->
                        outcome.copy(result = outcome.result.copy(previous = checkpoint()?.previous))
                    is MetricOutcome.Overflow -> outcome
                }
            else null,
            cursor?.result(allowTrailing, cursor.crosses(timeline.interruptions)),
            baseline?.result(allowTrailing, baseline.crosses(timeline.interruptions)),
        )
}

data class InspectionPlotPoint(val point: InspectionPoint, val segment: Int, val tieKey: Long? = null) {
    internal val key: Any
        get() = tieKey ?: point.key
}

private fun plotOrder(a: InspectionPlotPoint, b: InspectionPlotPoint): Int =
    a.point.timeUs.compareTo(b.point.timeUs).takeIf { it != 0 }
        ?: if (a.tieKey != null && b.tieKey != null) a.tieKey.compareTo(b.tieKey)
        else
            a.point.continuity.interruptionIndex.compareTo(b.point.continuity.interruptionIndex).takeIf { it != 0 }
                ?: InspectionOrder.compare(a.point, b.point)

class InspectionPlotAggregate
@Throws(IllegalArgumentException::class)
constructor(
    val metric: String,
    val range: AnalysisRange,
    points: List<InspectionPlotPoint>,
    val firstRawUs: Long?,
    val lastRawUs: Long?,
    val leadingBarrier: Boolean,
    val trailingBarrier: Boolean,
    val completeSegments: Boolean = false,
) {
    val points = points.toList()

    init {
        InspectionMetrics.plotGapUs(metric)
        require(points.size <= 16384 && (firstRawUs == null) == (lastRawUs == null))
        require(
            firstRawUs == null ||
                firstRawUs >= range.startUs && requireNotNull(lastRawUs) < range.endUs && firstRawUs <= lastRawUs
        )
        require(
            points.all {
                it.segment >= 0 &&
                    firstRawUs != null &&
                    it.point.timeUs >= firstRawUs &&
                    it.point.timeUs <= requireNotNull(lastRawUs)
            }
        )
        require(points.zipWithNext().all { (a, b) -> plotOrder(a, b) <= 0 })
        require(points.distinctBy { it.key }.size == points.size)
        require(firstRawUs != null || !leadingBarrier && !trailingBarrier)
        require(points.isNotEmpty() || firstRawUs == null || leadingBarrier && trailingBarrier)
        require(points.all { Metrics.plotValid(metric, it.point.value) })
        require(!InspectionMetrics.isProjected(metric) || points.all { it.tieKey != null })
        require(!completeSegments || points.groupBy { it.segment }.values.all { it.size <= 4 })
    }
}

class InspectionCapacityException : Exception("Chart discontinuity capacity exceeded")

class InspectionPlotReducer
@Throws(IllegalArgumentException::class, InspectionCapacityException::class)
constructor(
    val metric: String,
    val range: AnalysisRange,
    val buckets: Int,
    val pointLimit: Int = 16384,
    val owned: Boolean = false,
    private val identity: ((Long) -> String)? = null,
    val alignedSlotUs: Long? = null,
) {
    private var capacityExceeded = false
    private var previous: InspectionPoint? = null
    private var segment = 0
    private var broken = false
    private var firstRawUs: Long? = null
    private var lastRawUs: Long? = null
    private var leadingBarrier = false
    private var mergedThrough: Long? = null
    private var predecessor: InspectionPlotPoint? = null
    private var successor: InspectionPlotPoint? = null
    private var viewportStarted = false
    private var bucketIndex: Long? = null
    private val bucket = mutableListOf<InspectionPlotPoint>()
    private val retained = linkedMapOf<Any, InspectionPlotPoint>()

    init {
        require(buckets in 1..512 && pointLimit in 4..16384 && (!owned || buckets == 1))
        InspectionMetrics.plotGapUs(metric)
        require(
            alignedSlotUs == null ||
                !owned &&
                    alignedSlotUs > 0 &&
                    FixedPoint.product(alignedSlotUs, buckets.toLong()) >=
                        FixedPoint.subtract(range.endUs, range.startUs)
        )
    }

    private fun index(timeUs: Long): Long {
        if (alignedSlotUs != null) return timeUs / alignedSlotUs
        val width = FixedPoint.subtract(range.endUs, range.startUs)
        return if (width == 0L) 0L
        else
            minOf(
                buckets.toLong() - 1,
                FixedPoint.product(FixedPoint.subtract(timeUs, range.startUs), buckets.toLong()) / width,
            )
    }

    private fun add(point: InspectionPlotPoint) {
        val slot = index(point.point.timeUs)
        if (bucketIndex != slot || bucket.lastOrNull()?.segment?.let { it != point.segment } == true) flush()
        bucketIndex = slot
        bucket.add(point)
        if (bucket.size > 4) reduceBucket()
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun append(item: AnalysisItem, selected: Boolean = true, interruptionBoundary: Boolean = false) {
        require(!InspectionMetrics.isProjected(metric)) {
            "Distance plotting requires store segments and numeric point IDs"
        }
        if (!selected || !InspectionMetrics.isReading(item, metric)) return
        val valid = Metrics.plotValid(item, metric)
        val point =
            if (valid)
                InspectionPoint(
                    item.collectionUs,
                    requireNotNull(item.original(metric)),
                    item.continuity,
                    item.producer,
                    item.sequence,
                    interruptionBoundary,
                )
            else null
        appendRaw(item.collectionUs, point)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendCompact(point: InspectionCompactPoint, selected: Boolean = true) {
        require(!InspectionMetrics.isProjected(metric))
        if (!selected) return
        appendRaw(
            point.timeUs,
            if (Metrics.plotValid(metric, point.value, point.accuracy)) InspectionPoint(point, requireNotNull(identity))
            else null,
        )
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendDistance(boundary: DistanceInspectionPoint) {
        require(InspectionMetrics.isProjected(metric) && Metrics.plotValid(metric, boundary.point.value))
        if (!admitRaw(boundary.point.timeUs)) return
        previous = boundary.point
        segment = boundary.plotSegment
        broken = false
        retainViewport(InspectionPlotPoint(boundary.point, boundary.plotSegment, boundary.tieKey))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun appendCompactDistance(point: InspectionCompactPoint, plotSegment: Int, tieKey: Long) {
        require(InspectionMetrics.isProjected(metric) && Metrics.plotValid(metric, point.value))
        if (!admitRaw(point.timeUs)) return
        val admitted = InspectionPoint(point, requireNotNull(identity))
        previous = admitted
        segment = plotSegment
        broken = false
        retainViewport(InspectionPlotPoint(admitted, plotSegment, tieKey))
    }

    private fun admitRaw(timeUs: Long): Boolean {
        if (owned && (timeUs < range.startUs || timeUs >= range.endUs)) return false
        require(lastRawUs?.let { timeUs >= it } != false && mergedThrough?.let { timeUs >= it } != false) {
            "Plot originals must be ordered and disjoint"
        }
        if (firstRawUs == null) firstRawUs = timeUs
        lastRawUs = timeUs
        return true
    }

    private fun startViewport() {
        if (owned || viewportStarted) return
        previous = predecessor?.point
        viewportStarted = true
    }

    private fun appendRaw(timeUs: Long, point: InspectionPoint?) {
        if (!admitRaw(timeUs)) return
        if (!owned && timeUs < range.startUs) {
            if (point == null) {
                if (predecessor == null) leadingBarrier = true
                broken = true
            } else {
                retainViewport(InspectionPlotPoint(point, 0))
                if (predecessor?.point === point) broken = false
            }
            return
        }
        startViewport()
        if (!owned && timeUs > range.endUs) {
            if (point != null) retainViewport(InspectionPlotPoint(point, 0)) else if (successor == null) broken = true
            return
        }
        if (point == null) {
            if (previous == null) leadingBarrier = true
            broken = true
            return
        }
        val old = previous
        if (old != null && (broken || InspectionOrder.startsSegment(metric, old, point, course = true))) segment++
        broken = false
        previous = point
        retainViewport(InspectionPlotPoint(point, segment))
    }

    private fun retainViewport(plotted: InspectionPlotPoint) {
        val point = plotted.point
        fun neighbourOrder(a: InspectionPlotPoint, b: InspectionPlotPoint): Int =
            if (a.point.interruptionBoundary && !InspectionMetrics.isProjected(metric))
                compareUtf8(a.point.producer, b.point.producer).takeIf { it != 0 }
                    ?: a.point.sequence.compareTo(b.point.sequence)
            else if (InspectionMetrics.isProjected(metric)) plotOrder(a, b)
            else InspectionOrder.compare(a.point, b.point)
        if (point.timeUs < range.startUs) {
            val before = predecessor
            if (
                before == null ||
                    point.timeUs > before.point.timeUs ||
                    point.timeUs == before.point.timeUs &&
                        (if (point.interruptionBoundary || InspectionMetrics.isProjected(metric))
                            neighbourOrder(plotted, before) > 0
                        else neighbourOrder(plotted, before) < 0)
            )
                predecessor = plotted
        } else if (point.timeUs > range.endUs) {
            val after = successor
            if (after == null || point.timeUs == after.point.timeUs && neighbourOrder(plotted, after) < 0)
                successor = plotted
        } else add(plotted)
    }

    fun canMerge(aggregate: InspectionPlotAggregate): Boolean {
        if (
            aggregate.metric != metric ||
                (if (alignedSlotUs != null && aggregate.firstRawUs != null)
                    aggregate.firstRawUs < range.startUs || requireNotNull(aggregate.lastRawUs) > range.endUs
                else aggregate.range.startUs < range.startUs || aggregate.range.endUs > range.endUs) ||
                lastRawUs?.let { aggregate.range.startUs <= it } == true ||
                mergedThrough?.let { aggregate.range.startUs < it } == true
        )
            return false
        if (!aggregate.completeSegments && aggregate.points.any { it.segment != aggregate.points.first().segment })
            return false
        return aggregate.firstRawUs == null || index(aggregate.firstRawUs) == index(requireNotNull(aggregate.lastRawUs))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun merge(aggregate: InspectionPlotAggregate) {
        require(canMerge(aggregate)) { "Aggregate needs children or raw boundary originals" }
        mergedThrough = aggregate.range.endUs
        if (aggregate.firstRawUs == null) return
        if (firstRawUs == null) {
            firstRawUs = aggregate.firstRawUs
            leadingBarrier = aggregate.leadingBarrier
        }
        lastRawUs = aggregate.lastRawUs
        if (aggregate.points.isEmpty()) {
            broken = true
            return
        }
        if (InspectionMetrics.isProjected(metric)) {
            for (point in aggregate.points) add(point)
            segment = aggregate.points.last().segment
            previous = aggregate.points.last().point
            broken = aggregate.trailingBarrier
            return
        }
        startViewport()
        val ordered =
            aggregate.points.sortedWith { a, b ->
                a.point.timeUs.compareTo(b.point.timeUs).takeIf { it != 0 }
                    ?: a.segment.compareTo(b.segment).takeIf { it != 0 }
                    ?: plotOrder(a, b)
            }
        val first = ordered.first()
        val old = previous
        if (
            old != null &&
                (broken ||
                    aggregate.leadingBarrier ||
                    InspectionOrder.startsSegment(metric, old, first.point, course = true))
        )
            segment++
        val offset = segment - first.segment
        for (point in ordered) add(InspectionPlotPoint(point.point, point.segment + offset))
        segment = ordered.last().segment + offset
        previous = ordered.last().point
        broken = aggregate.trailingBarrier
    }

    private fun reduceBucket() {
        var first = bucket[0]
        var last = first
        var low = first
        var high = first
        for (i in 1 until bucket.size) {
            val point = bucket[i]
            if (plotOrder(point, first) < 0) first = point
            if (plotOrder(point, last) > 0) last = point
            val lowOrder = compareScalars(point.point.value, low.point.value)
            if (lowOrder < 0 || lowOrder == 0 && plotOrder(point, low) < 0) low = point
            val highOrder = compareScalars(point.point.value, high.point.value)
            if (highOrder > 0 || highOrder == 0 && plotOrder(point, high) < 0) high = point
        }
        bucket.clear()
        fun insert(point: InspectionPlotPoint) {
            for (retained in bucket) if (retained.key == point.key) return
            var index = 0
            while (index < bucket.size && plotOrder(bucket[index], point) <= 0) index++
            bucket.add(index, point)
        }
        insert(first)
        if (!InspectionMetrics.isProjected(metric)) {
            insert(low)
            insert(high)
        }
        insert(last)
    }

    private fun overflow(): Nothing {
        capacityExceeded = true
        throw InspectionCapacityException()
    }

    private fun flush() {
        if (capacityExceeded) throw InspectionCapacityException()
        if (bucket.isEmpty()) return
        reduceBucket()
        for (point in bucket) {
            val id = point.key
            if (id !in retained && retained.size >= pointLimit) overflow()
            retained[id] = point
        }
        bucket.clear()
    }

    @Throws(IllegalArgumentException::class, InspectionCapacityException::class)
    fun result(): List<InspectionPlotPoint> {
        flush()
        startViewport()
        val after = successor?.let {
            if (InspectionMetrics.isProjected(metric)) it
            else {
                val old = previous
                it.copy(
                    segment =
                        segment +
                            if (
                                old != null &&
                                    (broken || InspectionOrder.startsSegment(metric, old, it.point, course = true))
                            )
                                1
                            else 0
                )
            }
        }
        val points = (retained.values + listOfNotNull(predecessor, after)).distinctBy { it.key }.sortedWith(::plotOrder)
        if (points.size > pointLimit) overflow()
        return points
    }

    @Throws(IllegalArgumentException::class, InspectionCapacityException::class)
    fun aggregate(): InspectionPlotAggregate {
        require(owned)
        return InspectionPlotAggregate(metric, range, result(), firstRawUs, lastRawUs, leadingBarrier, broken, true)
    }
}
