package app.powerlog.domain.charts

import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.analysis.statistics.accurate
import app.powerlog.domain.core.analysis.statistics.reads
import app.powerlog.domain.core.analysis.statistics.value
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.OriginalRef
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.metric.MetricOrigin
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.time.TimeRange
import kotlin.math.abs

public data class InspectionTarget(public val atUs: Long, public val anchor: OriginalRef?)

public class InspectionReducer(
    private val metric: MetricId,
    private val target: InspectionTarget,
    range: TimeRange?,
    private val live: Boolean,
) : TelemetryConsumer {
    private val definition = MetricCatalog.of(metric)
    private val derived = metric in distances || metric in consumption
    private val statistics = if (range == null || derived) null else MetricReducer(metric, range)
    private val lines = PlotReducer(metric, TimeRange(0, 1), null, 1)
    private var before: PlotOriginal? = null
    private var after: PlotOriginal? = null
    private var brokenBefore = false
    private var brokenAfter = false
    private var anchored: OriginalRef? = null
    private var exact: OriginalRef? = null
    private var nearest: OriginalRef? = null
    private var containing: Pair<OriginalRef, OriginalRef>? = null
    private var arrivals = 0L
    private var pausedUs = Long.MIN_VALUE

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        if (definition.origin != MetricOrigin.telemetry && metric != MetricId.controllerSpeedMps) return
        for (row in from until to) {
            val session = sessions[rows.sessionNo[row]] ?: continue
            if (metric !in session.capabilities.metrics) continue
            val elapsedUs = rows.elapsedUs[row].toLong()
            val order = rows.sessionNo[row].toLong() * SEQ_SPAN + rows.seq[row].toLong()
            val value = rows.value(metric, row, session)
            reading(
                elapsedUs,
                lines.original(
                    rows.utcUs[row].toLong(),
                    elapsedUs,
                    value,
                    order,
                    rows.activeInterval[row],
                    lineRun(rows, row, session),
                ),
            )
        }
        statistics?.append(rows, from, to, sessions)
    }

    public fun append(fact: Fact) {
        when (fact) {
            is Fact.Location ->
                if (definition.origin == MetricOrigin.location) {
                    val fix = fact.fix
                    val value = fix.value(metric)?.takeIf { fix.accurate(metric) }
                    reading(
                        fix.elapsedUs,
                        lines.original(fix.utcUs, fix.elapsedUs, value, fix.seq, fix.activeInterval ?: 0, 0),
                    )
                    statistics?.append(fact)
                }
            is Fact.Health ->
                if (definition.origin == MetricOrigin.health && fact.sample.reads(metric)) {
                    val sample = fact.sample
                    val order = arrivals++
                    reading(
                        sample.elapsedUs,
                        lines.original(
                            sample.utcUs,
                            sample.elapsedUs,
                            sample.value,
                            order,
                            sample.activeInterval ?: 0,
                            0,
                        ),
                    )
                    statistics?.append(fact)
                }
            is Fact.Event -> {
                val event = fact.event
                if (event.kind == RideEventKind.pause && event.elapsedUs < target.atUs)
                    pausedUs = maxOf(pausedUs, event.elapsedUs)
            }
        }
    }

    public fun distance(interval: DistanceInterval) {
        if (metric in distances) boundaries(interval.start, interval.end)
    }

    public fun battery(interval: ConsumptionInterval) {
        if (interval.metric.name != metric.name) return
        val start = quantize(interval.startValue) ?: return
        val end = quantize(interval.endValue) ?: return
        boundaries(
            OriginalRef(interval.utc.startUtcUs, interval.range.startUs, start),
            OriginalRef(interval.utc.endUtcUs, interval.range.endUs, end),
        )
    }

    public fun result(): Bounded<InspectionResult> {
        val reduced =
            when (val result = statistics?.result()) {
                null -> null
                is Bounded.Done -> result.value
                Bounded.Capacity -> return Bounded.Capacity
            }
        return Bounded.Done(InspectionResult(reduced, selection(), nearest ?: closest(before?.ref, after?.ref)))
    }

    private fun reading(elapsedUs: Long, original: PlotOriginal?) {
        val anchor = target.anchor
        if (original != null && anchor != null && matches(original.ref, anchor)) anchored = original.ref
        if (elapsedUs <= target.atUs) {
            val kept = before
            if (original == null) {
                if (kept != null) brokenBefore = true
            } else if (kept == null || elapsedUs > kept.ref.elapsedUs) {
                before = original
                brokenBefore = false
            }
        }
        if (elapsedUs >= target.atUs && after == null) {
            if (original == null) brokenAfter = true else after = original
        }
    }

    private fun boundaries(start: OriginalRef, end: OriginalRef) {
        val anchor = target.anchor
        for (ref in listOf(start, end)) {
            if (anchor != null && matches(ref, anchor) && anchored == null) anchored = ref
            if (ref.elapsedUs == target.atUs && exact == null) exact = ref
            nearest = closest(nearest, ref)
        }
        if (
            containing == null &&
                end.elapsedUs > start.elapsedUs &&
                start.elapsedUs <= target.atUs &&
                target.atUs <= end.elapsedUs
        )
            containing = start to end
    }

    private fun selection(): OriginalRef? {
        anchored?.let {
            return it
        }
        if (derived) return exact ?: containing?.let { (start, end) -> closest(start, end) }
        val left = before
        val right = after
        if (left?.ref?.elapsedUs == target.atUs) return left.ref
        if (right?.ref?.elapsedUs == target.atUs) return right.ref
        if (left == null) return null
        if (right == null)
            return left.ref.takeIf {
                live && !brokenBefore && pausedUs < it.elapsedUs && target.atUs - it.elapsedUs < gapUs()
            }
        if (brokenBefore || brokenAfter || lines.starts(left, right, course = false)) return null
        return closest(
            left.ref.takeIf { target.atUs - it.elapsedUs < gapUs() },
            right.ref.takeIf { it.elapsedUs - target.atUs < gapUs() },
        )
    }

    private fun gapUs(): Long = if (metric in distances) DISTANCE_GAP_US else definition.plotGapUs

    private fun matches(ref: OriginalRef, anchor: OriginalRef): Boolean =
        ref.elapsedUs == anchor.elapsedUs && ref.utcUs == anchor.utcUs && abs(anchor.elapsedUs - target.atUs) <= 1

    private fun closest(a: OriginalRef?, b: OriginalRef?): OriginalRef? {
        if (a == null) return b
        if (b == null) return a
        val da = abs(a.elapsedUs - target.atUs)
        val db = abs(b.elapsedUs - target.atUs)
        return if (db < da || db == da && b.elapsedUs < a.elapsedUs) b else a
    }

    private fun quantize(value: Double): Long? =
        try {
            FixedPoint.quantize(value, definition.quantum)
        } catch (_: ArithmeticException) {
            null
        }

    private companion object {
        const val SEQ_SPAN = 1L shl 40
        const val DISTANCE_GAP_US = 6_000_000L
        val distances = setOf(MetricId.distanceMeters, MetricId.gpsDistanceMeters, MetricId.controllerDistanceMeters)
        val consumption =
            setOf(
                MetricId.controllerEnergyWh,
                MetricId.controllerChargeAh,
                MetricId.calculatedEnergyWh,
                MetricId.calculatedChargeAh,
            )
    }
}

public data class InspectionResult(
    public val statistics: MetricStatistics?,
    public val selection: OriginalRef?,
    public val reference: OriginalRef?,
)
