package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*
import kotlin.math.*
import org.json.JSONObject

internal class RideAnalytics(private val store: RideStore) {
    private class CachedNode(val payload: String, metric: String, range: AnalysisRange, plotOnly: Boolean) {
        private val data by lazy {
            runCatching { JSONObject(payload).let { if (plotOnly) JSONObject().put("plot", it) else it } }.getOrNull()
        }
        val plot by lazy {
            data?.let {
                runCatching {
                    MetricCache.decodePlot(
                        it,
                        if (metric.endsWith("DistanceMeters")) "distanceMeters" else metric,
                        range,
                    )
                }
                    .getOrNull()
            }
        }
        val statistics by lazy {
            data?.let { runCatching { MetricCache.decode(it, metric, range) }.getOrNull() }
        }
    }

    val summaries = RidePlotSummaries(store, this)
    private val decoded = mutableMapOf<String, CachedNode>()
    private var decodedRevision = ""

    private fun node(
        id: String,
        metric: String,
        bucket: Long,
        payload: String,
        readRevision: Long,
        plotOnly: Boolean = false,
        span: Long = 1,
    ): CachedNode {
        val revision = "$id:$readRevision"
        val key = "$metric:$bucket:$plotOnly:$span"
        synchronized(decoded) {
            if (decodedRevision != revision) {
                decoded.clear()
                decodedRevision = revision
            }
            decoded[key]
                ?.takeIf { it.payload == payload }
                ?.let {
                    return it
                }
            val range = AnalysisRange(bucket * span * 16000000, (bucket + 1) * span * 16000000)
            return CachedNode(payload, metric, range, plotOnly).also { decoded[key] = it }
        }
    }

    private val healthMetrics = listOf("heartRateBpm", "activeEnergyKcal")
    private val changes = ThreadLocal.withInitial {
        mutableMapOf<Pair<String, Long>, MutableSet<String>>()
    }
    private val dirty
        get() = requireNotNull(changes.get())

    fun changed(id: String, time: Double, metrics: Collection<String>) {
        val bucket = floor(time / BLOCK).toLong()
        changedBlocks(id, bucket, bucket, metrics)
    }

    fun intervalChanged(id: String, startUs: Long, endUs: Long, metrics: Collection<String>) =
        changedBlocks(id, Math.floorDiv(startUs, 16000000L), Math.floorDiv(endUs, 16000000L), metrics)

    private fun changedBlocks(id: String, first: Long, last: Long, metrics: Collection<String>) {
        val selected = metrics.filter { it !in listOf("latitude", "longitude") }
        for (bucket in first..last + 1) dirty.getOrPut(id to bucket) { mutableSetOf() }.addAll(selected)
    }

    fun trimBefore(id: String, cutoff: Double) {
        check(id.startsWith("live-") && store.writableDatabase.inTransaction())
        val bucket = floor(cutoff / BLOCK).toLong()
        store.writableDatabase.delete("analytics", "ride=? AND bucket<?", arrayOf(id, bucket.toString()))
        store.writableDatabase.delete(
            "analytics_plot_blocks",
            "ride=? AND bucket<?",
            arrayOf(id, Math.floorDiv(bucket, PLOT_BUCKETS).toString()),
        )
        dirty.keys.removeAll { it.first == id && it.second < bucket }
        // Rebuild the partially retained boundary before committing the same preview revision.
        changed(id, cutoff, store.available(id))
    }

    fun clear() = changes.remove()

    fun flush() {
        val healthOrigins =
            dirty.keys
                .map { it.first }
                .distinct()
                .associateWith { id ->
                    if (dirty.any { it.key.first == id && it.value.any { metric -> metric in healthMetrics } })
                        HealthReadings(store).origins(id)
                    else emptyMap()
                }
        flushSelected(healthOrigins)
    }

    private fun flushSelected(healthOrigins: Map<String, Map<String, String?>>) {
        for ((id, origins) in healthOrigins) for (metric in healthMetrics) {
            if (origins.isEmpty()) continue
            val measure =
                when (metric) {
                    "heartRateBpm" -> "heartRate"
                    "activeEnergyKcal" -> "activeEnergy"
                    else -> error("Unsupported Android Health metric")
                }
            val selected = origins[measure]
            val prior =
                store.select(
                    "SELECT json_extract(payload,'$.healthOrigin') FROM analytics WHERE ride=? AND metric=? ORDER BY revision DESC LIMIT 1",
                    arrayOf(id, metric),
                ) { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            if (selected != prior) {
                val buckets =
                    store.select(
                        "SELECT DISTINCT bucket FROM analytics WHERE ride=? AND metric=?",
                        arrayOf(id, metric),
                    ) { c ->
                        buildList { while (c.moveToNext()) add(c.getLong(0)) }
                    }
                buckets.forEach { dirty.getOrPut(id to it) { mutableSetOf() }.add(metric) }
            }
        }
        val queries = mutableMapOf<Pair<String, String>, String>()
        val timelines = mutableMapOf<String, ActiveTimeline>()
        val revisions = mutableMapOf<String, Long>()
        dirty.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).forEach { (key, metrics) ->
            val (id, bucket) = key
            val start = bucket * BLOCK
            val raws = mutableMapOf<String, List<Observation>>()
            val predecessors = mutableMapOf<String, Observation?>()
            metrics.forEach { metric ->
                val measure =
                    when (metric) {
                        "heartRateBpm" -> "heartRate"
                        "activeEnergyKcal" -> "activeEnergy"
                        else -> null
                    }
                val origin = healthOrigins[id]?.get(measure)
                val query = queries.getOrPut(id to metric) { store.metricQuery(id, metric, elapsedOrder = true) }
                val raw = raws.getOrPut(query) { rawRows(id, query, start, start + BLOCK) }
                val range = AnalysisRange(elapsedUs(start), elapsedUs(start + BLOCK))
                val plotMetric = if (metric.endsWith("DistanceMeters")) "distanceMeters" else metric
                val timeline = timelines.getOrPut(id) { RideAnalysis(store).timeline(id) }
                val plot =
                    try {
                        val reducer = InspectionPlotReducer(plotMetric, range, 1, owned = true)
                        if (projectedMetric(metric))
                            RideDistanceInspection(store)
                                .boundaries(id, metric, range.startUs, range.endUs)
                                .forEach(reducer::appendDistance)
                        else {
                            val cutoffs = timeline.interruptions.map { it.cutoffUs }.toSet()
                            raw.forEach { reducer.append(item(it), interruptionBoundary = it.elapsedUs in cutoffs) }
                        }
                        MetricCache.encodePlot(reducer.aggregate())
                    } catch (_: InspectionCapacityException) {
                        null
                    }
                val checkpoint =
                    if (projectedMetric(metric)) null
                    else {
                        val predecessor =
                            if (query in predecessors) predecessors[query]
                            else neighbor(id, query, range.startUs, true).also { predecessors[query] = it }
                        reduceBucket(metric, start, raw, timeline, predecessor)
                    }
                val payload =
                    json(
                        mapOf(
                            "checkpoint" to checkpoint?.let(MetricCache::encode),
                            "plot" to plot,
                            "healthOrigin" to origin,
                        )
                    )
                store.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO analytics(ride,metric,bucket,revision,first,last,payload) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(
                        id,
                        metric,
                        bucket,
                        revisions.getOrPut(id) { store.revision(id) },
                        raw.firstOrNull()?.time ?: start,
                        raw.lastOrNull()?.time ?: start,
                        payload.toString(),
                    ),
                )
                store.pruneCheckpoints(id, metric, bucket)
            }
        }
        val completed =
            healthOrigins.keys.associateWith { floor(store.timing(it).elapsed / (BLOCK * PLOT_BUCKETS)).toLong() }
        val parents =
            dirty.entries
                .flatMap { (key, metrics) ->
                    metrics
                        .filter { !it.endsWith("DistanceMeters") }
                        .map {
                            Triple(key.first, it, Math.floorDiv(key.second, PLOT_BUCKETS))
                        }
                }
                .filter { (id, _, bucket) -> bucket < completed.getValue(id) }
                .toMutableSet()
        val closures =
            dirty.entries
                .flatMap { (key, metrics) ->
                    metrics
                        .filter { !it.endsWith("DistanceMeters") }
                        .map { Triple(key.first, it, Math.floorDiv(key.second, PLOT_BUCKETS) - 1) }
                }
                .distinct()
                .filter { (id, _, bucket) -> bucket >= 0 && bucket < completed.getValue(id) }
                .filter { it !in parents }
                .groupBy { it.first to it.third }
        for ((key, candidates) in closures) {
            val metrics = candidates.map { it.second }
            val existing =
                store.select(
                    "SELECT DISTINCT metric FROM analytics_plot_blocks WHERE ride=? AND metric IN (${metrics.joinToString { "?" }}) AND bucket=?",
                    (listOf(key.first) + metrics + key.second).toTypedArray(),
                ) { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
            parents.addAll(candidates.filter { it.second !in existing })
        }
        parents.forEach { (id, metric, bucket) -> flushPlotBlock(id, metric, bucket) }
        dirty.clear()
    }

    private fun flushPlotBlock(id: String, metric: String, bucket: Long) {
        val revision = store.revision(id)
        val low = bucket * PLOT_BUCKETS
        val range = AnalysisRange(low * 16000000, (low + PLOT_BUCKETS) * 16000000)
        val reducer = InspectionPlotReducer(metric, range, 1, owned = true)
        var valid = true
        plotNodes(id, metric, low, low + PLOT_BUCKETS - 1, revision) { child, payload ->
            val aggregate = payload?.let {
                runCatching {
                    MetricCache.decodePlot(
                        JSONObject().put("plot", JSONObject(it)),
                        metric,
                        AnalysisRange(child * 16000000, (child + 1) * 16000000),
                    )
                }
                    .getOrNull()
            }
            if (aggregate == null || !reducer.canMerge(aggregate)) valid = false
            else if (valid) {
                try {
                    reducer.merge(aggregate)
                } catch (_: InspectionCapacityException) {
                    valid = false
                }
            }
        }
        val payload =
            if (valid)
                runCatching { json(mapOf("plot" to MetricCache.encodePlot(reducer.aggregate()))).toString() }
                    .getOrNull()
            else null
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO analytics_plot_blocks(ride,metric,bucket,revision,payload) VALUES(?,?,?,?,?)",
            arrayOf(id, metric, bucket, revision, payload),
        )
        store.pruneCheckpoints(id, metric, bucket, plot = true)
    }

    private fun plotNodes(
        id: String,
        metric: String,
        low: Long,
        high: Long,
        revision: Long,
        span: Long = 1,
        body: (Long, String?) -> Unit,
    ) {
        val coarse = span != 1L
        if (!coarse && metric in consumptionMetrics) {
            // Enumerate occupied blocks after cache loss; a long pause must not trigger a query for every empty block.
            var after = low - 1
            val intervalPredicate =
                "ride=? AND metric=? AND end_us>=? AND end_us<? AND revision<=$revision AND (retired_revision IS NULL OR retired_revision>$revision)"
            val bounds =
                listOf(
                    id,
                    metric,
                    low * 16000000,
                    (high + 1) * 16000000 + BatteryConsumptionPolicy.maximumCounterEdgeUs,
                )
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
                val rows =
                    store.select(
                        "SELECT b.bucket,CASE WHEN json_valid(a.payload) THEN json_extract(a.payload,'$.plot') END FROM (SELECT CAST(start_us/16000000 AS INTEGER) AS bucket FROM battery_intervals WHERE $intervalPredicate UNION SELECT CAST(end_us/16000000 AS INTEGER) AS bucket FROM battery_intervals WHERE $intervalPredicate) b LEFT JOIN (${checkpoints(id, revision)}) a ON a.ride=? AND a.metric=? AND a.bucket=b.bucket WHERE b.bucket>? AND b.bucket<=? ORDER BY b.bucket LIMIT 128",
                        (bounds + bounds + listOf(id, metric, after, high)).toTypedArray(),
                    ) { c ->
                        buildList {
                            while (c.moveToNext()) add(c.getLong(0) to if (c.isNull(1)) null else c.getString(1))
                        }
                    }
                rows.forEach { (bucket, payload) -> body(bucket, payload) }
                if (rows.size < 128) return
                after = rows.last().first
            }
        }
        val fillMissing = coarse
        val first = if (coarse) Math.floorDiv(low, span) else low
        val last = if (coarse) Math.floorDiv(high, span) else high
        var expected = first
        var afterBucket: Long? = null
        while (true) {
            if (fillMissing && expected > last) return
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val pageEnd = if (fillMissing) minOf(last, expected + 127) else last
            val continuation = if (fillMissing || afterBucket == null) "" else "AND bucket>?"
            val args =
                listOf(id, metric, if (fillMissing) expected else first, pageEnd) +
                    if (fillMissing) emptyList() else listOfNotNull(afterBucket)
            var read = 0
            store.select(
                "SELECT bucket,CASE WHEN json_valid(payload) THEN json_extract(payload,'$.plot') END FROM (${checkpoints(id, revision, plot = coarse)}) WHERE ride=? AND metric=? AND bucket>=? AND bucket<=? $continuation ORDER BY bucket LIMIT 128",
                args.toTypedArray(),
            ) { c ->
                while (c.moveToNext()) {
                    read++
                    val bucket = c.getLong(0)
                    if (fillMissing)
                        while (expected < bucket) {
                            body(expected, null)
                            expected++
                        }
                    body(bucket, if (c.isNull(1)) null else c.getString(1))
                    expected = bucket + 1
                    afterBucket = bucket
                }
            }
            if (fillMissing)
                while (expected <= pageEnd) {
                    body(expected, null)
                    expected++
                }
            else if (read < 128) return
        }
    }

    private fun checkpoints(id: String, revision: Long = store.readRevision(id), plot: Boolean = false): String {
        val table = if (plot) "analytics_plot_blocks" else "analytics"
        return "SELECT a.* FROM $table a WHERE a.revision<=${revision} AND a.revision=(SELECT max(b.revision) FROM $table b WHERE b.ride=a.ride AND b.metric=a.metric AND b.bucket=a.bucket AND b.revision<=${revision})"
    }

    fun item(row: Observation, statistics: Boolean = false): AnalysisItem =
        row.analysisItem().let {
            it.copy(
                continuity = row.displayContinuity().copy(connectionEpoch = row.epoch),
                values = if (statistics && !row.active) emptyMap() else it.values,
            )
        }

    fun plotValid(row: Observation, metric: String): Boolean =
        if (projectedMetric(metric)) Metrics.plotValid("distanceMeters", row.values[metric]?.let(Scalar::Real64))
        else Metrics.plotValid(item(row), metric)

    fun plotNeighbor(
        id: String,
        metric: String,
        time: Double,
        before: Boolean,
        inclusive: Boolean = false,
    ): Observation? {
        val order = if (before) "DESC" else "ASC"
        val op = (if (before) "<" else ">") + if (inclusive) "=" else ""
        val bucket = if (time == Double.MAX_VALUE) Long.MAX_VALUE else floor(time / BLOCK).toLong()
        val bound = if (before) "bucket<=? AND first$op?" else "bucket>=? AND last$op?"
        val cached =
            if (time == Double.MAX_VALUE || time == -Double.MAX_VALUE) null
            else
                store.select(
                    "SELECT bucket,payload FROM (${checkpoints(id)}) WHERE ride=? AND metric=? AND $bound ORDER BY bucket $order LIMIT 1",
                    arrayOf(id, metric, bucket, time),
                ) { c ->
                    if (!c.moveToFirst() || c.getLong(0) == bucket) null
                    else
                        try {
                            val aggregate =
                                MetricCache.decodePlot(
                                    JSONObject(c.getString(1)),
                                    metric,
                                    AnalysisRange(c.getLong(0) * 16000000, (c.getLong(0) + 1) * 16000000),
                                )
                            (if (before) aggregate.points.lastOrNull() else aggregate.points.firstOrNull())
                                ?.point
                                ?.original
                                ?.let { rowId(id, it) }
                        } catch (_: Exception) {
                            null
                        }
                }
        cached?.let { row ->
            store.metricObservation(id, metric, row)?.let {
                return it
            }
        }
        var offset = 0L
        while (true) {
            val rows =
                store.select(
                    "SELECT * FROM (${store.metricQuery(id, metric, elapsedOrder = true, idOrder = true)}) WHERE elapsed_us${if (before) "<=" else ">="}? AND time$op? ORDER BY elapsed_us $order,id $order LIMIT 16 OFFSET ?",
                    arrayOf(
                        id,
                        when (time) {
                            Double.MAX_VALUE -> Long.MAX_VALUE
                            -Double.MAX_VALUE -> Long.MIN_VALUE
                            else -> elapsedUs(time)
                        },
                        time,
                        offset,
                    ),
                ) { c ->
                    buildList { while (c.moveToNext()) add(with(store) { c.observation() }) }
                }
            rows
                .firstOrNull { plotValid(it, metric) }
                ?.let {
                    return it
                }
            if (rows.size < 16) return null
            offset += rows.size
        }
    }

    private fun atTime(id: String, metric: String, row: Observation?): List<Observation> {
        if (row == null) return emptyList()
        return store.select(
            "SELECT * FROM (${store.metricQuery(id, metric, elapsedOrder = true, idOrder = true)}) WHERE elapsed_us=? ORDER BY id",
            arrayOf(id, row.elapsedUs),
        ) { c ->
            buildList { while (c.moveToNext()) add(with(store) { c.observation() }) }
        }
    }

    fun inspect(
        id: String,
        metric: String,
        seconds: Double,
        anchor: MonitorAnchor?,
        allowTrailing: Boolean = false,
    ): Original? {
        val timeline = RideAnalysis(store).timeline(id)
        val query =
            InspectionQuery(
                metric,
                AnalysisRange(0, timeline.endUs),
                timeline,
                InspectionTarget(
                    elapsedUs(seconds),
                    anchor
                        ?.takeIf { it.metric == metric }
                        ?.let { store.metricObservation(id, metric, it.rowId) }
                        ?.let { InspectionAnchor(item(it).observationId, elapsedUs(seconds)) },
                ),
                allowTrailing = allowTrailing,
            )
        val rows =
            atTime(id, metric, plotNeighbor(id, metric, seconds, true, true)) +
                atTime(id, metric, plotNeighbor(id, metric, seconds, false, true)) +
                listOfNotNull(
                    anchor?.takeIf { it.metric == metric }?.let { store.metricObservation(id, metric, it.rowId) }
                )
        rows
            .distinctBy { it.id }
            .sortedWith(
                compareBy(
                    { it.elapsedUs },
                    { requireNotNull(it.original).producer },
                    { requireNotNull(it.original).sequence },
                )
            )
            .forEach { query.appendInspection(item(it)) }
        return query.result().selection
    }

    fun plot(
        id: String,
        metric: String,
        start: Double,
        end: Double,
        buckets: Int,
        pointLimit: Int = InspectionPlotSlots.maximumPoints,
    ): List<Payload> = charted {
        var slotUs = 16000000L
        while (slotUs * buckets < elapsedUs(end) - elapsedUs(start)) slotUs *= 2
        val aligned = slotUs.takeIf { elapsedUs(end) - elapsedUs(start) >= 16000000L * buckets }
        plotRange(id, metric, start, end, buckets, pointLimit, aligned)
    }

    fun wholePlot(
        id: String,
        columns: Collection<String>,
        end: Double,
        buckets: Int,
        pointLimit: Int,
    ): Map<String, List<Payload>> {
        val range = AnalysisRange(0, elapsedUs(end))
        val slotUs = InspectionPlotSlots.alignedSlotUs(range, buckets)
        val cutoffs = cutoffs(id)
        val recording =
            slotUs != null && !id.startsWith("live-") && store.metadata(id).str("phase") in listOf("running", "paused")
        if (recording) summaries.feed(id, columns, range.endUs, cutoffs)
        return columns.associateWith { column ->
            charted {
                if (recording) {
                    val reducer =
                        InspectionPlotReducer(plotMetric(column), range, buckets, pointLimit, alignedSlotUs = slotUs)
                    appendBefore(id, column, 0.0, reducer, cutoffs)
                    if (summaries.reduce(id, column, reducer, cutoffs)) {
                        appendAfter(id, column, end, reducer, cutoffs)
                        return@charted payloads(id, reducer.result())
                    }
                    summaries.fallbacks.incrementAndGet()
                }
                plotRange(id, column, 0.0, end, buckets, pointLimit, slotUs)
            }
        }
    }

    private inline fun charted(read: () -> List<Payload>): List<Payload> =
        try {
            read()
        } catch (error: InspectionCapacityException) {
            throw expo.modules.kotlin.exception.CodedException("ERR_CHART_CAPACITY", "Choose a smaller range.", error)
        }

    private fun plotMetric(metric: String) = if (metric.endsWith("DistanceMeters")) "distanceMeters" else metric

    private fun cutoffs(id: String) = RideAnalysis(store).timeline(id).interruptions.map { it.cutoffUs }.toSet()

    private fun appendBefore(
        id: String,
        metric: String,
        start: Double,
        reducer: InspectionPlotReducer,
        cutoffs: Set<Long>,
    ) {
        if (projectedMetric(metric))
            RideDistanceInspection(store)
                .boundaries(id, metric, elapsedUs(start), elapsedUs(start))
                .filter { it.point.timeUs < elapsedUs(start) }
                .forEach(reducer::appendDistance)
        else
            atTime(id, metric, plotNeighbor(id, metric, start, true)).forEach {
                reducer.append(item(it), interruptionBoundary = it.elapsedUs in cutoffs)
            }
    }

    private fun appendAfter(
        id: String,
        metric: String,
        end: Double,
        reducer: InspectionPlotReducer,
        cutoffs: Set<Long>,
    ) {
        if (projectedMetric(metric))
            RideDistanceInspection(store)
                .boundaries(id, metric, elapsedUs(end), elapsedUs(end))
                .filter { it.point.timeUs > elapsedUs(end) }
                .forEach(reducer::appendDistance)
        else
            atTime(id, metric, plotNeighbor(id, metric, end, false)).forEach {
                reducer.append(item(it), interruptionBoundary = it.elapsedUs in cutoffs)
            }
    }

    private fun payloads(id: String, points: List<InspectionPlotPoint>): List<Payload> {
        var segment: Int? = null
        val identities = hydrate(id, points.map { it.point.original })
        return points.map { point ->
            val starts = segment != point.segment
            segment = point.segment
            payload(id, point.point.original, identities) + mapOf("startsSegment" to starts)
        }
    }

    fun plotRange(
        id: String,
        metric: String,
        start: Double,
        end: Double,
        buckets: Int,
        pointLimit: Int,
        slotUs: Long?,
    ): List<Payload> {
        val revision = store.readRevision(id)
        val reducer =
            InspectionPlotReducer(
                plotMetric(metric),
                AnalysisRange(elapsedUs(start), elapsedUs(end)),
                buckets,
                pointLimit,
                alignedSlotUs = slotUs,
            )
        val cutoffs = cutoffs(id)
        appendBefore(id, metric, start, reducer, cutoffs)
        val low = floor(start / BLOCK).toLong()
        val high = floor(end / BLOCK).toLong()
        fun append(bucket: Long, payload: String?) {
            val range = AnalysisRange(bucket * 16000000, (bucket + 1) * 16000000)
            val cached =
                try {
                    payload?.let { node(id, metric, bucket, it, revision, plotOnly = true).plot }
                } catch (_: Exception) {
                    null
                }
            if (cached != null && reducer.canMerge(cached)) reducer.merge(cached)
            else if (projectedMetric(metric))
                RideDistanceInspection(store)
                    .boundaries(id, metric, range.startUs, range.endUs)
                    .filter { it.point.timeUs >= range.startUs && it.point.timeUs < range.endUs }
                    .forEach(reducer::appendDistance)
            else
                eachRawUs(
                    id,
                    metric,
                    maxOf(range.startUs, elapsedUs(start)),
                    minOf(range.endUs, elapsedUs(end) + 1),
                ) {
                    reducer.append(item(it), interruptionBoundary = it.elapsedUs in cutoffs)
                }
        }
        val span =
            if (!projectedMetric(metric) && slotUs != null && slotUs >= PLOT_BUCKETS * 16000000) PLOT_BUCKETS else 1L
        plotNodes(id, metric, low, high, revision, span) { bucket, payload ->
            if (span == 1L) append(bucket, payload)
            else {
                val cached = payload?.let { node(id, metric, bucket, it, revision, plotOnly = true, span = span).plot }
                if (cached != null && reducer.canMerge(cached)) reducer.merge(cached)
                else
                    plotNodes(
                        id,
                        metric,
                        maxOf(low, bucket * span),
                        minOf(high, (bucket + 1) * span - 1),
                        revision,
                        body = ::append,
                    )
            }
        }
        appendAfter(id, metric, end, reducer, cutoffs)
        return payloads(id, reducer.result())
    }

    fun rowId(id: String, original: Original): Long =
        original.observationId.toLongOrNull()
            ?: store.select("SELECT id FROM observations WHERE ride=? AND event_id=?", arrayOf(id, original.eventId)) {
                c ->
                check(c.moveToFirst())
                c.getLong(0)
            }

    private fun hydrate(id: String, originals: List<Original>): Map<String, Long> = buildMap {
        originals
            .filter { it.observationId.toLongOrNull() == null }
            .map { it.eventId }
            .distinct()
            .chunked(256)
            .forEach { events ->
                store.select(
                    "SELECT event_id,id FROM observations WHERE ride=? AND event_id IN (${events.joinToString(",") { "?" }})",
                    (listOf(id) + events).toTypedArray(),
                ) { c ->
                    while (c.moveToNext()) put(c.getString(0), c.getLong(1))
                }
            }
    }

    fun payload(id: String, original: Original, identities: Map<String, Long> = emptyMap()): Payload =
        mapOf(
            "observationId" to (identities[original.eventId] ?: rowId(id, original)).toString(),
            "elapsedSeconds" to original.elapsedSeconds,
            "timestamp" to original.timestamp,
            "value" to original.value.real(),
        )

    private fun eachRawUs(id: String, metric: String, startUs: Long, endUs: Long, body: (Observation) -> Unit) =
        eachRawQuery(id, store.metricQuery(id, metric, elapsedOrder = true), startUs, endUs, body)

    fun eachRawQuery(id: String, query: String, startUs: Long, endUs: Long, body: (Observation) -> Unit) {
        var after: Observation? = null
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val cursor = after
            val continuation = if (cursor == null) "" else "AND (elapsed_us,producer,producer_sequence,id)>(?,?,?,?)"
            val args =
                listOf(id, startUs, endUs) +
                    (cursor?.let {
                        listOf(it.elapsedUs, requireNotNull(it.original).producer, it.original.sequence, it.id)
                    } ?: emptyList())
            val rows =
                store.select(
                    "SELECT * FROM ($query) WHERE elapsed_us>=? AND elapsed_us<? $continuation ORDER BY elapsed_us,producer,producer_sequence,id LIMIT 16",
                    args.toTypedArray(),
                ) { c ->
                    buildList { while (c.moveToNext()) add(with(store) { c.observation() }) }
                }
            if (rows.isEmpty()) return
            rows.forEach(body)
            after = rows.last()
        }
    }

    private fun rawRows(id: String, query: String, start: Double, end: Double): List<Observation> = buildList {
        eachRawQuery(id, query, elapsedUs(start), elapsedUs(end), ::add)
    }

    private fun rawNeighbor(
        id: String,
        metric: String,
        timeUs: Long,
        before: Boolean,
    ): Observation? = neighbor(id, store.metricQuery(id, metric, elapsedOrder = true), timeUs, before)

    private fun neighbor(id: String, query: String, timeUs: Long, before: Boolean): Observation? {
        val order = if (before) "DESC" else "ASC"
        val op = if (before) "<" else ">"
        return store.select(
            "SELECT * FROM ($query) WHERE elapsed_us$op? ORDER BY elapsed_us $order,producer $order,producer_sequence $order,id $order LIMIT 1",
            arrayOf(id, timeUs),
        ) { c ->
            if (c.moveToFirst()) with(store) { c.observation() } else null
        }
    }

    private fun reduceBucket(
        metric: String,
        start: Double,
        rows: List<Observation>,
        timeline: ActiveTimeline,
        predecessor: Observation?,
    ): MetricCheckpoint? {
        val range = AnalysisRange(elapsedUs(start), elapsedUs(start + BLOCK))
        val query =
            InspectionQuery(
                metric,
                range,
                timeline,
                bucket = true,
                predecessor = predecessor?.let { item(it, true) },
            )
        rows.forEach { query.append(item(it, true)) }
        return query.checkpoint()
    }

    fun stats(id: String, metric: String, start: Double, end: Double): Payload {
        if (metric in consumptionMetrics) return RideConsumption(store).statistics(id, metric, start, end)
        if (metric.endsWith("DistanceMeters")) {
            val source = if (metric == "gpsDistanceMeters") "gps:phone" else "controller"
            val value = RideDistance(store).coreRange(id, source, start, end)
            return mapOf(
                "distance" to value.distanceMeters,
                "coveredSeconds" to value.coveredSeconds,
                "partial" to value.partial,
                "unresolvedBoundary" to value.unresolvedBoundary,
            )
        }
        val revision = store.readRevision(id)
        val range = AnalysisRange(elapsedUs(start), elapsedUs(end))
        val timeline = RideAnalysis(store).timeline(id)
        val reducer = InspectionQuery(metric, range, timeline)
        rawNeighbor(id, metric, range.startUs, true)?.let {
            reducer.append(item(it, true))
        }
        val low = Math.floorDiv(range.startUs, 16000000)
        val high = Math.floorDiv(range.endUs, 16000000)
        val nodes =
            store.select(
                "SELECT bucket,payload FROM (${checkpoints(id, revision)}) WHERE ride=? AND metric=? AND bucket>=? AND bucket<=? ORDER BY bucket",
                arrayOf(id, metric, low, high),
            ) { c ->
                buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
            }
        for (bucket in low..high) {
            val bounds = AnalysisRange(bucket * 16000000, (bucket + 1) * 16000000)
            val cached =
                if (
                    bounds.startUs > range.startUs &&
                        bounds.endUs <= range.endUs &&
                        metric !in listOf("heartRateBpm", "activeEnergyKcal")
                )
                    nodes[bucket]?.let { node(id, metric, bucket, it, revision).statistics }
                else null
            if (cached != null && reducer.canMerge(cached)) reducer.mergeOwned(cached)
            else
                eachRawUs(id, metric, maxOf(bounds.startUs, range.startUs), minOf(bounds.endUs, range.endUs + 1)) {
                    reducer.append(item(it, true))
                }
        }
        rawNeighbor(id, metric, range.endUs, false)?.let {
            reducer.append(item(it, true))
        }
        val stats =
            when (val outcome = reducer.result().statistics) {
                null -> return emptyMap()
                is MetricOutcome.Complete -> outcome.result.statistics
                is MetricOutcome.Overflow ->
                    throw expo.modules.kotlin.exception.CodedException(
                        "ERR_NUMERIC_OVERFLOW",
                        "${outcome.failure.metric}:${outcome.failure.quantity}",
                        null,
                    )
            }
        fun original(value: Original?): Payload? = value?.let { payload(id, it) }
        return mapOf(
                "count" to stats.count,
                "sampleMean" to stats.sampleMean,
                "integral" to stats.integral,
                "coveredSeconds" to stats.coveredSeconds,
                "min" to original(stats.min),
                "max" to original(stats.max),
            )
            .filterValues { it != null }
    }

    companion object {
        const val BLOCK = 16.0
        private const val PLOT_BUCKETS = 16L
    }
}
