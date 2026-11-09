package app.powerlog.domain.core.ride

import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.time.RideTiming
import app.powerlog.domain.core.time.TimeRange

public data class RideEvent(
    public val id: EventId,
    public val kind: RideEventKind,
    public val utcUs: Long,
    public val elapsedUs: Long,
)

public data class LapSpan(public val index: Int, public val range: TimeRange, public val timerUs: Long)

public class ActiveTimeline(private val endUs: Long, events: List<RideEvent>) {
    public val intervals: List<TimeRange>

    public val laps: List<LapSpan>

    public val timerUs: Long

    private val activeThroughEnd: Boolean

    init {
        require(endUs >= 0) { "a timeline ends at or after the ride's start: $endUs" }
        val spans = mutableListOf<TimeRange>()
        val lapEnds = mutableListOf<Long>()
        var open: Long? = 0
        for (event in events) {
            val t = event.elapsedUs
            if (t < 0 || t > endUs) continue
            when (event.kind) {
                RideEventKind.pause ->
                    open?.let {
                        spans += TimeRange(it, t)
                        open = null
                    }
                RideEventKind.resume -> if (open == null) open = t
                RideEventKind.lap -> if (t > 0 && t < endUs && lapEnds.lastOrNull() != t) lapEnds += t
                RideEventKind.start,
                RideEventKind.stop -> Unit
            }
        }
        open?.let { spans += TimeRange(it, endUs) }
        activeThroughEnd = open != null
        intervals = spans
        timerUs = active(0, endUs)
        val marks = listOf(0L) + lapEnds + endUs
        laps =
            marks.zipWithNext().mapIndexed { index, (start, end) ->
                LapSpan(index, TimeRange(start, end), active(start, end))
            }
    }

    public fun timerAt(elapsedUs: Long): Long = active(0, elapsedUs)

    public fun intervalAt(elapsedUs: Long): Int? {
        if (elapsedUs < 0 || elapsedUs > endUs) return null
        for ((index, interval) in intervals.withIndex()) {
            val final = activeThroughEnd && index == intervals.lastIndex && elapsedUs == endUs
            if (elapsedUs >= interval.startUs && (elapsedUs < interval.endUs || final)) return index + 1
        }
        return null
    }

    private fun active(startUs: Long, endUs: Long): Long = intervals.sumOf {
        maxOf(0L, minOf(endUs, it.endUs) - maxOf(startUs, it.startUs))
    }
}

public data class RetainedEnd(public val utcUs: Long, public val elapsedUs: Long)

public object FinishBoundary {
    private val candidates = setOf(RideEventKind.start, RideEventKind.pause, RideEventKind.resume, RideEventKind.lap)

    public fun of(startedUtcUs: Long, lastFact: RetainedEnd?, events: List<RideEvent>): RideTiming {
        val retained =
            listOfNotNull(lastFact) +
                events.filter { it.kind in candidates }.map { RetainedEnd(it.utcUs, it.elapsedUs) }
        val last =
            retained.maxByOrNull { it.elapsedUs }?.takeIf { it.elapsedUs >= 0 } ?: return RideTiming(startedUtcUs, 0, 0)
        return RideTiming(last.utcUs, last.elapsedUs, ActiveTimeline(last.elapsedUs, events).timerAt(last.elapsedUs))
    }
}
