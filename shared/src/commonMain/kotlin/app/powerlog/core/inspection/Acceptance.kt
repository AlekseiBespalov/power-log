package app.powerlog.core.inspection

import app.powerlog.core.analytics.DistancePolicy
import app.powerlog.core.analytics.FixedPoint
import app.powerlog.core.analytics.Original

data class InspectionContext
@Throws(IllegalArgumentException::class)
constructor(
    val sourceId: String,
    val revision: String,
    val distanceSelection: String,
    val interactionEpoch: Long,
    val interactionSequence: Long,
) {
    init {
        require(sourceId.isNotEmpty() && revision.isNotEmpty() && DistancePolicy.validSelection(distanceSelection))
        require(interactionEpoch in 0..9007199254740991L && interactionSequence in 0..9007199254740991L)
    }
}

data class InspectionTicket(val context: InspectionContext, val generation: Long)

object InspectionAcceptance {
    fun acceptsSelection(
        current: InspectionTicket?,
        result: InspectionTicket,
        referenceUs: Long?,
        resultReferenceUs: Long?,
    ): Boolean = accepts(current, result) && referenceUs == resultReferenceUs

    fun accepts(current: InspectionTicket?, result: InspectionTicket): Boolean = current != null && current == result
}

class InspectionRequests {
    private var generation = 0L
    var current: InspectionTicket? = null
        private set

    @Throws(ArithmeticException::class)
    fun begin(context: InspectionContext): InspectionTicket {
        generation = FixedPoint.add(generation, 1)
        return InspectionTicket(context, generation).also { current = it }
    }

    @Throws(ArithmeticException::class)
    fun cancel() {
        generation = FixedPoint.add(generation, 1)
        current = null
    }

    fun accepts(ticket: InspectionTicket): Boolean = InspectionAcceptance.accepts(current, ticket)
}

data class InspectionTail(val observationId: String, val startSeconds: Double, val endSeconds: Double)

object HeldTail {
    fun gapUs(metric: String): Long = minOf(6000000, InspectionMetrics.plotGapUs(metric))

    @Throws(IllegalArgumentException::class)
    fun render(
        metric: String,
        original: Original,
        evidenceObservationId: String,
        acquisitionMonotonic: Double?,
        monotonicNow: Double,
        elapsedNow: Double,
    ): InspectionTail? {
        if (
            original.observationId.isEmpty() ||
                original.observationId != evidenceObservationId ||
                !original.value.real().isFinite() ||
                !original.elapsedSeconds.isFinite() ||
                !elapsedNow.isFinite() ||
                !monotonicNow.isFinite() ||
                acquisitionMonotonic == null ||
                !acquisitionMonotonic.isFinite()
        )
            return null
        val limit = FixedPoint.divide(gapUs(metric), 1000000)
        val age = monotonicNow - acquisitionMonotonic
        val elapsedAge = elapsedNow - original.elapsedSeconds
        return if (age >= 0 && age < limit && elapsedAge > 0 && elapsedAge < limit)
            InspectionTail(original.observationId, original.elapsedSeconds, elapsedNow)
        else null
    }
}
