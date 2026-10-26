package app.powerlog.bridge

import app.powerlog.core.inspection.*
import java.util.concurrent.atomic.AtomicLong

internal class RidePlotSummaries(private val store: RideStore, private val analytics: RideAnalytics) {
    private class Entry {
        var summary: InspectionPlotSummary? = null
        var exhausted = false
        var selection = ""
        var revision = 0L
        var throughUs = 0L
        var cutoffs = emptySet<Long>()
        var lastRow = 0L
        var points = 0
    }

    private val entries = LinkedHashMap<Pair<String, String>, Entry>(16, 0.75f, true)
    val scanned = AtomicLong()
    val fed = AtomicLong()
    val rebuilds = AtomicLong()
    val merges = AtomicLong()
    val fallbacks = AtomicLong()
    var summaryPoints = InspectionPlotSlots.maximumPoints
    val channels: Int
        get() = synchronized(entries) { entries.size }

    val retainedPoints: Int
        get() = synchronized(entries) { entries.values.sumOf { it.points } }

    fun forget(id: String) = synchronized(entries) { entries.keys.removeAll { it.first == id } }

    fun feed(id: String, columns: Collection<String>, endUs: Long, cutoffs: Set<Long>) =
        synchronized(entries) {
            val revision = store.readRevision(id)
            columns
                .filter { !projectedMetric(it) }
                .map { it to selection(id, it) }
                .groupBy { (column, selection) -> kind(column) to selection }
                .values
                .forEach { group ->
                    val ready = group.mapNotNull { (column, selection) ->
                        open(id, column, selection, revision, cutoffs)?.let { column to it }
                    }
                    if (ready.isNotEmpty()) scan(id, ready, endUs, revision, cutoffs)
                }
        }

    fun reduce(id: String, column: String, reducer: InspectionPlotReducer, cutoffs: Set<Long>): Boolean =
        synchronized(entries) {
            val revision = store.readRevision(id)
            val endUs = reducer.range.endUs
            val projected = projectedMetric(column)
            val entry = open(id, column, selection(id, column), revision, cutoffs) ?: return false
            if (projected) {
                try {
                    feedIntervals(id, column, entry, endUs)
                } catch (error: Throwable) {
                    entry.summary = null
                    if (error !is InspectionCapacityException) throw error
                    entry.exhausted = true
                } finally {
                    account(id to column, entry)
                }
                entry.revision = revision
            } else scan(id, listOf(column to entry), endUs, revision, cutoffs)
            val summary = entry.summary ?: return false
            if (!summary.canMergeInto(reducer)) return false
            summary.mergeInto(reducer)
            if (projected && entry.throughUs <= endUs)
                RideDistanceInspection(store)
                    .boundaries(id, column, entry.throughUs, endUs)
                    .filter { it.point.timeUs >= entry.throughUs && it.point.timeUs <= endUs }
                    .forEach(reducer::appendDistance)
            merges.incrementAndGet()
            true
        }

    private fun open(id: String, column: String, selection: String, revision: Long, cutoffs: Set<Long>): Entry? {
        val entry = entries.getOrPut(id to column, ::Entry)
        if (entry.summary != null || entry.exhausted) {
            if (
                entry.selection != selection ||
                    revision > entry.revision && changed(id, column, entry, revision, cutoffs)
            ) {
                entry.summary = null
                entry.exhausted = false
            } else if (revision < entry.revision) return null
            else if (entry.exhausted) {
                entry.revision = revision
                entry.cutoffs = cutoffs
                return null
            }
        }
        if (entry.summary == null) {
            entry.summary =
                InspectionPlotSummary(
                    if (column.endsWith("DistanceMeters")) "distanceMeters" else column,
                    pointBudget = summaryPoints,
                )
            entry.selection = selection
            entry.revision = revision
            entry.throughUs = if (projectedMetric(column)) 0L else -1L
            entry.cutoffs = cutoffs
            entry.lastRow = 0L
            rebuilds.incrementAndGet()
        }
        return entry
    }

    private fun scan(id: String, ready: List<Pair<String, Entry>>, endUs: Long, revision: Long, cutoffs: Set<Long>) {
        val pending = ready.filter { (_, entry) -> entry.summary != null && entry.throughUs < endUs }
        if (pending.isNotEmpty()) {
            try {
                analytics.eachRawQuery(
                    id,
                    store.metricQuery(id, pending.first().first, elapsedOrder = true),
                    pending.minOf { it.second.throughUs } + 1,
                    endUs + 1,
                ) { row ->
                    scanned.incrementAndGet()
                    val item = analytics.item(row)
                    val boundary = row.elapsedUs in cutoffs
                    for ((_, entry) in pending) {
                        val summary = entry.summary
                        if (summary == null || row.elapsedUs <= entry.throughUs) continue
                        try {
                            summary.append(item, interruptionBoundary = boundary)
                            fed.incrementAndGet()
                        } catch (_: InspectionCapacityException) {
                            entry.summary = null
                            entry.exhausted = true
                        }
                    }
                }
            } catch (error: Throwable) {
                pending.forEach { it.second.summary = null }
                throw error
            } finally {
                pending.forEach { (column, entry) -> account(id to column, entry) }
            }
            pending.forEach { it.second.throughUs = endUs }
        }
        ready.forEach { (_, entry) ->
            entry.revision = revision
            entry.cutoffs = cutoffs
        }
    }

    private fun feedIntervals(id: String, column: String, entry: Entry, endUs: Long) {
        val summary = entry.summary ?: return
        val battery = column in consumptionMetrics
        val revision = store.readRevision(id)
        val lastEnd =
            if (battery)
                store.select(
                    "SELECT end_us FROM battery_intervals WHERE ride=? AND metric=? AND revision<=? AND (retired_revision IS NULL OR retired_revision>?) ORDER BY end_us DESC LIMIT 1",
                    arrayOf(id, column, revision, revision),
                ) { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
            else
                store.select(
                    "SELECT end_us FROM distance_intervals WHERE ride=? AND source=? AND revision<=? ORDER BY end_us DESC LIMIT 1",
                    arrayOf(id, source(column), revision),
                ) { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
        if (lastEnd == null) return
        // Later intervals start at or after the newest end, so only edges before it are final.
        val cut = minOf(lastEnd, endUs + 1)
        var from = entry.throughUs
        while (from < cut) {
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val to = minOf(cut, from + WINDOW_US)
            RideDistanceInspection(store)
                .boundaries(id, column, from, to - 1)
                .filter { it.point.timeUs >= from && it.point.timeUs < to }
                .forEach {
                    scanned.incrementAndGet()
                    summary.appendDistance(it)
                    fed.incrementAndGet()
                }
            from = to
        }
        entry.throughUs = maxOf(entry.throughUs, cut)
        if (battery)
            entry.lastRow =
                store.select(
                    "SELECT to_id FROM battery_intervals WHERE ride=? AND metric=? AND revision<=? ORDER BY to_id DESC LIMIT 1",
                    arrayOf(id, column, revision),
                ) { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
    }

    private fun changed(id: String, column: String, entry: Entry, revision: Long, cutoffs: Set<Long>): Boolean {
        fun exists(sql: String, vararg args: Any): Boolean =
            store.select(sql, arrayOf(id, *args)) {
                it.moveToFirst()
            }
        val after = entry.revision
        if (column in consumptionMetrics)
            return exists(
                "SELECT 1 FROM lifecycle WHERE ride=? AND revision>? AND revision<=? LIMIT 1",
                after,
                revision,
            ) ||
                exists(
                    "SELECT 1 FROM battery_intervals WHERE ride=? AND retired_revision>? AND retired_revision<=? LIMIT 1",
                    after,
                    revision,
                ) ||
                exists(
                    "SELECT 1 FROM battery_intervals WHERE ride=? AND metric=? AND to_id>? AND revision<=? AND start_us<? LIMIT 1",
                    column,
                    entry.lastRow,
                    revision,
                    entry.throughUs,
                )
        if (projectedMetric(column))
            return exists(
                "SELECT 1 FROM distance_intervals WHERE ride=? AND source=? AND revision>? AND revision<=? AND start_us<? LIMIT 1",
                source(column),
                after,
                revision,
                entry.throughUs,
            )
        val kind = kind(column)
        return exists(
            "SELECT 1 FROM observations WHERE ride=? AND revision>? AND revision<=? AND kind=? AND elapsed_us<=? LIMIT 1",
            after,
            revision,
            kind,
            entry.throughUs,
        ) ||
            kind == "health" &&
                exists(
                    "SELECT 1 FROM health_changes WHERE ride=? AND revision>? AND revision<=? LIMIT 1",
                    after,
                    revision,
                ) ||
            cutoffs.filterTo(mutableSetOf()) { it <= entry.throughUs } !=
                entry.cutoffs.filterTo(mutableSetOf()) { it <= entry.throughUs }
    }

    private fun account(key: Pair<String, String>, entry: Entry) {
        entry.points = entry.summary?.retainedPoints ?: 0
        var total = entries.values.sumOf { it.points }
        val retained = entries.entries.iterator()
        while (retained.hasNext() && (total > POINT_BUDGET || entries.size > ENTRY_LIMIT)) {
            val next = retained.next()
            if (next.key == key) continue
            total -= next.value.points
            retained.remove()
        }
    }

    private fun selection(id: String, column: String) =
        if (!projectedMetric(column) && kind(column) == "health") HealthReadings(store).predicate(id, column) else ""

    private fun kind(column: String) =
        when (column) {
            in telemetryMetrics -> "telemetry"
            in locationMetrics -> "location"
            else -> "health"
        }

    private fun source(column: String) =
        when (column) {
            "gpsDistanceMeters" -> "gps:phone"
            "controllerDistanceMeters" -> "controller"
            else -> column
        }

    companion object {
        private const val WINDOW_US = 64_000_000L
        private const val POINT_BUDGET = 65_536
        private const val ENTRY_LIMIT = 64
    }
}
