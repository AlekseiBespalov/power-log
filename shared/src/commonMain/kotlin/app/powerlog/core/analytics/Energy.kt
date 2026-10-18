package app.powerlog.core.analytics

import app.powerlog.core.health.*

fun validEnergyEvidence(evidence: EnergyEvidence): Boolean =
    evidence.valueKcal.isFinite() &&
        evidence.valueKcal >= 0 &&
        (evidence !is EnergyEvidence.IntervalRecord || HealthOriginSelector.valid(evidence.interval))

class EnergyReducer(
    private val metric: String,
    private val endUs: Long,
    private val selectedSource: String?,
    private val selectedOrigin: String? = null,
) {
    private val intervals = CheckedSum()
    private var hasInterval = false
    private var snapshot: Pair<Double, Long>? = null
    private var final: Pair<Long, Long>? = null

    fun append(evidence: EnergyEvidence) {
        if (
            evidence.metric != metric ||
                evidence.source.name != selectedSource ||
                selectedOrigin != null && evidence.origin.dataOrigin != selectedOrigin ||
                !validEnergyEvidence(evidence)
        )
            return
        val q =
            try {
                FixedPoint.quantize(evidence.valueKcal, 1000000)
            } catch (_: ArithmeticException) {
                throw NumericFailure(NumericOverflow("${metric}EnergyKcal", Quantity.energyQ))
            }
        when (evidence) {
            is EnergyEvidence.IntervalRecord -> {
                val interval = evidence.interval
                val a = maxOf(0L, interval.startUs)
                val b = minOf(endUs, interval.endUs)
                if (b > a) {
                    intervals.add(FixedPoint.share(q, b - a, FixedPoint.subtract(interval.endUs, interval.startUs)))
                    hasInterval = true
                }
            }
            is EnergyEvidence.CumulativeSnapshot ->
                if (
                    evidence.point.collectionUs <= endUs &&
                        (snapshot == null || evidence.valueKcal > requireNotNull(snapshot).first)
                )
                    snapshot = evidence.valueKcal to q
            is EnergyEvidence.FinalWorkoutTotal ->
                if (final == null || evidence.point.collectionUs >= requireNotNull(final).first)
                    final = evidence.point.collectionUs to q
        }
    }

    fun valueQ(): Long? {
        if (final != null) return requireNotNull(final).second
        if (hasInterval) return intervals.value("${metric}EnergyKcal", Quantity.energyQ)
        return snapshot?.second
    }
}
