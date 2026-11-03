package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*

internal class RideDistanceInspection(private val store: RideStore) {
    fun boundaries(id: String, metric: String, startUs: Long, endUs: Long): List<DistanceInspectionPoint> {
        if (metric in consumptionMetrics) return RideConsumption(store).boundaries(id, metric, startUs, endUs)
        val source = if (metric == "gpsDistanceMeters") "gps:phone" else "controller"
        val revision = store.readRevision(id)
        val maximumIntervalUs =
            if (metric == "gpsDistanceMeters") DistancePolicy.maximumGPSIntervalUs
            else DistancePolicy.maximumControllerEdgeUs
        val upper = if (endUs > Long.MAX_VALUE - maximumIntervalUs) Long.MAX_VALUE else endUs + maximumIntervalUs
        return store.select(
            "SELECT d.*,a.event_id AS start_event,a.timestamp AS start_stamp,a.time AS start_time,b.event_id AS end_event,b.timestamp AS end_stamp,b.time AS end_time FROM distance_intervals d JOIN observations a ON a.id=d.from_id JOIN observations b ON b.id=d.to_id WHERE d.ride=? AND d.source=? AND d.end_us>=? AND d.end_us<=? AND d.start_us<=? AND d.revision<=? ORDER BY d.end_us,d.to_id",
            arrayOf(id, source, startUs, upper, endUs, revision),
        ) { c ->
            buildList {
                    while (c.moveToNext()) {
                        val start = c.long("start_us")
                        val end = c.long("end_us")
                        for (edge in listOf("start", "end")) {
                            val row = c.long(if (edge == "start") "from_id" else "to_id")
                            val cumulative = c.long("cumulative_q") - if (edge == "start") c.long("meters_q") else 0L
                            add(
                                DistanceInspectionPoint(
                                    InspectionPoint(
                                        if (edge == "start") start else end,
                                        Original(
                                            row.toString(),
                                            c.text("${edge}_event"),
                                            c.text("${edge}_stamp"),
                                            c.double("${edge}_time"),
                                            Scalar.Real64(FixedPoint.divide(cumulative, 1000000)),
                                        ),
                                        Continuity(),
                                    ),
                                    c.long("segment").toInt(),
                                    start,
                                    end,
                                    row,
                                    c.long("plot_segment").toInt(),
                                )
                            )
                        }
                    }
                }
                .sortedWith(compareBy({ it.point.timeUs }, { it.tieKey }))
        }
    }

    fun inspect(id: String, metric: String, seconds: Double, anchor: MonitorAnchor?): Original? {
        val target =
            InspectionTarget(
                elapsedUs(seconds),
                anchor
                    ?.takeIf { it.metric in listOf("distanceMeters", metric) }
                    ?.let { InspectionAnchor(it.rowId.toString(), elapsedUs(seconds)) },
            )
        val points = boundaries(id, metric, target.timeUs, target.timeUs)
        val before =
            points
                .filter { it.point.timeUs <= target.timeUs }
                .maxWithOrNull(compareBy({ it.point.timeUs }, { it.tieKey }))
        val after =
            points
                .filter { it.point.timeUs >= target.timeUs }
                .minWithOrNull(compareBy({ it.point.timeUs }, { it.tieKey }))
        val anchored = points.firstOrNull { InspectionOrder.anchorMatches(target, it.point) }
        return DistanceInspection.select(target, before, after, anchored)
    }
}
