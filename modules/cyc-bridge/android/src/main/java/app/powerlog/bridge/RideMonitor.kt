package app.powerlog.bridge

import kotlin.math.*

internal class RideMonitor(private val store: RideStore, private val distance: RideDistance) {
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

    private fun gap(metric: String) = if (metric in locationMetrics) 10.0 else 2.5

    private fun adjacent(id: String, column: String, time: Double, before: Boolean): Point? {
        val order = if (before) "DESC" else "ASC"
        val op = if (before) "<=" else ">="
        return store.readableDatabase
            .rawQuery(
                "SELECT id,time,timestamp,$column,segment,active FROM observations WHERE ride=? AND time$op? AND $column IS NOT NULL ORDER BY time $order,id $order LIMIT 1",
                arrayOf(id, time.toString()),
            )
            .use { c ->
                if (c.moveToFirst())
                    Point(
                        c.getLong(0),
                        c.getDouble(1),
                        c.getString(2),
                        c.getDouble(3),
                        c.getInt(4),
                        c.getInt(5) == 1,
                    )
                else null
            }
    }

    private fun nearest(
        id: String,
        column: String,
        time: Double,
        metric: String,
        anchor: Payload? = null,
    ): Point? {
        if (anchor?.str("metric") == metric) {
            val number = anchor.str("observationId").toLongOrNull()
            if (number != null)
                store.readableDatabase
                    .rawQuery(
                        "SELECT time FROM observations WHERE ride=? AND id=? AND $column IS NOT NULL",
                        arrayOf(id, number.toString()),
                    )
                    .use { c ->
                        if (c.moveToFirst())
                            return adjacent(id, column, c.getDouble(0), true)?.takeIf {
                                it.id == number
                            }
                    }
        }
        val left = adjacent(id, column, time, true)
        val right = adjacent(id, column, time, false)
        return listOfNotNull(left, right)
            .minByOrNull { abs(it.time - time) }
            ?.takeIf {
                abs(it.time - time) <= if (metric == "distanceMeters") 120.0 else gap(metric)
            }
    }

    fun query(kind: String, id: String, request: Payload): Payload {
        if (kind !in listOf("describe", "changes")) return readSnapshot(kind, id, request)
        repeat(3) {
            val result = readSnapshot(kind, id, request)
            if (result["status"] == "ok") return result
        }
        error("[monitor-contention] Recording changed during the read.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSnapshot(kind: String, id: String, request: Payload): Payload {
        val revision = store.revision(id).toString()
        val source = request.str("distanceSource", "auto")
        val distanceInfo = distance.info(id, source)
        val metricSources = mapOf("distanceMeters" to distanceInfo["selected"])
        val envelope =
            mapOf(
                "generation" to request.num("generation").toInt(),
                "sourceId" to id,
                "revision" to revision,
                "metricSources" to metricSources.filterValues { it != null },
            )
        if (
            kind !in listOf("describe", "latest", "changes") &&
                request.str("expectedRevision") != revision
        )
            return envelope + mapOf("status" to "retry")
        val metrics =
            (request["metrics"] as? List<*>)?.filterIsInstance<String>()?.distinct()?.take(32)
                ?: emptyList()
        val (elapsed, _) = store.timing(id)
        val start = request.num("startSeconds").coerceAtLeast(0.0)
        val end = request.num("endSeconds", elapsed).coerceAtLeast(start)
        val result: Payload =
            when (kind) {
                "describe" -> {
                    val available =
                        store
                            .available(id)
                            .filter { !it.endsWith("DistanceMeters") }
                            .toMutableList()
                    if (distanceInfo["selected"] != null) available.add("distanceMeters")
                    mapOf(
                        "startedAt" to store.metadata(id)["startedAt"],
                        "domain" to mapOf("start" to 0, "end" to max(1.0, elapsed)),
                        "nowSeconds" to elapsed,
                        "availableMetrics" to available,
                        "outcome" to if (available.isEmpty()) "unavailable" else "available",
                        "warnings" to emptyList<String>(),
                    )
                }
                "latest" ->
                    mapOf(
                        "points" to
                            metrics.associateWith { m ->
                                column(id, m, source)?.let {
                                    adjacent(id, it, Double.MAX_VALUE, true)?.payload()
                                }
                            }
                    )
                "inspect" -> {
                    val seconds = request.num("seconds")
                    val points = metrics.associateWith { m ->
                        column(id, m, source)?.let {
                            nearest(id, it, seconds, m, request["anchor"] as? Payload)?.payload()
                        }
                    }
                    mapOf(
                        "seconds" to seconds,
                        "points" to points,
                        "gaps" to points.mapValues { it.value == null },
                    )
                }
                "plot" -> {
                    val buckets = request.num("buckets", 128.0).toInt().coerceIn(1, 512)
                    mapOf(
                        "series" to
                            metrics.associateWith { m ->
                                column(id, m, source)?.let {
                                    store.analytics.plot(id, it, start, end, buckets)
                                } ?: emptyList<Payload>()
                            },
                        "latest" to
                            metrics.associateWith { m ->
                                column(id, m, source)?.let {
                                    adjacent(id, it, Double.MAX_VALUE, true)?.payload()
                                }
                            },
                        "resolution" to "reduced",
                    )
                }
                "stats" -> {
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
                                stats["partial"] = (total?.second ?: 0.0) < end - start - 2.5
                            }
                            stats
                        }
                    }
                    val result = mutableMapOf<String, Any?>("statistics" to statistics)
                    if (request.flag("includeEndpoints"))
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
                                                    request["startAnchor"] as? Payload,
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
                                                    request["endAnchor"] as? Payload,
                                                )
                                                ?.payload()
                                        }
                                    },
                            )
                    result
                }
                "changes" ->
                    mapOf(
                        "resetRequired" to false,
                        "changes" to
                            if (request.str("sinceRevision") == revision) emptyList()
                            else
                                listOf(
                                    mapOf(
                                        "startSeconds" to 0,
                                        "endSeconds" to elapsed,
                                        "kind" to "append",
                                    )
                                ),
                    )
                else -> error("Unknown monitor query")
            }
        if (store.revision(id).toString() != revision) return envelope + mapOf("status" to "retry")
        return envelope + result + mapOf("status" to "ok")
    }
}
