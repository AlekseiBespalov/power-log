package app.powerlog.domain.core.analysis.statistics

import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.numeric.CheckedSum
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.numeric.SignedSum
import app.powerlog.domain.core.time.TimeRange
import kotlinx.serialization.Serializable

@Serializable
internal data class MetricPoint(
    val utcUs: Long,
    val elapsedUs: Long,
    val value: Double,
    val valueQ: Long,
    val activeInterval: Int,
    val run: Int,
) {
    val ref: OriginalRef
        get() = OriginalRef(utcUs, elapsedUs, valueQ)
}

@Serializable internal data class MetricEdge(val from: MetricPoint, val to: MetricPoint)

@ConsistentCopyVisibility
@Serializable
public data class MetricState
internal constructor(
    internal val metric: MetricId,
    internal val range: TimeRange,
    internal val bucket: Boolean,
    internal val count: Long,
    internal val sum: SignedSum,
    internal val integral: SignedSum,
    internal val covered: SignedSum,
    internal val minimum: MetricPoint?,
    internal val maximum: MetricPoint?,
    internal val first: MetricPoint?,
    internal val last: MetricPoint?,
    internal val previous: MetricPoint?,
    internal val leading: MetricEdge?,
    internal val overflow: Boolean,
)

public class MetricReducer
internal constructor(private val metric: MetricId, private val range: TimeRange, private val bucket: Boolean) :
    TelemetryConsumer {
    public constructor(metric: MetricId, range: TimeRange) : this(metric, range, false)

    private val definition = MetricCatalog.of(metric)
    private val quantum = definition.quantum
    private val means = definition.allowsMean
    private var count = 0L
    private val sum = CheckedSum()
    private val integral = CheckedSum()
    private val covered = CheckedSum()
    private var minimum: MetricPoint? = null
    private var maximum: MetricPoint? = null
    private var first: MetricPoint? = null
    private var last: MetricPoint? = null
    private var previous: MetricPoint? = null
    private var leading: MetricEdge? = null
    private var overflow = false

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        if (definition.origin == MetricOrigin.location || definition.origin == MetricOrigin.health) return
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            if (metric !in session.capabilities.metrics) continue
            reading(
                rows.elapsedUs[row].toLong(),
                rows.utcUs[row].toLong(),
                rows.value(metric, row, session),
                true,
                rows.activeInterval[row],
                rows.run[row],
            )
        }
    }

    public fun append(fact: Fact) {
        when (fact) {
            is Fact.Location ->
                if (definition.origin == MetricOrigin.location) {
                    val fix = fact.fix
                    reading(
                        fix.elapsedUs,
                        fix.utcUs,
                        fix.value(metric),
                        fix.accurate(metric),
                        fix.activeInterval ?: 0,
                        fix.run,
                    )
                }
            is Fact.Health -> {
                val sample = fact.sample
                if (sample.reads(metric))
                    reading(sample.elapsedUs, sample.utcUs, sample.value, true, sample.activeInterval ?: 0, 0)
            }
            is Fact.Event -> Unit
        }
    }

    public fun merge(state: MetricState) {
        require(state.metric == metric) { "a ${state.metric} state merges into a $metric reducer" }
        overflow = overflow || state.overflow
        try {
            count = FixedPoint.add(count, state.count)
        } catch (_: ArithmeticException) {
            overflow = true
        }
        sum.merge(state.sum)
        integral.merge(state.integral)
        covered.merge(state.covered)
        val edge = state.leading
        if (edge != null && edge.from.elapsedUs < range.startUs) {
            if (bucket) {
                if (leading == null) leading = edge
            } else {
                edge(edge.from, edge.to, edge.from.elapsedUs, edge.to.elapsedUs, -1)
                edge(edge.from, edge.to, range.startUs, edge.to.elapsedUs, 1)
            }
        }
        minimum = pick(minimum, state.minimum, lower = true)
        maximum = pick(maximum, state.maximum, lower = false)
        if (first == null) first = state.first
        if (state.last != null) last = state.last
        previous = state.previous
    }

    public fun state(): MetricState =
        MetricState(
            metric,
            range,
            bucket,
            count,
            sum.snapshot(),
            integral.snapshot(),
            covered.snapshot(),
            minimum,
            maximum,
            first,
            last,
            previous,
            leading,
            overflow,
        )

    public fun result(): Bounded<MetricStatistics> {
        if (overflow) return Bounded.Capacity
        val coveredUs = covered.exact() ?: return Bounded.Capacity
        val sumQ = sum.exact() ?: return Bounded.Capacity
        val integralQUs = integral.exact() ?: return Bounded.Capacity
        val average =
            if (!means || count == 0L) null
            else
                try {
                    FixedPoint.divide(sumQ, FixedPoint.product(quantum, count))
                } catch (_: ArithmeticException) {
                    return Bounded.Capacity
                }
        return Bounded.Done(
            MetricStatistics(
                count,
                coveredUs,
                integralQUs.takeIf { means },
                average,
                minimum?.ref,
                maximum?.ref,
                first?.ref,
                last?.ref,
            )
        )
    }

    internal fun maximumValue(): Double? = maximum?.value

    internal fun reading(
        elapsedUs: Long,
        utcUs: Long,
        value: Double?,
        accurate: Boolean,
        activeInterval: Int,
        run: Int,
    ) {
        if (bucket && elapsedUs >= range.endUs) return
        if (
            activeInterval == 0 ||
                value == null ||
                !value.isFinite() ||
                !accurate ||
                definition.statisticsRange?.contains(value) == false ||
                metric == MetricId.courseDegrees && value >= FULL_TURN_DEGREES
        ) {
            previous = null
            return
        }
        val valueQ =
            try {
                FixedPoint.quantize(value, quantum)
            } catch (_: ArithmeticException) {
                overflow = true
                previous = null
                return
            }
        val point = MetricPoint(utcUs, elapsedUs, value, valueQ, activeInterval, run)
        val owned = elapsedUs >= range.startUs && elapsedUs < range.endUs
        if (owned) own(point)
        val old = previous
        if (means && old != null && old.activeInterval == activeInterval && old.run == run) {
            val durationUs = elapsedUs - old.elapsedUs
            if (durationUs > 0 && durationUs <= definition.integrationGapUs) {
                if (!bucket) edge(old, point, maxOf(old.elapsedUs, range.startUs), minOf(elapsedUs, range.endUs), 1)
                else if (owned) {
                    edge(old, point, old.elapsedUs, elapsedUs, 1)
                    if (old.elapsedUs < range.startUs) leading = MetricEdge(old, point)
                }
            }
        }
        previous = point
    }

    private fun own(point: MetricPoint) {
        try {
            count = FixedPoint.add(count, 1)
        } catch (_: ArithmeticException) {
            overflow = true
        }
        if (means) sum.add(point.valueQ)
        minimum = pick(minimum, point, lower = true)
        maximum = pick(maximum, point, lower = false)
        if (first == null) first = point
        last = point
    }

    private fun edge(old: MetricPoint, point: MetricPoint, a: Long, b: Long, sign: Int) {
        if (b <= a) return
        try {
            val durationUs = point.elapsedUs - old.elapsedUs
            val va = FixedPoint.interpolate(old.valueQ, point.valueQ, a - old.elapsedUs, durationUs)
            val vb = FixedPoint.interpolate(old.valueQ, point.valueQ, b - old.elapsedUs, durationUs)
            val area = FixedPoint.trapezoid(va, vb, b - a)
            integral.add(if (sign < 0) -area else area)
            covered.add(if (sign < 0) -(b - a) else b - a)
        } catch (_: ArithmeticException) {
            overflow = true
        }
    }

    private fun pick(kept: MetricPoint?, next: MetricPoint?, lower: Boolean): MetricPoint? {
        if (kept == null) return next
        if (next == null) return kept
        val better = if (lower) next.value < kept.value else next.value > kept.value
        return if (better || next.value == kept.value && next.elapsedUs < kept.elapsedUs) next else kept
    }

    private companion object {
        const val FULL_TURN_DEGREES = 360.0
    }
}

public data class MetricStatistics(
    public val count: Long,
    public val coveredUs: Long,
    public val integralQUs: Long?,
    public val average: Double?,
    public val minimum: OriginalRef?,
    public val maximum: OriginalRef?,
    public val first: OriginalRef?,
    public val last: OriginalRef?,
)

internal fun MetricStatistics.timeAverage(metric: MetricId): Double? {
    val integralQUs = integralQUs ?: return null
    if (coveredUs <= 0) return null
    return try {
        FixedPoint.divide(integralQUs, FixedPoint.product(MetricCatalog.of(metric).quantum, coveredUs))
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun LocationFix.value(metric: MetricId): Double? =
    when (metric) {
        MetricId.speedMps -> speedMps
        MetricId.altitudeMeters -> mslM
        MetricId.horizontalAccuracyM -> horizontalAccuracyM
        MetricId.verticalAccuracyM -> mslVerticalAccuracyM
        MetricId.courseDegrees -> courseDeg
        else -> null
    }

internal fun LocationFix.accurate(metric: MetricId): Boolean {
    val accuracy =
        when (metric) {
            MetricId.speedMps -> speedAccuracyMps
            MetricId.courseDegrees -> courseAccuracyDeg
            else -> null
        }
    return accuracy == null || accuracy.isFinite() && accuracy >= 0
}

internal fun HealthSample.reads(metric: MetricId): Boolean = this.metric?.let(HealthMetrics::metricId) == metric
