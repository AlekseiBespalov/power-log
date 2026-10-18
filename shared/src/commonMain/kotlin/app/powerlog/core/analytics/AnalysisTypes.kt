package app.powerlog.core.analytics

import app.powerlog.core.health.EnergyEvidence
import app.powerlog.core.health.HealthOutcome
import app.powerlog.core.health.MeasurementSource
import app.powerlog.core.health.Origin
import app.powerlog.core.presentation.SampleRates

data class Original(
    val observationId: String,
    val eventId: String,
    val timestamp: String,
    val elapsedSeconds: Double,
    val value: Scalar,
    val originalElapsedSeconds: Double? = null,
)

data class Continuity(
    val clockEpoch: String? = null,
    val connectionEpoch: String? = null,
    val interruptionIndex: Int = 0,
)

data class AnalysisItem(
    val collectionUs: Long,
    val collectionElapsedSeconds: Double,
    val eventId: String,
    val observationId: String,
    val producer: String,
    val sequence: Long,
    val kind: String,
    val source: String,
    val timestamp: String,
    val values: Map<String, Scalar> = emptyMap(),
    val originalElapsedSeconds: Double? = null,
    val representation: String? = null,
    val continuity: Continuity = Continuity(),
    val action: String? = null,
    val distanceBarrier: Boolean = false,
    val interrupted: Boolean = false,
    val controllerSequence: Long? = null,
    val hasElapsedMapping: Boolean = true,
    val origin: Origin? = null,
    val declaredMetrics: List<String>? = null,
) {
    fun isReadingOf(metric: String): Boolean = declaredMetrics?.contains(metric) ?: true

    fun number(metric: String): Double? = values[metric]?.real()

    fun original(metric: String): Original? =
        values[metric]?.let {
            Original(observationId, eventId, timestamp, collectionElapsedSeconds, it, originalElapsedSeconds)
        }
}

data class ActiveInterval(val startUs: Long, val endUs: Long)

data class InterruptionClose(
    val cutoffUs: Long,
    val producer: String,
    val sequence: Long,
    val clockEpoch: String?,
    val controllerSequence: Long,
    val eventId: String,
) {
    fun closing(item: AnalysisItem): Boolean {
        return closing(item.collectionUs, item.producer, item.sequence, item.continuity.clockEpoch)
    }

    fun closing(timeUs: Long, producer: String, sequence: Long, clockEpoch: String?): Boolean {
        if (timeUs != cutoffUs) return timeUs < cutoffUs
        if (producer == this.producer) return sequence <= this.sequence
        if (producer == "controller") return sequence <= controllerSequence
        return this.clockEpoch == null || clockEpoch == null || clockEpoch == this.clockEpoch
    }
}

data class AnalysisMetadata(
    val id: String,
    val startedAt: String,
    val elapsedSeconds: Double,
    val indoor: Boolean,
    val watchEnabled: Boolean,
    val recordGPS: Boolean,
    val saveToHealth: Boolean,
    val sampleHz: Int,
    val endedAt: String? = null,
    val stopElapsedSeconds: Double? = null,
    val sources: List<MeasurementSource> = emptyList(),
    val completeEncodedMetadata: String,
    val phase: String = "running",
    val healthOutcome: HealthOutcome? = null,
    val healthReadbackPending: Boolean = true,
) {
    init {
        SampleRates.require(sampleHz.toDouble())
    }
}

data class AnalysisPin(
    val rideId: String,
    val observationRevision: Long,
    val metadata: AnalysisMetadata,
    val distanceSelection: String,
) {
    val semanticMetadataIdentity: String
        get() = metadata.completeEncodedMetadata

    init {
        require(rideId == metadata.id && observationRevision > 0 && observationRevision <= 9007199254740991L)
        require(DistancePolicy.validSelection(distanceSelection))
    }
}

enum class AnalysisPass {
    prepare,
    compose,
    metric,
}

enum class AnalysisStream {
    observations,
    controllerDistance,
    energy,
    lifecycle,
}

data class AnalysisCursor(val pinIdentity: String, val pass: AnalysisPass, val orderedKey: String)

data class AnalysisRange(val startUs: Long, val endUs: Long) {
    init {
        require(endUs >= startUs)
    }
}

data class AnalysisPageRequest(
    val pin: AnalysisPin,
    val pass: AnalysisPass,
    val stream: AnalysisStream,
    val cancellationId: String,
    val cursor: AnalysisCursor? = null,
    val limit: Int = 256,
    val rangeUs: AnalysisRange? = null,
    val metrics: List<String> = emptyList(),
) {
    init {
        require(limit in 1..256 && cancellationId.isNotEmpty())
        require(cursor == null || cursor.pinIdentity == CacheIdentity.of(pin) && cursor.pass == pass)
    }
}

sealed class AnalysisProjection {
    abstract val observationId: String

    data class Observation(val item: AnalysisItem) : AnalysisProjection() {
        override val observationId
            get() = item.observationId
    }

    data class Lifecycle(val item: AnalysisItem) : AnalysisProjection() {
        override val observationId
            get() = item.observationId
    }

    data class ControllerDistance(val evidence: ControllerDistanceEvidence) : AnalysisProjection() {
        override val observationId
            get() = evidence.anchor.observationId
    }

    data class Energy(val evidence: EnergyEvidence, override val observationId: String, val collectionUs: Long) :
        AnalysisProjection()

    data class Altitude(val evidence: AltitudeEvidence, val anchor: Original) : AnalysisProjection() {
        override val observationId
            get() = anchor.observationId
    }
}

data class AnalysisPage(
    val pin: AnalysisPin,
    val items: List<AnalysisProjection>,
    val nextCursor: AnalysisCursor?,
    val done: Boolean,
    val serializedBytes: Int,
) {
    init {
        require(items.size <= 256 && serializedBytes in 0..524288)
        require(done || nextCursor != null)
    }

    fun validFor(request: AnalysisPageRequest): Boolean =
        pin == request.pin &&
            items.size <= request.limit &&
            (nextCursor == null ||
                nextCursor.pinIdentity == CacheIdentity.of(pin) &&
                    nextCursor.pass == request.pass &&
                    nextCursor != request.cursor) &&
            (done || items.isNotEmpty())
}

sealed class PageOutcome {
    data class Page(val page: AnalysisPage) : PageOutcome()

    data object Cancelled : PageOutcome()

    data object Deferred : PageOutcome()

    data object Deleted : PageOutcome()

    data class Invalid(val reason: String) : PageOutcome()
}

object CacheIdentity {
    fun of(pin: AnalysisPin): String =
        frame(
            listOf(
                pin.rideId,
                pin.observationRevision.toString(),
                pin.semanticMetadataIdentity,
                pin.metadata.phase,
                pin.metadata.healthOutcome?.name ?: "",
                pin.metadata.healthReadbackPending.toString(),
                pin.distanceSelection,
            )
        )

    private fun frame(values: List<String>): String = values.joinToString("") { "${it.encodeToByteArray().size}:$it" }

    fun filename(pin: AnalysisPin): String =
        "summary-r${pin.observationRevision}-d${pin.distanceSelection.replace(':', '-')}.json"

    fun mayPublish(request: AnalysisPin, current: AnalysisPin): Boolean = of(request) == of(current)
}

data class AltitudeEvidence(
    val originalAltitudeDatum: String,
    val originalAltitudeMeters: Double? = null,
    val originalVerticalAccuracyM: Double? = null,
    val altitudeMeters: Double? = null,
    val verticalAccuracyM: Double? = null,
)

data class ControllerDistanceEvidence(
    val timeUs: Long,
    val originalTime: Double,
    val speedMps: Double?,
    val model: String?,
    val protocol: String?,
    val deviceIdentity: String?,
    val continuityIdentity: String?,
    val activeInterval: Int?,
    val monotonicSeconds: Double?,
    val counter: Scalar?,
    val barrier: Boolean,
    val anchor: Original,
)

class ActiveTimeline(val endUs: Long, lifecycle: List<AnalysisItem>) {
    val intervals: List<ActiveInterval>
    val interruptions: List<InterruptionClose>
    val lapEnds: List<Long>
    val lapRanges: List<AnalysisRange>
        get() = lapEnds.mapIndexed { index, end -> AnalysisRange(if (index == 0) 0 else lapEnds[index - 1], end) }

    val timerUs: Long
    val activeThroughEnd: Boolean

    init {
        require(endUs >= 0 && lifecycle.size <= 10000)
        val sorted =
            lifecycle.sortedWith(
                compareBy<AnalysisItem> { it.collectionUs }
                    .thenBy { it.producer }
                    .thenBy { it.sequence }
                    .thenBy { it.eventId }
            )
        val spans = mutableListOf<ActiveInterval>()
        val closes = mutableListOf<InterruptionClose>()
        val marks = mutableSetOf<Long>()
        var open: Long? = 0
        for (item in sorted) {
            val t = item.collectionUs
            if (t < 0L || t > endUs) continue
            when (item.action) {
                "pause" -> {
                    if (open != null) {
                        spans.add(ActiveInterval(open, t))
                        open = null
                    }
                    if (item.interrupted)
                        closes.add(
                            InterruptionClose(
                                t,
                                item.producer,
                                item.sequence,
                                item.continuity.clockEpoch,
                                requireNotNull(item.controllerSequence),
                                item.eventId,
                            )
                        )
                }
                "resume" -> if (open == null) open = t
                "lap" -> if (t > 0 && t < endUs) marks.add(t)
            }
        }
        if (open != null) spans.add(ActiveInterval(open, endUs))
        activeThroughEnd = open != null
        intervals = spans.toList()
        interruptions = closes.toList()
        lapEnds = marks.sorted() + endUs
        timerUs =
            spans.fold(0L) { sum, interval ->
                FixedPoint.add(sum, FixedPoint.subtract(interval.endUs, interval.startUs))
            }
    }

    fun membership(item: AnalysisItem): Int? =
        membership(item.collectionUs, item.producer, item.sequence, item.continuity.clockEpoch)

    fun membership(t: Long, producer: String, sequence: Long, clockEpoch: String?): Int? {
        if (t < 0L || t > endUs) return null
        val closeIndex = upperBound(interruptions.size) { interruptions[it].cutoffUs <= t } - 1
        val close = interruptions.getOrNull(closeIndex)?.takeIf { it.cutoffUs == t }
        if (close != null && close.closing(t, producer, sequence, clockEpoch)) {
            val index = upperBound(intervals.size) { intervals[it].endUs <= t } - 1
            return index.takeIf { it >= 0 && intervals[it].endUs == t }
        }
        val final = t == endUs && activeThroughEnd
        val index = upperBound(intervals.size) { if (final) intervals[it].endUs < t else intervals[it].endUs <= t }
        return index.takeIf {
            it < intervals.size &&
                t >= intervals[it].startUs &&
                (t < intervals[it].endUs || final && intervals[it].endUs == endUs)
        }
    }

    fun interruptionIndex(item: AnalysisItem): Int =
        interruptionIndex(item.collectionUs, item.producer, item.sequence, item.continuity.clockEpoch)

    fun interruptionIndex(t: Long, producer: String, sequence: Long, clockEpoch: String?): Int {
        var index = upperBound(interruptions.size) { interruptions[it].cutoffUs < t }
        var count = index
        while (index < interruptions.size && interruptions[index].cutoffUs == t) {
            if (!interruptions[index].closing(t, producer, sequence, clockEpoch)) count++
            index++
        }
        return count
    }

    private inline fun upperBound(size: Int, before: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (before(middle)) low = middle + 1 else high = middle
        }
        return low
    }
}

fun compareUtf8(a: String, b: String): Int {
    val x = a.encodeToByteArray()
    val y = b.encodeToByteArray()
    for (i in 0 until minOf(x.size, y.size)) {
        val order = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
        if (order != 0) return order
    }
    return x.size.compareTo(y.size)
}

fun compareScalars(a: Scalar, b: Scalar): Int {
    fun realOrder(left: Double, right: Double): Int =
        when {
            left < right -> -1
            left > right -> 1
            else -> 0
        }
    if (a is Scalar.Integer64 && b is Scalar.Integer64) return a.value.compareTo(b.value)
    fun integerReal(n: Long, d: Double): Int {
        if (d >= 9223372036854775808.0) return -1
        if (d < -9223372036854775808.0) return 1
        val truncated = d.toLong()
        val order = n.compareTo(truncated)
        return if (order != 0) order else realOrder(truncated.toDouble(), d)
    }
    if (a is Scalar.Integer64) return integerReal(a.value, b.real())
    if (b is Scalar.Integer64) return -integerReal(b.value, a.real())
    return realOrder(a.real(), b.real())
}
