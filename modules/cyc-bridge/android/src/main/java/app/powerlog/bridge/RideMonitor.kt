package app.powerlog.bridge

import android.database.Cursor
import android.os.SystemClock
import kotlin.math.*

internal class RideMonitor(
    private val store: RideStore,
    private val distance: RideDistance,
    private val liveOriginSeconds: (String) -> Double? = { null },
) {
    private data class LiveEvidence(val row: Long, val acquiredAt: Double)

    private val liveLock = Any()
    private var liveRide: String? = null
    private var liveLastRow = 0L
    private val liveEvidence = mutableMapOf<String, LiveEvidence>()

    fun selectLiveRide(id: String) =
        synchronized(liveLock) {
            if (liveRide != id) {
                liveRide = id
                liveLastRow = 0L
                liveEvidence.clear()
            }
        }

    fun committedLiveObservation(id: String, row: Long, acquiredAt: Double, metrics: Collection<String>) =
        synchronized(liveLock) {
            check(!store.readableDatabase.inTransaction()) { "Live evidence requires a committed observation." }
            if (id != liveRide || row <= liveLastRow || !acquiredAt.isFinite() || acquiredAt < 0) return@synchronized
            metrics.forEach { metric ->
                val prior = liveEvidence[metric]
                if (prior == null || acquiredAt > prior.acquiredAt) liveEvidence[metric] = LiveEvidence(row, acquiredAt)
            }
            liveLastRow = row
        }

    private data class Point(
        val id: Long,
        val time: Double,
        val stamp: String,
        val value: Double,
        val segment: Int,
        val active: Boolean,
    ) {
        fun payload(starts: Boolean = false): Payload =
            mapOf(
                "observationId" to id.toString(),
                "elapsedSeconds" to time,
                "timestamp" to stamp,
                "value" to value,
                "startsSegment" to starts,
            )
    }

    private fun column(id: String, metric: String, source: String): String? =
        if (metric == "distanceMeters")
            when (distance.selected(id, source)) {
                "gps:phone" -> "gpsDistanceMeters"
                "controller" -> "controllerDistanceMeters"
                else -> null
            }
        else metric.takeIf { it in store.available(id) }

    private fun gap(metric: String) = if (metric in locationMetrics) 10.0 else RideAnalytics.DISPLAY_GAP_SECONDS

    private fun Cursor.point() = Point(getLong(0), getDouble(1), getString(2), getDouble(3), getInt(4), getInt(5) == 1)

    private fun observation(id: String, column: String, row: Long): Point? =
        store.readableDatabase
            .rawQuery(
                "SELECT id,time,timestamp,$column,segment,active FROM observations WHERE ride=? AND id=? AND $column IS NOT NULL",
                arrayOf(id, row.toString()),
            )
            .use { c -> if (c.moveToFirst()) c.point() else null }

    private fun adjacent(id: String, column: String, time: Double, before: Boolean): Point? {
        val order = if (before) "DESC" else "ASC"
        val op = if (before) "<=" else ">="
        return store.readableDatabase
            .rawQuery(
                "SELECT id,time,timestamp,$column,segment,active FROM observations WHERE ride=? AND time$op? AND $column IS NOT NULL ORDER BY time $order,id $order LIMIT 1",
                arrayOf(id, time.toString()),
            )
            .use { c -> if (c.moveToFirst()) c.point() else null }
    }

    private fun nearest(
        id: String,
        column: String,
        time: Double,
        metric: String,
        anchor: MonitorAnchor? = null,
    ): Point? {
        if (!time.isFinite()) return null
        if (anchor?.metric == metric) {
            store.readableDatabase
                .rawQuery(
                    "SELECT id,time,timestamp,$column,segment,active FROM observations WHERE ride=? AND id=? AND $column IS NOT NULL",
                    arrayOf(id, anchor.rowId.toString()),
                )
                .use { c -> if (c.moveToFirst()) return c.point() }
        }
        val left = adjacent(id, column, time, true)
        val right = adjacent(id, column, time, false)
        if (right?.time == time) return right
        if (left == null) return null
        if (right == null) {
            if (metric == "distanceMeters") return null
            val trailing = store.metadata(id).str("phase") in listOf("running", "paused")
            return left.takeIf { trailing && time - it.time < gap(metric) }
        }
        if (metric == "distanceMeters") {
            if (!distance.supportsInterval(id, column, left.id, right.id, right.time)) return null
        } else if (right.time - left.time >= gap(metric)) return null
        return if (time - left.time <= right.time - time) left else right
    }

    fun query(id: String, request: MonitorInput): Payload {
        val kind = request.operation
        if (kind !in listOf(MonitorOperation.Describe, MonitorOperation.Changes)) return readSnapshot(id, request)
        repeat(3) {
            val result = readSnapshot(id, request)
            if (result["status"] == "ok") return result
        }
        error("[monitor-contention] Recording changed during the read.")
    }

    private fun readSnapshot(id: String, request: MonitorInput): Payload {
        val kind = request.operation
        val revision = store.revision(id).toString()
        val source = request.distanceSource.wire
        val distanceInfo = distance.info(id, source)
        val metricSources = mapOf("distanceMeters" to distanceInfo["selected"])
        val envelope =
            mapOf(
                "generation" to request.generation,
                "sourceId" to id,
                "revision" to revision,
                "metricSources" to metricSources.filterValues { it != null },
            )
        if (
            kind !in listOf(MonitorOperation.Describe, MonitorOperation.Latest, MonitorOperation.Changes) &&
                request.expectedRevision != revision
        )
            return envelope + mapOf("status" to "retry")
        val metrics = request.metrics
        val (elapsed, _) = store.timing(id)
        val start = request.startSeconds
        val end = request.endSeconds ?: max(start, elapsed)
        val liveSource =
            kind in listOf(MonitorOperation.Describe, MonitorOperation.Latest) &&
                store.metadata(id).str("phase") in listOf("running", "paused")
        val evidence = synchronized(liveLock) { if (liveRide == id) liveEvidence.toMap() else emptyMap() }
        val latestColumns =
            if (kind == MonitorOperation.Latest) metrics.associateWith { column(id, it, source) } else emptyMap()
        val latest = latestColumns.mapValues { (_, column) ->
            column?.let {
                if (liveSource) evidence[it]?.let { live -> observation(id, column, live.row) }
                else adjacent(id, it, Double.MAX_VALUE, true)
            }
        }
        val result: Payload =
            when (kind) {
                MonitorOperation.Describe -> {
                    val available = store.available(id).filter { !it.endsWith("DistanceMeters") }.toMutableList()
                    if (distanceInfo["selected"] != null) available.add("distanceMeters")
                    mapOf(
                        "startedAt" to store.metadata(id)["startedAt"],
                        "domain" to mapOf("start" to 0, "end" to max(1.0, elapsed)),
                        "availableMetrics" to available,
                        "outcome" to if (available.isEmpty()) "unavailable" else "available",
                        "warnings" to emptyList<String>(),
                    )
                }
                MonitorOperation.Latest -> mapOf("points" to latest.mapValues { it.value?.payload() })
                MonitorOperation.Inspect -> {
                    val seconds = requireNotNull(request.seconds)
                    val points = metrics.associateWith { m ->
                        column(id, m, source)?.let {
                            nearest(id, it, seconds, m, request.anchor)?.payload()
                        }
                    }
                    mapOf(
                        "seconds" to seconds,
                        "points" to points,
                        "gaps" to points.mapValues { it.value == null },
                    )
                }
                MonitorOperation.Plot -> {
                    val buckets = request.buckets
                    mapOf(
                        "series" to
                            metrics.associateWith { m ->
                                column(id, m, source)?.let {
                                    store.analytics.plot(id, it, start, end, buckets)
                                } ?: emptyList<Payload>()
                            },
                        "resolution" to "reduced",
                    )
                }
                MonitorOperation.Stats -> {
                    val statistics = metrics.associateWith { m ->
                        val col = column(id, m, source)
                        if (col == null) emptyMap()
                        else {
                            val stats = store.analytics.stats(id, col, start, end).toMutableMap()
                            if (m == "distanceMeters") {
                                val selected = distance.selected(id, source)
                                val total = selected?.let { distance.range(id, it, start, end) }
                                stats["distance"] = total?.first
                                stats["coveredSeconds"] = total?.second ?: 0.0
                                stats["partial"] =
                                    distance.activeSeconds(id, start, end) - (total?.second ?: 0.0) > 0.001
                            }
                            stats
                        }
                    }
                    val result = mutableMapOf<String, Any?>("statistics" to statistics)
                    if (request.includeEndpoints)
                        result["endpoints"] =
                            mapOf(
                                "start" to
                                    metrics.associateWith { m ->
                                        column(id, m, source)?.let {
                                            nearest(
                                                    id,
                                                    it,
                                                    start,
                                                    m,
                                                    request.startAnchor,
                                                )
                                                ?.payload()
                                        }
                                    },
                                "end" to
                                    metrics.associateWith { m ->
                                        column(id, m, source)?.let {
                                            nearest(
                                                    id,
                                                    it,
                                                    end,
                                                    m,
                                                    request.endAnchor,
                                                )
                                                ?.payload()
                                        }
                                    },
                            )
                    result
                }
                MonitorOperation.Changes ->
                    mapOf(
                        "resetRequired" to false,
                        "changes" to
                            if (request.sinceRevision == revision) emptyList()
                            else
                                listOf(
                                    mapOf(
                                        "startSeconds" to 0,
                                        "endSeconds" to elapsed,
                                        "kind" to "append",
                                    )
                                ),
                    )
            }
        if (store.revision(id).toString() != revision) return envelope + mapOf("status" to "retry")
        val liveTiming =
            if (liveSource) {
                val clock =
                    liveOriginSeconds(id)?.let { origin ->
                        val monotonicAt = SystemClock.elapsedRealtime() / 1000.0
                        mapOf("nowSeconds" to max(elapsed, monotonicAt - origin), "monotonicAt" to monotonicAt)
                    } ?: emptyMap()
                if (kind == MonitorOperation.Latest)
                    clock +
                        mapOf(
                            "liveAcquiredAt" to
                                latest.mapValues { (metric, point) ->
                                    point?.let { evidence[latestColumns[metric]]?.acquiredAt }
                                }
                        )
                else clock
            } else emptyMap()
        return envelope + result + liveTiming + mapOf("status" to "ok")
    }
}
