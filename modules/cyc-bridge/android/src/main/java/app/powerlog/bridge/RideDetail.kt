package app.powerlog.bridge

import kotlin.math.*

internal class RideDetail(
    private val store: RideStore,
    private val distance: RideDistance,
    private val monitor: RideMonitor,
) {
    @Suppress("UNCHECKED_CAST")
    fun detail(id: String, source: String): Payload {
        val metadata = store.metadata(id)
        val (elapsed, timer, timestamp) = store.timing(id)
        val query =
            monitor.query(
                id,
                MonitorInput(
                    operation = MonitorOperation.Stats,
                    target = MonitorTarget.Workout(id),
                    generation = 0,
                    expectedRevision = store.revision(id).toString(),
                    distanceSource = BridgeInputs.distanceSource(source),
                    metrics = listOf("humanPowerW", "cadenceRpm", "speedMps"),
                    startSeconds = 0.0,
                    endSeconds = elapsed,
                ),
            )
        check(query["status"] == "ok") { "Ride changed. Try again." }
        val stats = query["statistics"] as Map<String, Payload>
        val distanceInfo = distance.info(id, source)
        val selected = distanceInfo["selected"] as? Payload
        val route = mutableListOf<Payload>()
        var locations = 0L
        val stride = max(1, (store.count(id, "location") / 500).toInt())
        store.readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND kind='location' ORDER BY time,id",
                arrayOf(id),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val row = with(store) { c.observation() }
                    if (
                        row.active &&
                            (row.values["horizontalAccuracyM"] ?: 999.0) <= 50 &&
                            locations++ % stride == 0L &&
                            route.size < 501
                    )
                        route.add(
                            mapOf(
                                "latitude" to row.values["latitude"],
                                "longitude" to row.values["longitude"],
                                "segment" to row.segment,
                            )
                        )
                }
            }
        fun mean(metric: String): Double? {
            val s = stats[metric] ?: return null
            return if (s.num("coveredSeconds") > 0) s.num("integral") / s.num("coveredSeconds") else null
        }
        fun maximum(metric: String) = (stats[metric]?.get("max") as? Map<*, *>)?.get("value")
        val power = stats["humanPowerW"] ?: emptyMap()
        val summary =
            mapOf(
                "schemaVersion" to 1,
                "id" to id,
                "startedAt" to metadata["startedAt"],
                "endedAt" to (metadata["endedAt"] ?: timestamp),
                "elapsedSeconds" to elapsed,
                "timerSeconds" to timer,
                "distance" to distanceInfo,
                "distanceMeters" to selected?.get("distanceMeters"),
                "averageSpeedMps" to
                    selected?.let {
                        if (it.num("coveredSeconds") > 0) it.num("distanceMeters") / it.num("coveredSeconds") else null
                    },
                "maximumSpeedMps" to maximum("speedMps"),
                "averageRiderPowerW" to mean("humanPowerW"),
                "maximumRiderPowerW" to maximum("humanPowerW"),
                "averageCadenceRpm" to mean("cadenceRpm"),
                "maximumCadenceRpm" to maximum("cadenceRpm"),
                "riderWorkJoules" to power["integral"],
                "telemetryCoveredSeconds" to power.num("coveredSeconds"),
                "heartRateCoveredSeconds" to 0,
                "eventCount" to store.count(id),
                "telemetryCount" to store.count(id, "telemetry"),
                "locationCount" to store.count(id, "location"),
                "healthCount" to 0,
                "lapCount" to store.events(id).count { it.action == "lap" } + 1,
                "routePreview" to route,
                "provenance" to
                    mapOf(
                        "riderPower" to "CYC rider power",
                        "distance" to (selected?.str("source") ?: "unavailable"),
                        "gps" to "phone",
                    ),
            )
        return mapOf("metadata" to metadata, "summary" to summary.filterValues { it != null })
    }
}
