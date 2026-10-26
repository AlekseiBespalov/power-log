package app.powerlog.bridge

import android.os.SystemClock
import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*
import expo.modules.kotlin.exception.CodedException
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

    fun committedLiveObservation(
        id: String,
        row: Long,
        acquiredAt: Double,
        metrics: Collection<String>,
    ) =
        synchronized(liveLock) {
            check(!store.readableDatabase.inTransaction()) {
                "Live evidence requires a committed observation."
            }
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
    ) {
        fun payload(): Payload =
            mapOf(
                "observationId" to id.toString(),
                "elapsedSeconds" to time,
                "timestamp" to stamp,
                "value" to value,
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

    private fun Observation.point(column: String) = Point(id, time, timestamp, values.getValue(column))

    private fun observation(id: String, column: String, row: Long): Point? =
        store.metricObservation(id, column, row)?.takeIf { store.analytics.plotValid(it, column) }?.point(column)

    private fun adjacent(id: String, column: String, time: Double, before: Boolean): Point? =
        store.analytics.plotNeighbor(id, column, time, before, true)?.point(column)

    private fun nearest(
        id: String,
        column: String,
        time: Double,
        metric: String,
        anchor: MonitorAnchor? = null,
    ): Point? {
        if (!time.isFinite()) return null
        if (metric == "distanceMeters" || projectedMetric(metric))
            return RideDistanceInspection(store).inspect(id, column, time, anchor)?.let {
                Point(it.observationId.toLong(), it.elapsedSeconds, it.timestamp, it.value.real())
            }
        val evidence = synchronized(liveLock) { if (liveRide == id) liveEvidence[column] else null }
        val tail = evidence?.let { observation(id, column, it.row) }
        val extent = tail?.let {
            HeldTail.render(
                metric,
                Original(it.id.toString(), it.id.toString(), it.stamp, it.time, Scalar.Real64(it.value)),
                it.id.toString(),
                evidence.acquiredAt,
                SystemClock.elapsedRealtime() / 1000.0,
                time,
            )
        }
        return store.analytics.inspect(id, column, time, anchor, extent != null)?.let {
            Point(store.analytics.rowId(id, it), it.elapsedSeconds, it.timestamp, it.value.real())
        }
    }

    fun query(id: String, request: MonitorInput): Payload {
        val kind = request.operation
        if (kind !in listOf(MonitorOperation.Describe, MonitorOperation.Changes))
            return store.withReadPin(id, request.distanceSource.wire) { readSnapshot(id, request) }
        repeat(3) {
            val result = store.withReadPin(id, request.distanceSource.wire) { readSnapshot(id, request) }
            if (result["status"] == "ok") return result
        }
        throw CodedException("ERR_READ_CONTENTION", "Recording changed during the read.", null)
    }

    private fun readSnapshot(id: String, request: MonitorInput): Payload {
        val kind = request.operation
        val revision = store.readRevision(id).toString()
        val source = request.distanceSource.wire
        val distanceInfo = distance.info(id, source)
        val metricSources = mutableMapOf("distanceMeters" to distanceInfo["selected"])
        (distanceInfo["available"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { profile ->
            val key = if (profile["source"] == "controller") "controllerDistanceMeters" else "gpsDistanceMeters"
            metricSources[key] = profile
        }
        for (metric in
            if (kind == MonitorOperation.EnergyPerDistance) emptySet()
            else consumptionMetrics.intersect(store.available(id).toSet())) {
            val total = RideConsumption(store).range(id, metric, 0.0, store.timing(id).elapsed)
            metricSources[metric] =
                mapOf(
                    "source" to if (metric.startsWith("controller")) "controller" else "calculated",
                    "label" to if (metric.startsWith("controller")) "Controller counters" else "Calculated",
                    "estimated" to metric.startsWith("calculated"),
                    "partial" to total.partial,
                    "coveredSeconds" to total.coveredUs / 1_000_000.0,
                )
        }
        val envelope =
            mapOf(
                "generation" to request.generation,
                "sourceId" to "${if (request.target == MonitorTarget.Live) "live" else "workout"}:$id",
                "revision" to revision,
                "metricSources" to metricSources.filterValues { it != null },
            )
        if (
            kind !in
                listOf(
                    MonitorOperation.Describe,
                    MonitorOperation.Latest,
                    MonitorOperation.Changes,
                ) && request.expectedRevision != revision
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
                val live = if (liveSource) evidence[it]?.let { live -> observation(id, column, live.row) } else null
                live ?: adjacent(id, it, Double.MAX_VALUE, true)
            }
        }
        val result: Payload =
            when (kind) {
                MonitorOperation.EnergyPerDistance ->
                    store.energy.read(
                        id,
                        request.startSeconds,
                        request.endSeconds,
                        request.buckets,
                        request.energySmoothingSeconds,
                        request.energyTotals,
                        request.energyCurrent,
                    )
                MonitorOperation.Describe -> {
                    val available = store.available(id).toMutableList()
                    if (distanceInfo["selected"] != null) available.add("distanceMeters")
                    val metadata = store.metadata(id)
                    val selectedDistance = distanceInfo["selected"] as? Map<*, *>
                    val outcome =
                        when {
                            metadata["phase"] == "finishing" -> "pending"
                            selectedDistance?.get("partial") == true -> "partial"
                            else -> "available"
                        }
                    mapOf(
                        "startedAt" to metadata["startedAt"],
                        "domain" to mapOf("start" to 0.0, "end" to max(10.0, elapsed)),
                        "availableMetrics" to available.sorted(),
                        "outcome" to outcome,
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
                    val count = metrics.toSet().size
                    val buckets = InspectionPlotSlots.buckets(request.buckets, count)
                    val pointLimit = InspectionPlotSlots.pointLimit(count)
                    val dataEnd = max(10.0, elapsed)
                    val columns = metrics.associateWith { column(id, it, source) }
                    val plotted = columns.values.filterNotNull().toSet()
                    val series =
                        if (start <= 0.0 && (request.endSeconds ?: dataEnd) >= dataEnd)
                            store.analytics.wholePlot(id, plotted, dataEnd, buckets, pointLimit)
                        else plotted.associateWith { store.analytics.plot(id, it, start, end, buckets, pointLimit) }
                    mapOf(
                        "series" to columns.mapValues { (_, column) -> column?.let(series::getValue) ?: emptyList() },
                        "resolution" to "reduced",
                    )
                }
                MonitorOperation.Stats -> {
                    val statistics = metrics.associateWith { m ->
                        val col = column(id, m, source)
                        if (col == null) emptyMap()
                        else {
                            if (m == "distanceMeters") {
                                val selected = distance.selected(id, source)
                                val total = selected?.let { distance.coreRange(id, it, start, end) }
                                mapOf(
                                        "distance" to total?.distanceMeters,
                                        "coveredSeconds" to (total?.coveredSeconds ?: 0.0),
                                        "partial" to (total?.partial ?: true),
                                        "unresolvedBoundary" to (total?.unresolvedBoundary ?: false),
                                    )
                                    .filterValues { it != null }
                            } else store.analytics.stats(id, col, start, end)
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

        val liveTiming =
            if (liveSource) {
                val clock =
                    liveOriginSeconds(id)?.let { origin ->
                        val monotonicAt = SystemClock.elapsedRealtime() / 1000.0
                        mapOf(
                            "nowSeconds" to max(elapsed, monotonicAt - origin),
                            "monotonicAt" to monotonicAt,
                        )
                    } ?: emptyMap()
                if (kind == MonitorOperation.Latest)
                    clock +
                        mapOf(
                            "liveAcquiredAt" to
                                latest.mapValues { (metric, point) ->
                                    evidence[latestColumns[metric]]?.takeIf { it.row == point?.id }?.acquiredAt
                                }
                        )
                else clock
            } else emptyMap()
        return envelope + result + liveTiming + mapOf("status" to "ok")
    }
}
