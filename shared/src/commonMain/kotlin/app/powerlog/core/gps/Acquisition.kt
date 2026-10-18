package app.powerlog.core.gps

import app.powerlog.core.analytics.FixedPoint
import app.powerlog.core.protocol.ProtocolTimestamp
import kotlin.math.abs

data class GPSOriginal(
    val timestamp: ProtocolTimestamp,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double,
)

private fun fractionNanos(timestamp: ProtocolTimestamp): Int =
    timestamp.original
        .substringBefore('Z')
        .substringBefore('+')
        .substringAfter('.', "")
        .padEnd(9, '0')
        .takeLast(3)
        .toInt()

data class GPSReceipt(val utcUs: Long, val monotonicSeconds: Double, val submicrosecondNanos: Int = 0) {
    constructor(
        timestamp: ProtocolTimestamp,
        monotonicSeconds: Double,
    ) : this(
        timestamp.comparisonUs,
        monotonicSeconds,
        fractionNanos(timestamp),
    )

    init {
        require(submicrosecondNanos in 0..999)
    }
}

data class GPSTimeline(
    val epoch: String,
    val startedAtUs: Long,
    val monotonicOrigin: Double,
    val epochStart: Double? = null,
    val stopMonotonic: Double? = null,
    val uncertain: Boolean = false,
    val startedAtSubmicrosecondNanos: Int = 0,
) {
    init {
        require(startedAtSubmicrosecondNanos in 0..999)
    }

    fun elapsedForAcquisition(epoch: String?, acquisition: Double?): Double? {
        if (epoch != this.epoch || acquisition == null || !acquisition.isFinite()) return null
        val elapsed = acquisition - monotonicOrigin
        return elapsed
            .takeIf {
                it.isFinite() &&
                    it >= 0 &&
                    acquisition >= (epochStart ?: monotonicOrigin) &&
                    (stopMonotonic == null || acquisition <= stopMonotonic)
            }
            ?.let { maxOf(0.0, it) }
    }
}

class GPSAcquisitionFix
internal constructor(
    val original: GPSOriginal,
    val elapsedSeconds: Double,
    val acquisitionMonotonic: Double,
    val epoch: String,
    val uncertain: Boolean,
    internal val admissionOwner: Any,
) {
    val elapsedUs: Long = FixedPoint.timeUs(elapsedSeconds)
}

data class GPSMappingBatch(val fixes: List<GPSAcquisitionFix>, val discontinuity: Boolean)

class GPSAcquisition {
    private val admissionOwner = Any()
    private var receipt: GPSReceipt? = null
    private var uncertainUntil: Double? = null
    private var epoch: String? = null
    private var lastAcquisition: Double? = null
    private val recent = mutableListOf<GPSOriginal>()

    @Throws(ArithmeticException::class)
    fun map(original: GPSOriginal, received: GPSReceipt, timeline: GPSTimeline): GPSAcquisitionFix? {
        val uptime = received.monotonicSeconds
        if (
            !uptime.isFinite() ||
                !timeline.monotonicOrigin.isFinite() ||
                timeline.epochStart?.isFinite() == false ||
                timeline.stopMonotonic?.isFinite() == false
        )
            return null
        if (
            !original.latitude.isFinite() ||
                original.latitude !in -90.0..90.0 ||
                !original.longitude.isFinite() ||
                original.longitude !in -180.0..180.0 ||
                !original.horizontalAccuracyM.isFinite()
        )
            return null
        if (epoch != timeline.epoch) {
            epoch = timeline.epoch
            receipt = null
            lastAcquisition = null
            val wallElapsed =
                FixedPoint.divide(FixedPoint.subtract(received.utcUs, timeline.startedAtUs), 1000000) +
                    (received.submicrosecondNanos - timeline.startedAtSubmicrosecondNanos) / 1000000000.0
            uncertainUntil = if (abs(wallElapsed - (uptime - timeline.monotonicOrigin)) > 1) uptime + 15 else null
        }
        receipt?.let {
            val wallDelta =
                FixedPoint.divide(FixedPoint.subtract(received.utcUs, it.utcUs), 1000000) +
                    (received.submicrosecondNanos - it.submicrosecondNanos) / 1000000000.0
            if (abs(wallDelta - (uptime - it.monotonicSeconds)) > 1) uncertainUntil = uptime + 15
        }
        receipt = received
        val ageUs = FixedPoint.subtract(received.utcUs, original.timestamp.comparisonUs)
        val ageNanos = received.submicrosecondNanos - fractionNanos(original.timestamp)
        if (ageUs !in 0L..15000000L || ageUs == 0L && ageNanos < 0 || ageUs == 15000000L && ageNanos > 0) return null
        val acquisition = uptime - (FixedPoint.divide(ageUs, 1000000) + ageNanos / 1000000000.0)
        val elapsed = timeline.elapsedForAcquisition(timeline.epoch, acquisition) ?: return null
        return GPSAcquisitionFix(
            original,
            maxOf(0.0, elapsed),
            acquisition,
            timeline.epoch,
            uncertainUntil?.let { uptime <= it } == true || timeline.uncertain,
            admissionOwner,
        )
    }

    @Throws(ArithmeticException::class)
    fun mapBatch(originals: List<GPSOriginal>, received: GPSReceipt, timeline: GPSTimeline): GPSMappingBatch {
        val mapped = originals.mapNotNull { map(it, received, timeline) }
        return GPSMappingBatch(mapped.sortedBy { it.acquisitionMonotonic }, mapped.size != originals.size)
    }

    fun admit(fix: GPSAcquisitionFix): Boolean {
        if (
            fix.admissionOwner !== admissionOwner || fix.epoch != epoch || recent.any { sameOriginal(it, fix.original) }
        )
            return false
        if (lastAcquisition?.let { fix.acquisitionMonotonic <= it + 0.000001 } == true) return false
        lastAcquisition = fix.acquisitionMonotonic
        if (recent.size == 256) recent.removeAt(0)
        recent.add(fix.original)
        return true
    }

    private fun sameOriginal(a: GPSOriginal, b: GPSOriginal): Boolean =
        a.timestamp.comparisonUs == b.timestamp.comparisonUs &&
            fractionNanos(a.timestamp) == fractionNanos(b.timestamp) &&
            a.latitude == b.latitude &&
            a.longitude == b.longitude &&
            a.horizontalAccuracyM == b.horizontalAccuracyM
}
