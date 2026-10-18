package app.powerlog.core.health

import app.powerlog.core.analytics.*

data class OriginCoverageScore(val dataOrigin: String, val coveredUs: Long)

private data class CoverageState(var lastEnd: Long, var covered: Long = 0)

class EnergyOriginPreparation(private val source: String, private val window: AnalysisRange) {
    private val origins = mutableMapOf<String, CoverageState>()
    private var previousStart = Long.MIN_VALUE

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun append(evidence: EnergyEvidence) {
        if (evidence is EnergyEvidence.IntervalRecord) append(evidence.metric, evidence.interval)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun append(metric: String, record: EnergyInterval) {
        require(metric == "active" || metric == "basal")
        if (record.source.name != source || !HealthOriginSelector.valid(record)) return
        require(record.startUs >= previousStart) { "Origin coverage pages must be ordered by interval start" }
        previousStart = record.startUs
        val state = origins.getOrPut(record.origin.dataOrigin.orEmpty()) { CoverageState(window.startUs) }
        val start = maxOf(window.startUs, record.startUs)
        val end = minOf(window.endUs, record.endUs)
        if (end <= start) return
        state.covered = FixedPoint.add(state.covered, maxOf(0, end - maxOf(start, state.lastEnd)))
        state.lastEnd = maxOf(state.lastEnd, end)
    }

    fun scores(): List<OriginCoverageScore> = origins.map { (origin, state) ->
        OriginCoverageScore(origin, state.covered)
    }

    fun selected(): OriginCoverageScore? = selectOriginCoverage(scores())
}

fun selectOriginCoverage(scores: List<OriginCoverageScore>): OriginCoverageScore? = scores.minWithOrNull { a, b ->
    val order = b.coveredUs.compareTo(a.coveredUs)
    if (order == 0) compareUtf8(a.dataOrigin, b.dataOrigin) else order
}

class HeartRateOriginPreparation(private val source: String, private val window: AnalysisRange) {
    private val reducers = mutableMapOf<String, MetricReducer>()

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun append(record: HeartRateEvidence, activeInterval: Int? = 0) {
        if (record.source.name != source) return
        val reducer =
            reducers.getOrPut(record.origin.dataOrigin.orEmpty()) {
                MetricReducer(Metrics.policy("heartRateBpm"), window)
            }
        val original =
            record.valueBpm?.let {
                Original(
                    record.memberIdentity ?: record.origin.originEventId,
                    record.origin.originEventId,
                    record.originalStartTimestamp,
                    FixedPoint.divide(record.collectionUs, 1000000),
                    Scalar.Real64(it),
                )
            }
        reducer.append(MetricReading(record.collectionUs, original, activeInterval, record.continuity))
    }

    @Throws(ArithmeticException::class)
    fun scores(): List<OriginCoverageScore> = reducers.map { (origin, reducer) ->
        OriginCoverageScore(origin, reducer.result().coveredUs)
    }

    @Throws(ArithmeticException::class) fun selected(): OriginCoverageScore? = selectOriginCoverage(scores())
}

class HealthIntervalRevisionCursor(private val pinRevision: Long) {
    private var pending: EnergyInterval? = null
    private var key: String? = null
    private var finished = false

    private fun identity(record: EnergyInterval): String =
        listOf(
                record.origin.provider?.name.orEmpty(),
                record.origin.dataOrigin.orEmpty(),
                normalizedOriginIdentifier(record.origin.providerObservationId ?: record.origin.originEventId),
            )
            .joinToString("") { "${it.encodeToByteArray().size}:$it" }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun append(record: EnergyInterval): EnergyInterval? {
        check(!finished)
        val nextKey = identity(record)
        require(key == null || compareUtf8(requireNotNull(key), nextKey) <= 0) {
            "Health revision pages must be ordered by provider identity"
        }
        val result = if (key != nextKey) pending else null
        if (key != nextKey) {
            pending = null
            key = nextKey
        }
        if (record.observationRevision <= pinRevision) {
            val old = pending
            if (old == null || record.observationRevision > old.observationRevision) pending = record
            else if (record.observationRevision == old.observationRevision)
                require(
                    record.copy(origin = normalizedOrigin(record.origin)) ==
                        old.copy(origin = normalizedOrigin(old.origin))
                ) {
                    "Conflicting immutable Health identity"
                }
        }
        return result
    }

    @Throws(IllegalStateException::class)
    fun finish(): EnergyInterval? {
        check(!finished)
        finished = true
        return pending
    }

    companion object {
        fun order(a: EnergyInterval, b: EnergyInterval): Int {
            val x =
                listOf(
                        a.origin.provider?.name.orEmpty(),
                        a.origin.dataOrigin.orEmpty(),
                        normalizedOriginIdentifier(a.origin.providerObservationId ?: a.origin.originEventId),
                    )
                    .joinToString("") { "${it.encodeToByteArray().size}:$it" }
            val y =
                listOf(
                        b.origin.provider?.name.orEmpty(),
                        b.origin.dataOrigin.orEmpty(),
                        normalizedOriginIdentifier(b.origin.providerObservationId ?: b.origin.originEventId),
                    )
                    .joinToString("") { "${it.encodeToByteArray().size}:$it" }
            return compareUtf8(x, y)
        }
    }
}
