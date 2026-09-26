package app.powerlog.bridge

import kotlin.math.*

/** One source for an entire distance interpretation; no hidden gap splicing between sensors. */
internal class RideDistance(private val store: RideStore) {
    private data class Prior(
        val id: Long,
        val time: Double,
        val values: Map<String, Double>,
        val segment: Int,
        val epoch: String,
        val identity: String,
    )

    private val previous = mutableMapOf<String, Prior>()

    fun reset() = previous.clear()

    fun append(
        ride: String,
        row: Long,
        time: Double,
        values: Map<String, Double>,
        active: Boolean,
        segment: Int,
        epoch: String,
        identity: String,
        gps: Boolean,
    ) {
        if (!store.writableDatabase.inTransaction()) {
            store.transaction {
                append(ride, row, time, values, active, segment, epoch, identity, gps)
            }
            return
        }
        val source = if (gps) "gps:phone" else "controller"
        val key = "$ride:$source"
        val valid =
            active &&
                if (gps)
                    values["latitude"]?.let { it in -90.0..90.0 } == true &&
                        values["longitude"]?.let { it in -180.0..180.0 } == true &&
                        values["horizontalAccuracyM"]?.let { it in 0.0..50.0 } == true
                else
                    values["controllerSpeedMps"]?.let { it in 0.0..40.0 } == true &&
                        identity.isNotEmpty() &&
                        epoch.isNotEmpty()
        if (!valid) {
            previous.remove(key)
            return
        }
        val old = previous.put(key, Prior(row, time, values, segment, epoch, identity)) ?: return
        val dt = time - old.time
        if (dt <= 0) {
            previous.remove(key)
            return
        }
        if (
            dt > (if (gps) 10.0 else 2.5) ||
                segment != old.segment ||
                epoch != old.epoch ||
                identity != old.identity
        )
            return
        val u = old.values["controllerSpeedMps"] ?: 0.0
        val v = values["controllerSpeedMps"] ?: 0.0
        var meters =
            if (gps)
                haversine(
                    old.values.getValue("latitude"),
                    old.values.getValue("longitude"),
                    values.getValue("latitude"),
                    values.getValue("longitude"),
                )
            else (u + v) * dt / 2
        if (meters / dt > 40) return
        if (
            gps &&
                old.values["speedMps"]?.let { it in 0.0..0.5 } == true &&
                values["speedMps"]?.let { it in 0.0..0.5 } == true
        )
            meters = 0.0
        val prior = total(ride, source)
        store.writableDatabase.execSQL(
            "INSERT INTO distance_intervals(ride,source,start,end,meters,cumulative,covered,from_id,to_id,u,v) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                ride,
                source,
                old.time,
                time,
                meters,
                prior.first + meters,
                prior.second + dt,
                old.id,
                row,
                if (gps) null else u,
                if (gps) null else v,
            ),
        )
        val column = if (gps) "gpsDistanceMeters" else "controllerDistanceMeters"
        store.analytics.changed(ride, time, listOf(column))
        store.writableDatabase.execSQL(
            "UPDATE observations SET $column=? WHERE id=?",
            arrayOf(prior.first + meters, row),
        )
        store.writableDatabase.execSQL(
            "UPDATE rides SET metrics=metrics|? WHERE id=?",
            arrayOf(1L shl storedMetrics.indexOf(column), ride),
        )
    }

    fun total(id: String, source: String): Pair<Double, Double> =
        store.readableDatabase
            .rawQuery(
                "SELECT cumulative,covered FROM distance_intervals WHERE ride=? AND source=? ORDER BY end DESC LIMIT 1",
                arrayOf(id, source),
            )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else 0.0 to 0.0 }

    fun info(id: String, selection: String): Payload {
        require(
            selection in
                listOf(
                    "auto",
                    "gps:phone",
                    "gps:watch",
                    "health:phone",
                    "health:watch",
                    "controller",
                )
        )
        val timer = store.timing(id).second
        val available =
            listOf("gps:phone", "controller").mapNotNull { source ->
                val (meters, covered) = total(id, source)
                if (covered <= 0) null
                else
                    mapOf(
                        "source" to source,
                        "label" to
                            if (source == "controller") "Controller estimate" else "GPS · Phone",
                        "estimated" to (source == "controller"),
                        "distanceMeters" to meters,
                        "coveredSeconds" to covered,
                        "uncoveredSeconds" to max(0.0, timer - covered),
                        "partial" to (timer - covered > 2.5),
                        "policyVersion" to 1,
                    )
            }
        val selected =
            if (selection == "auto") available.firstOrNull()
            else available.find { it["source"] == selection }
        return mapOf("selection" to selection, "selected" to selected, "available" to available)
    }

    fun selected(id: String, selection: String) =
        (info(id, selection)["selected"] as? Map<*, *>)?.get("source") as? String

    fun range(id: String, source: String, start: Double, end: Double): Pair<Double, Double> {
        fun cumulative(time: Double): Pair<Double, Double> {
            val base =
                store.readableDatabase
                    .rawQuery(
                        "SELECT cumulative,covered FROM distance_intervals WHERE ride=? AND source=? AND end<=? ORDER BY end DESC LIMIT 1",
                        arrayOf(id, source, time.toString()),
                    )
                    .use { c ->
                        if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else 0.0 to 0.0
                    }
            return store.readableDatabase
                .rawQuery(
                    "SELECT start,end,meters,u,v FROM distance_intervals WHERE ride=? AND source=? AND end>? ORDER BY end LIMIT 1",
                    arrayOf(id, source, time.toString()),
                )
                .use { c ->
                    if (!c.moveToFirst() || c.getDouble(0) >= time) base
                    else {
                        val a = c.getDouble(0)
                        val b = c.getDouble(1)
                        val dt = time - a
                        val meters =
                            if (c.isNull(3)) c.getDouble(2) * dt / (b - a)
                            else
                                (c.getDouble(3) +
                                    (c.getDouble(4) - c.getDouble(3)) * dt / (2 * (b - a))) * dt
                        base.first + meters to base.second + dt
                    }
                }
        }
        val a = cumulative(start)
        val b = cumulative(end)
        return b.first - a.first to b.second - a.second
    }

    companion object {
        fun haversine(a: Double, b: Double, c: Double, d: Double): Double {
            val p1 = Math.toRadians(a)
            val p2 = Math.toRadians(c)
            val h =
                (sin((p2 - p1) / 2).pow(2) +
                        cos(p1) * cos(p2) * sin(Math.toRadians(d - b) / 2).pow(2))
                    .coerceIn(0.0, 1.0)
            return 6371008.8 * 2 * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
