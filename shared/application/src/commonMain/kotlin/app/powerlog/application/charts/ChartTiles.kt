package app.powerlog.application.charts

import app.powerlog.application.FactReader
import app.powerlog.application.FactSink
import app.powerlog.application.FactStreams
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.Extent
import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.charts.PlotReducer
import app.powerlog.domain.charts.PlotTiles
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAtomicApi::class)
internal class ChartTiles(private val cache: CacheRepository, private val scans: CoroutineDispatcher) {
    class Ride(
        val rideId: RideId,
        val revision: Long,
        val extent: Extent,
        val recording: Boolean,
        val reader: FactReader,
        val memory: Boolean = false,
    )

    private val building = AtomicReference<Map<RideId, Int>>(emptyMap())
    private val imported = AtomicReference<Map<RideId, Map<Pair<MetricId, Int>, Map<Long, PlotAggregate>>>>(emptyMap())

    fun drop(rideId: RideId) {
        while (true) {
            val current = imported.load()
            if (rideId !in current || imported.compareAndSet(current, current - rideId)) return
        }
    }

    private fun held(rideId: RideId, metric: MetricId, level: Int): Map<Long, PlotAggregate> =
        imported.load()[rideId]?.get(metric to level).orEmpty()

    private fun hold(rideId: RideId, metric: MetricId, level: Int, tiles: Map<Long, PlotAggregate>) {
        while (true) {
            val current = imported.load()
            val ride = current[rideId].orEmpty()
            val next = current + (rideId to ride + ((metric to level) to held(rideId, metric, level) + tiles))
            if (imported.compareAndSet(current, next)) return
        }
    }

    fun catchingUp(rideId: RideId): Boolean = (building.load()[rideId] ?: 0) > 0

    suspend fun tiles(
        ride: Ride,
        metric: MetricId,
        level: Int,
        slotUs: Long,
        parts: LongRange,
        before: suspend (FactSink) -> Unit,
    ): Map<Long, PlotAggregate> {
        val cached =
            if (ride.memory) held(ride.rideId, metric, level).filterKeys { it in parts }
            else cache.tiles(ride.rideId, metric, level, parts, ride.revision)
        val missing = parts.filter { it !in cached }
        if (missing.isEmpty()) return cached
        change(ride.rideId, 1)
        try {
            val span = missing.first()..missing.last()
            val builder = TileBuilder(metric, slotUs)
            before(builder)
            val location = ChartMetrics.kind(metric) == ChartKind.location
            withContext(scans) {
                ride.reader.feed(
                    TimeRange(span.first * slotUs, (span.last + 1) * slotUs),
                    FactStreams(telemetry = !location, locations = location, health = null),
                    builder,
                )
            }
            val built = builder.result().filterKeys { it in missing }
            val complete = missing.associateWith { built[it] ?: TileBuilder.empty(metric, slotUs, it) }
            if (ride.memory) {
                hold(ride.rideId, metric, level, complete)
                return cached + complete
            }
            val newestUs = (if (location) ride.extent.locations else ride.extent.telemetry)?.endUs ?: 0L
            val final = complete.filterKeys { (it + 1) * slotUs <= newestUs - 1 - ChartsPolicy.finalTileLagUs }
            if (final.isNotEmpty()) cache.putTiles(ride.rideId, metric, level, 0, final)
            val others = complete - final.keys
            if (!ride.recording && others.isNotEmpty())
                cache.putTiles(ride.rideId, metric, level, ride.revision, others)
            return cached + complete
        } finally {
            change(ride.rideId, -1)
        }
    }

    class Covered(val range: TimeRange, val aggregate: PlotAggregate)

    suspend fun covering(ride: Ride, metric: MetricId, range: TimeRange): List<Covered> {
        val chosen = mutableListOf<Covered>()
        for (level in MAX_LEVEL downTo 0) {
            val slotUs = PlotTiles.baseSlotUs shl level
            val first = (range.startUs + slotUs - 1).floorDiv(slotUs)
            val last = range.endUs.floorDiv(slotUs) - 1
            if (first > last) continue
            for ((part, aggregate) in cache.tiles(ride.rideId, metric, level, first..last, ride.revision)) {
                val slot = TimeRange(part * slotUs, (part + 1) * slotUs)
                if (chosen.none { it.range.startUs < slot.endUs && slot.startUs < it.range.endUs })
                    chosen += Covered(slot, aggregate)
            }
        }
        return chosen.sortedBy { it.range.startUs }
    }

    private fun change(rideId: RideId, by: Int) {
        while (true) {
            val current = building.load()
            val count = (current[rideId] ?: 0) + by
            val next = if (count <= 0) current - rideId else current + (rideId to count)
            if (building.compareAndSet(current, next)) return
        }
    }

    private class TileBuilder(private val metric: MetricId, private val slotUs: Long) : FactSink {
        private val done = mutableMapOf<Long, PlotAggregate>()
        private var slot: Long? = null
        private var reducer: PlotReducer? = null
        private var lastRows: TelemetryRows? = null
        private var lastRow = -1
        private var lastSessions: Map<Int, ControllerSession> = emptyMap()
        private var lastFix: LocationFix? = null

        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
            var start = from
            while (start < to) {
                val part = rows.elapsedUs[start].toLong().floorDiv(slotUs)
                var end = start + 1
                while (end < to && rows.elapsedUs[end].toLong().floorDiv(slotUs) == part) end++
                reducer(part).append(rows, start, end, sessions)
                lastRows = rows
                lastRow = end - 1
                lastSessions = sessions
                start = end
            }
        }

        override fun append(fact: Fact) {
            val fix = (fact as? Fact.Location)?.fix ?: return
            reducer(fix.elapsedUs.floorDiv(slotUs)).append(fact)
            lastFix = fix
        }

        fun result(): Map<Long, PlotAggregate> {
            finish()
            return done
        }

        private fun reducer(part: Long): PlotReducer {
            val current = reducer
            if (current != null && slot == part) return current
            finish()
            val next = PlotReducer(metric, TimeRange(part * slotUs, (part + 1) * slotUs), slotUs, 1)
            lastRows?.let { next.append(it, lastRow, lastRow + 1, lastSessions) }
            lastFix?.let { next.append(Fact.Location(it)) }
            slot = part
            reducer = next
            return next
        }

        private fun finish() {
            val current = reducer ?: return
            done[requireNotNull(slot)] = current.aggregate()
            reducer = null
            slot = null
        }

        companion object {
            fun empty(metric: MetricId, slotUs: Long, part: Long): PlotAggregate =
                PlotReducer(metric, TimeRange(part * slotUs, (part + 1) * slotUs), slotUs, 1).aggregate()
        }
    }

    private companion object {
        const val MAX_LEVEL = 10
    }
}
