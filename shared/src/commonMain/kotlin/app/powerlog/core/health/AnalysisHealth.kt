package app.powerlog.core.health

import app.powerlog.core.analytics.*

data class MeasurementSource(
    val source: Source,
    val device: Device,
    val provider: Provider? = null,
    val dataOrigin: String? = null,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        source: String,
        device: String,
        provider: String? = null,
        dataOrigin: String? = null,
    ) : this(Source.valueOf(source), Device.valueOf(device), provider?.let { Provider.valueOf(it) }, dataOrigin)
}

data class Origin(
    val provider: Provider?,
    val dataOrigin: String?,
    val providerObservationId: String?,
    val originEventId: String,
    val producer: Source,
    val sequence: Long,
    val originRevision: String? = null,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        provider: String?,
        dataOrigin: String?,
        providerObservationId: String?,
        originEventId: String,
        producer: String,
        sequence: Long,
        originRevision: String? = null,
    ) : this(
        provider?.let { Provider.valueOf(it) },
        dataOrigin,
        providerObservationId,
        originEventId,
        Source.valueOf(producer),
        sequence,
        originRevision,
    )
}

sealed class Association {
    data class Workout(val identity: String) : Association()

    data class RideWindow(val startUs: Long, val endUs: Long) : Association()
}

data class EnergyInterval(
    val source: Source,
    val origin: Origin,
    val observationRevision: Long,
    val startUs: Long,
    val endUs: Long,
    val originalStartTimestamp: String,
    val originalEndTimestamp: String,
    val valueKcal: Double,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        source: String,
        origin: Origin,
        observationRevision: Long,
        startUs: Long,
        endUs: Long,
        originalStartTimestamp: String,
        originalEndTimestamp: String,
        valueKcal: Double,
    ) : this(
        Source.valueOf(source),
        origin,
        observationRevision,
        startUs,
        endUs,
        originalStartTimestamp,
        originalEndTimestamp,
        valueKcal,
    )
}

data class EnergyPoint(
    val source: Source,
    val origin: Origin,
    val observationRevision: Long,
    val collectionUs: Long,
    val originalTimestamp: String,
    val valueKcal: Double,
    val association: Association,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        source: String,
        origin: Origin,
        observationRevision: Long,
        collectionUs: Long,
        originalTimestamp: String,
        valueKcal: Double,
        association: Association,
    ) : this(
        Source.valueOf(source),
        origin,
        observationRevision,
        collectionUs,
        originalTimestamp,
        valueKcal,
        association,
    )
}

sealed class EnergyEvidence {
    abstract val metric: String
    abstract val source: Source
    abstract val origin: Origin
    abstract val valueKcal: Double
    val collectionUs: Long
        get() =
            when (this) {
                is IntervalRecord -> interval.endUs
                is CumulativeSnapshot -> point.collectionUs
                is FinalWorkoutTotal -> point.collectionUs
            }

    data class CumulativeSnapshot(override val metric: String, val point: EnergyPoint) : EnergyEvidence() {
        override val source
            get() = point.source

        override val origin
            get() = point.origin

        override val valueKcal
            get() = point.valueKcal
    }

    data class IntervalRecord(override val metric: String, val interval: EnergyInterval) : EnergyEvidence() {
        override val source
            get() = interval.source

        override val origin
            get() = interval.origin

        override val valueKcal
            get() = interval.valueKcal
    }

    data class FinalWorkoutTotal(override val metric: String, val point: EnergyPoint) : EnergyEvidence() {
        override val source
            get() = point.source

        override val origin
            get() = point.origin

        override val valueKcal
            get() = point.valueKcal
    }
}

data class HeartRateEvidence(
    val origin: Origin,
    val source: Source,
    val association: Association,
    val originalStartTimestamp: String,
    val originalEndTimestamp: String,
    val startUs: Long,
    val endUs: Long,
    val comparisonUs: Long,
    val collectionUs: Long,
    val continuity: Continuity,
    val representation: String,
    val memberIdentity: String?,
    val memberCount: Long,
    val valueBpm: Double?,
    val unit: String,
    val observationRevision: Long,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        origin: Origin,
        source: String,
        association: Association,
        originalStartTimestamp: String,
        originalEndTimestamp: String,
        startUs: Long,
        endUs: Long,
        comparisonUs: Long,
        collectionUs: Long,
        continuity: Continuity,
        representation: String,
        memberIdentity: String?,
        memberCount: Long,
        valueBpm: Double?,
        unit: String,
        observationRevision: Long,
    ) : this(
        origin,
        Source.valueOf(source),
        association,
        originalStartTimestamp,
        originalEndTimestamp,
        startUs,
        endUs,
        comparisonUs,
        collectionUs,
        continuity,
        representation,
        memberIdentity,
        memberCount,
        valueBpm,
        unit,
        observationRevision,
    )
}

data class HealthSelection(
    val metric: String,
    val source: Source,
    val dataOrigin: String?,
    val unionCoverageUs: Long,
    val contributingOrigins: List<Origin>,
    val association: Association,
) {
    @Throws(IllegalArgumentException::class)
    constructor(
        metric: String,
        source: String,
        dataOrigin: String?,
        unionCoverageUs: Long,
        contributingOrigins: List<Origin>,
        association: Association,
    ) : this(metric, Source.valueOf(source), dataOrigin, unionCoverageUs, contributingOrigins, association)
}

internal fun normalizedOriginIdentifier(value: String): String {
    val normalized = value.lowercase()
    return if (app.powerlog.core.HealthIdentity.validRide(normalized)) normalized else value
}

internal fun normalizedOrigin(origin: Origin): Origin =
    origin.copy(
        providerObservationId = origin.providerObservationId?.let(::normalizedOriginIdentifier),
        originEventId = normalizedOriginIdentifier(origin.originEventId),
        originRevision = origin.originRevision?.let(::normalizedOriginIdentifier),
    )

private fun originOrderParts(origin: Origin, providerIdentifier: String): List<String> =
    listOf(
            providerIdentifier,
            origin.dataOrigin ?: "",
            origin.providerObservationId ?: origin.originEventId,
            origin.originRevision ?: "",
            origin.producer.name,
        )
        .map(::normalizedOriginIdentifier)

fun compareOrigins(a: Origin, b: Origin, providerIdentifierA: String = "", providerIdentifierB: String = ""): Int {
    val left = originOrderParts(a, providerIdentifierA)
    val right = originOrderParts(b, providerIdentifierB)
    for (i in left.indices) {
        val order = compareUtf8(left[i], right[i])
        if (order != 0) return order
    }
    if (a.sequence != b.sequence) return a.sequence.compareTo(b.sequence)
    return compareUtf8(normalizedOriginIdentifier(a.originEventId), normalizedOriginIdentifier(b.originEventId))
}

object HealthOriginOrder {
    fun key(origin: Origin): String {
        fun field(value: String) =
            value.encodeToByteArray().joinToString("") { byte ->
                (byte.toInt() and 255).toString(16).padStart(2, '0')
            } + "!"
        return originOrderParts(origin, "").joinToString("") { field(it) } +
            (origin.sequence.toULong() xor (1UL shl 63)).toString(16).padStart(16, '0') +
            "!" +
            field(normalizedOriginIdentifier(origin.originEventId))
    }
}

object HealthOriginSelector {
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun latestIntervals(records: List<EnergyInterval>, pinRevision: Long): List<EnergyInterval> {
        require(records.size <= 256) { "Use HealthIntervalRevisionCursor for paged evidence" }
        val cursor = HealthIntervalRevisionCursor(pinRevision)
        val result = mutableListOf<EnergyInterval>()
        records.sortedWith(HealthIntervalRevisionCursor::order).forEach { cursor.append(it)?.let(result::add) }
        cursor.finish()?.let(result::add)
        return result
    }

    fun union(records: List<EnergyInterval>, window: AnalysisRange): List<ActiveInterval> {
        val sorted =
            records
                .filter { valid(it) }
                .mapNotNull { record ->
                    val a = maxOf(record.startUs, window.startUs)
                    val b = minOf(record.endUs, window.endUs)
                    if (b > a) ActiveInterval(a, b) else null
                }
                .sortedWith(compareBy<ActiveInterval> { it.startUs }.thenBy { it.endUs })
        val union = mutableListOf<ActiveInterval>()
        for (span in sorted) {
            val old = union.lastOrNull()
            if (old != null && span.startUs <= old.endUs)
                union[union.lastIndex] = ActiveInterval(old.startUs, maxOf(old.endUs, span.endUs))
            else union.add(span)
        }
        return union
    }

    @Throws(ArithmeticException::class)
    fun coverage(intervals: List<ActiveInterval>): Long =
        intervals.fold(0L) { sum, span -> FixedPoint.add(sum, FixedPoint.subtract(span.endUs, span.startUs)) }

    fun valid(record: EnergyInterval): Boolean =
        record.endUs > record.startUs && record.valueKcal.isFinite() && record.valueKcal >= 0

    @Throws(IllegalArgumentException::class, IllegalStateException::class, ArithmeticException::class)
    fun energy(
        records: List<EnergyInterval>,
        source: String,
        metric: String,
        window: AnalysisRange,
        revision: Long,
    ): HealthSelection? {
        val admitted = latestIntervals(records, revision).filter { it.source.name == source && valid(it) }
        val preparation = EnergyOriginPreparation(source, window)
        admitted.sortedBy { it.startUs }.forEach { preparation.append("active", it) }
        val winner = preparation.selected() ?: return null
        val selectedRecords = admitted.filter { it.origin.dataOrigin.orEmpty() == winner.dataOrigin }
        return HealthSelection(
            metric,
            source,
            winner.dataOrigin,
            winner.coveredUs,
            selectedRecords.map { it.origin },
            Association.RideWindow(window.startUs, window.endUs),
        )
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun heartRate(records: List<HeartRateEvidence>, source: String, window: AnalysisRange): HealthSelection? {
        require(records.size <= 256) { "Use HeartRateOriginPreparation for paged evidence" }
        val groups = records.filter { it.source.name == source }.groupBy { it.origin.dataOrigin.orEmpty() }
        val scored = groups.map { (origin, values) ->
            val reducer = MetricReducer(Metrics.policy("heartRateBpm"), window)
            values
                .sortedWith(
                    compareBy<HeartRateEvidence> { it.collectionUs }
                        .thenBy { it.origin.producer }
                        .thenBy { it.origin.sequence }
                        .thenBy { it.memberIdentity ?: it.origin.originEventId }
                )
                .forEach {
                    val original =
                        it.valueBpm?.let { value ->
                            Original(
                                it.memberIdentity ?: it.origin.originEventId,
                                it.origin.originEventId,
                                it.originalStartTimestamp,
                                FixedPoint.divide(it.collectionUs, 1000000),
                                Scalar.Real64(value),
                            )
                        }
                    reducer.append(MetricReading(it.collectionUs, original, 0, it.continuity))
                }
            HealthSelection(
                "heartRateBpm",
                source,
                origin,
                reducer.result().coveredUs,
                values.map { it.origin },
                Association.RideWindow(window.startUs, window.endUs),
            )
        }
        return scored
            .sortedWith { x, y ->
                val order = y.unionCoverageUs.compareTo(x.unionCoverageUs)
                if (order == 0) compareUtf8(x.dataOrigin.orEmpty(), y.dataOrigin.orEmpty()) else order
            }
            .firstOrNull()
    }
}
