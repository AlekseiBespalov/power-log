package app.powerlog.bridge

import kotlin.math.*
import org.json.JSONObject

/** Fixed-duration checkpoints keep wide chart and statistics reads off the original stream. */
internal class RideAnalytics(private val store: RideStore) {
    private val dirty = mutableMapOf<Pair<String, Long>, MutableSet<String>>()

    fun changed(id: String, time: Double, metrics: Collection<String>) {
        val bucket = floor(time / BLOCK).toLong()
        dirty.getOrPut(id to bucket) { mutableSetOf() }.addAll(metrics)
        dirty.getOrPut(id to bucket + 1) { mutableSetOf() }.addAll(metrics)
    }

    fun clear() = dirty.clear()

    fun flush() {
        dirty.forEach { (key, metrics) ->
            val (id, bucket) = key
            val start = bucket * BLOCK
            val rows =
                store.readableDatabase
                    .rawQuery(
                        "SELECT * FROM observations WHERE ride=? AND time>=? AND time<? ORDER BY time,id",
                        arrayOf(id, start.toString(), (start + BLOCK).toString()),
                    )
                    .use { c ->
                        buildList { while (c.moveToNext()) add(with(store) { c.observation() }) }
                    }
            metrics.forEach { metric ->
                val points = rows.filter { it.values[metric] != null }
                if (points.isEmpty()) return@forEach
                val prior = before(id, metric, points.first().time)
                val stats =
                    aggregate(
                        points,
                        metric,
                        prior,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                    )
                val selected =
                    listOf(
                            points.first(),
                            points.minBy { it.values.getValue(metric) },
                            points.maxBy { it.values.getValue(metric) },
                            points.last(),
                        )
                        .distinctBy { it.id }
                        .sortedWith(compareBy({ it.time }, { it.id }))
                val broken = points.zipWithNext().any { (a, b) -> !continuous(a, b, metric, true) }
                val payload =
                    stats +
                        mapOf(
                            "points" to
                                selected.map {
                                    point(it, metric) +
                                        mapOf(
                                            "startsSegment" to
                                                (it.id == points.first().id &&
                                                    (prior == null ||
                                                        !continuous(prior, it, metric, true)))
                                        )
                                },
                            "broken" to broken,
                            "previousTime" to prior?.time,
                        )
                store.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO analytics(ride,metric,bucket,first,last,payload) VALUES(?,?,?,?,?,?)",
                    arrayOf(
                        id,
                        metric,
                        bucket,
                        points.first().time,
                        points.last().time,
                        json(payload).toString(),
                    ),
                )
            }
        }
        dirty.clear()
    }

    private fun before(id: String, metric: String, time: Double): Observation? =
        store.readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND time<? AND $metric IS NOT NULL ORDER BY time DESC,id DESC LIMIT 1",
                arrayOf(id, time.toString()),
            )
            .use { c -> if (c.moveToFirst()) with(store) { c.observation() } else null }

    private fun rows(id: String, metric: String, start: Double, end: Double): List<Observation> =
        store.readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND time>=? AND time<? AND $metric IS NOT NULL ORDER BY time,id",
                arrayOf(id, start.toString(), end.toString()),
            )
            .use { c -> buildList { while (c.moveToNext()) add(with(store) { c.observation() }) } }

    @Suppress("UNCHECKED_CAST")
    fun plot(id: String, metric: String, start: Double, end: Double, buckets: Int): List<Payload> {
        val candidates = mutableListOf<Payload>()
        val low = floor(start / BLOCK).toLong()
        val high = floor(end / BLOCK).toLong()
        store.readableDatabase
            .rawQuery(
                "SELECT bucket,payload FROM analytics WHERE ride=? AND metric=? AND bucket>=? AND bucket<=? ORDER BY bucket",
                arrayOf(id, metric, low.toString(), high.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getLong(0)
                    val data = JSONObject(c.getString(1)).map()
                    if (
                        data.flag("broken") ||
                            bucket == low ||
                            bucket == high ||
                            BLOCK > (end - start) / buckets
                    ) {
                        val raw = rows(id, metric, bucket * BLOCK, (bucket + 1) * BLOCK)
                        var prior = raw.firstOrNull()?.let { before(id, metric, it.time) }
                        for (p in raw) {
                            val old = prior
                            if (p.time in start..end)
                                candidates.add(
                                    point(p, metric) +
                                        mapOf(
                                            "startsSegment" to
                                                (old == null || !continuous(old, p, metric, true))
                                        )
                                )
                            prior = p
                        }
                    } else candidates.addAll(data["points"] as List<Payload>)
                }
            }
        before(id, metric, start)?.let { candidates.add(0, point(it, metric)) }
        store.readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND time>? AND $metric IS NOT NULL ORDER BY time,id LIMIT 1",
                arrayOf(id, end.toString()),
            )
            .use { c ->
                if (c.moveToFirst()) {
                    val next = with(store) { c.observation() }
                    val previous = before(id, metric, next.time)
                    candidates.add(
                        point(next, metric) +
                            mapOf(
                                "startsSegment" to
                                    (previous == null || !continuous(previous, next, metric, true))
                            )
                    )
                }
            }
        val ordered =
            candidates.distinctBy { it["observationId"] }.sortedBy { it.num("elapsedSeconds") }
        var run = 0
        var previous: Payload? = null
        val marked = ordered.map { p ->
            val old = previous
            if (
                old != null &&
                    (old["segment"] != p["segment"] ||
                        old["epoch"] != p["epoch"] ||
                        p.flag("startsSegment"))
            )
                run++
            previous = p
            p + mapOf("run" to run)
        }
        val selected =
            marked
                .groupBy {
                    floor((it.num("elapsedSeconds") - start) / max(0.001, end - start) * buckets)
                        .toInt()
                        .coerceIn(-1, buckets)
                }
                .toSortedMap()
                .values
                .flatMap { group ->
                    listOf(
                            group.first(),
                            group.minBy { it.num("value") },
                            group.maxBy { it.num("value") },
                            group.last(),
                        )
                        .distinctBy { it["observationId"] }
                        .sortedBy { it.num("elapsedSeconds") }
                }
        return selected.mapIndexed { i, p ->
            p.filterKeys { it !in setOf("segment", "epoch", "run") } +
                mapOf("startsSegment" to (i == 0 || selected[i - 1]["run"] != p["run"]))
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun stats(id: String, metric: String, start: Double, end: Double): Payload {
        var count = 0.0
        var sum = 0.0
        var integral = 0.0
        var covered = 0.0
        var low: Payload? = null
        var high: Payload? = null
        // Include the next observation's block for a clipped interval at the right edge.
        store.readableDatabase
            .rawQuery(
                "SELECT bucket,first,last,payload FROM analytics WHERE ride=? AND metric=? AND bucket>=? AND bucket<=? ORDER BY bucket",
                arrayOf(
                    id,
                    metric,
                    floor(start / BLOCK).toLong().toString(),
                    floor((end + 10) / BLOCK).toLong().toString(),
                ),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getLong(0)
                    val block = JSONObject(c.getString(3)).map()
                    val first = c.getDouble(1)
                    val last = c.getDouble(2)
                    val prior = block["previousTime"] as? Number
                    val data =
                        if (
                            first >= start &&
                                last <= end &&
                                (prior == null || prior.toDouble() >= start)
                        )
                            block
                        else {
                            val points = rows(id, metric, bucket * BLOCK, (bucket + 1) * BLOCK)
                            aggregate(
                                points,
                                metric,
                                points.firstOrNull()?.let { before(id, metric, it.time) },
                                start,
                                end,
                            )
                        }
                    count += data.num("count")
                    sum += data.num("sum")
                    integral += data.num("integral")
                    covered += data.num("coveredSeconds")
                    val min = data["min"] as? Payload
                    val max = data["max"] as? Payload
                    if (min != null && (low == null || min.num("value") < low!!.num("value")))
                        low = min
                    if (max != null && (high == null || max.num("value") > high!!.num("value")))
                        high = max
                }
            }
        return mapOf(
                "count" to count.toLong(),
                "sampleMean" to if (count > 0) sum / count else null,
                "integral" to integral,
                "coveredSeconds" to covered,
                "min" to low,
                "max" to high,
            )
            .filterValues { it != null }
    }

    private fun aggregate(
        rows: List<Observation>,
        metric: String,
        before: Observation?,
        start: Double,
        end: Double,
    ): Payload {
        var previous = before
        var count = 0
        var sum = 0.0
        var covered = 0.0
        var integral = 0.0
        var low: Observation? = null
        var high: Observation? = null
        for (row in rows) {
            val value = row.values.getValue(metric)
            if (row.time in start..end) {
                count++
                sum += value
                if (low == null || value < low.values.getValue(metric)) low = row
                if (high == null || value > high.values.getValue(metric)) high = row
            }
            val old = previous
            if (old != null && old.active && row.active && continuous(old, row, metric, false)) {
                val a = max(start, old.time)
                val b = min(end, row.time)
                val dt = row.time - old.time
                if (b > a) {
                    val u = old.values.getValue(metric)
                    val v = value
                    integral += (u + (v - u) * ((a + b) / 2 - old.time) / dt) * (b - a)
                    covered += b - a
                }
            }
            previous = row
        }
        return mapOf(
            "count" to count,
            "sum" to sum,
            "integral" to integral,
            "coveredSeconds" to covered,
            "min" to low?.let { point(it, metric) },
            "max" to high?.let { point(it, metric) },
        )
    }

    private fun continuous(a: Observation, b: Observation, metric: String, plot: Boolean) =
        a.segment == b.segment &&
            a.epoch == b.epoch &&
            b.time > a.time &&
            (if (plot) b.time - a.time < plotGap(metric)
            else b.time - a.time <= if (metric in locationMetrics) 10.0 else 2.5)

    private fun point(p: Observation, metric: String): Payload =
        mapOf(
            "observationId" to p.id.toString(),
            "elapsedSeconds" to p.time,
            "timestamp" to p.timestamp,
            "value" to p.values.getValue(metric),
            "segment" to p.segment,
            "epoch" to p.epoch,
        )

    private fun plotGap(metric: String) = if (metric in locationMetrics) 10.0 else 6.0

    companion object {
        const val BLOCK = 16.0
    }
}
