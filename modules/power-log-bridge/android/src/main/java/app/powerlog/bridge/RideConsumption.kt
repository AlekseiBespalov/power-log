package app.powerlog.bridge

import android.database.Cursor
import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*

internal val consumptionMetrics = BatteryConsumptionPolicy.metrics.toSet()

internal fun projectedMetric(metric: String) = InspectionMetrics.isProjected(metric)

/** Derived intervals commit beside originals; checkpoints bound capture cost independently of ride length. */
internal class RideConsumption(private val store: RideStore) {
    private fun visible(revision: Long) =
        "revision<=$revision AND (retired_revision IS NULL OR retired_revision>$revision)"

    fun append(ride: String, row: Long) {
        if (ride.startsWith("live-")) return
        val observation =
            store.select("SELECT * FROM observations WHERE id=?", arrayOf(row)) { c ->
                check(c.moveToFirst())
                with(store) { c.observation() }
            }
        append(ride, observation, store.consumptionTimeline(ride))
    }

    private fun append(ride: String, observation: Observation, timeline: ActiveTimeline) {
        check(store.writableDatabase.inTransaction())
        val row = observation.id
        val state =
            store.select("SELECT row_id,payload FROM battery_state WHERE ride=?", arrayOf(ride)) { c ->
                if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
            }
        if (state != null && row <= state.first) return
        val kernel = BatteryConsumptionKernel(state?.second)
        // A block stores the state before its first original, so a boundary at that exact time is replayable.
        store.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO battery_checkpoints VALUES(?,?,?,?)",
            arrayOf(ride, observation.elapsedUs / 16000000, state?.first ?: 0L, kernel.checkpoint()),
        )
        val values = observation.values
        val intervals =
            kernel.append(
                observation.elapsedUs,
                timeline.membership(observation.analysisItem()).takeIf { observation.active },
                "${observation.identity}|${observation.epoch}|${observation.segment}",
                values["consumedAh"],
                values["consumedWh"],
                values["batteryCurrentA"],
                values["batteryVoltageV"],
            )
        for (interval in intervals) {
            val covered =
                store.select(
                    "SELECT covered_us FROM battery_intervals WHERE ride=? AND metric=? AND retired_revision IS NULL ORDER BY end_us DESC,to_id DESC LIMIT 1",
                    arrayOf(ride, interval.metric),
                ) { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            val from =
                if (interval.baseline) row
                else
                    store.select(
                        "SELECT id FROM observations WHERE ride=? AND kind='telemetry' AND elapsed_us=? AND id<? ORDER BY id DESC LIMIT 1",
                        arrayOf(ride, interval.startUs, row),
                    ) { c ->
                        check(c.moveToFirst())
                        c.getLong(0)
                    }
            store.writableDatabase.execSQL(
                "INSERT INTO battery_intervals VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                arrayOf(
                    ride,
                    interval.metric,
                    interval.startUs,
                    interval.endUs,
                    interval.startValue,
                    interval.endValue,
                    interval.rate0,
                    interval.rate1,
                    if (interval.indivisible) 1 else 0,
                    interval.segment,
                    interval.activeInterval,
                    if (interval.baseline) 1 else 0,
                    from,
                    row,
                    covered + interval.endUs - interval.startUs,
                    store.revision(ride),
                ),
            )
            store.analytics.intervalChanged(ride, interval.startUs, interval.endUs, listOf(interval.metric))
        }
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO battery_state VALUES(?,?,?)",
            arrayOf(ride, row, kernel.checkpoint()),
        )
        if (intervals.isNotEmpty())
            store.writableDatabase.execSQL(
                "UPDATE rides SET metrics=metrics|? WHERE id=?",
                arrayOf(availabilityBits(intervals.map { it.metric }), ride),
            )
    }

    /** Lifecycle can revise membership of an already committed sample at its boundary. */
    fun lifecycle(ride: String, timeUs: Long) {
        if (ride.startsWith("live-")) return
        check(store.writableDatabase.inTransaction())
        val checkpoint =
            store.select(
                "SELECT bucket,row_id,payload FROM battery_checkpoints WHERE ride=? AND bucket>=? ORDER BY bucket LIMIT 1",
                arrayOf(ride, timeUs / 16000000),
            ) { c ->
                if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null
            } ?: return
        val revision = store.revision(ride)
        store.select(
            "SELECT DISTINCT metric,CAST(end_us/16000000 AS INTEGER) FROM battery_intervals WHERE ride=? AND to_id>? AND retired_revision IS NULL",
            arrayOf(ride, checkpoint.second),
        ) { c ->
            while (c.moveToNext()) store.analytics.changed(ride, c.getLong(1) * 16.0, listOf(c.getString(0)))
        }
        store.writableDatabase.execSQL(
            "UPDATE battery_intervals SET retired_revision=? WHERE ride=? AND to_id>? AND retired_revision IS NULL",
            arrayOf(revision, ride, checkpoint.second),
        )
        store.writableDatabase.execSQL(
            "DELETE FROM battery_checkpoints WHERE ride=? AND bucket>=?",
            arrayOf(ride, checkpoint.first),
        )
        store.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO battery_state VALUES(?,?,?)",
            arrayOf(ride, checkpoint.second, checkpoint.third),
        )
        val timeline =
            ActiveTimeline(maxOf(timeUs, elapsedUs(store.timing(ride).elapsed)), RideAnalysis(store).lifecycle(ride))
        var after = checkpoint.second
        while (true) {
            val page =
                store.select(
                    "SELECT * FROM observations INDEXED BY observations_battery_replay WHERE ride=? AND kind='telemetry' AND id>? ORDER BY id LIMIT 128",
                    arrayOf(ride, after),
                ) { c ->
                    with(store) { c.observationPage() }
                }
            if (page.isEmpty()) break
            page.forEach { append(ride, it, timeline) }
            after = page.last().id
        }
        val metrics = consumptionMetrics.filter { metric ->
            store.select(
                "SELECT 1 FROM battery_intervals WHERE ride=? AND metric=? AND retired_revision IS NULL LIMIT 1",
                arrayOf(ride, metric),
            ) {
                it.moveToFirst()
            }
        }
        store.writableDatabase.execSQL(
            "UPDATE rides SET metrics=(metrics&?)|? WHERE id=?",
            arrayOf(availabilityBits(consumptionMetrics).inv(), availabilityBits(metrics), ride),
        )
    }

    private fun Cursor.interval() =
        BatteryConsumptionInterval(
            text("metric"),
            long("start_us"),
            long("end_us"),
            double("start_value"),
            double("end_value"),
            getColumnIndexOrThrow("rate0").let { if (isNull(it)) null else getDouble(it) },
            getColumnIndexOrThrow("rate1").let { if (isNull(it)) null else getDouble(it) },
            long("indivisible") != 0L,
            long("segment").toInt(),
            long("active_interval").toInt(),
            long("baseline") != 0L,
        )

    fun range(id: String, metric: String, start: Double, end: Double): BatteryConsumptionRange {
        val a = elapsedUs(start)
        val b = elapsedUs(end)
        val revision = store.readRevision(id)
        data class Edge(val interval: BatteryConsumptionInterval, val covered: Long)
        fun edge(order: String): Edge? =
            store.select(
                "SELECT * FROM battery_intervals WHERE ride=? AND metric=? AND start_us>=? AND end_us>=? AND end_us<=? AND ${visible(revision)} ORDER BY end_us $order,to_id $order LIMIT 1",
                arrayOf(id, metric, a, a, b),
            ) { c ->
                if (c.moveToFirst()) Edge(c.interval(), c.long("covered_us")) else null
            }
        val first = edge("ASC")
        val last = edge("DESC")
        val initial =
            if (first != null && last != null)
                BatteryConsumptionRange(
                    last.interval.endValue - first.interval.startValue,
                    last.covered - first.covered + first.interval.endUs - first.interval.startUs,
                    true,
                    false,
                    false,
                )
            else null
        val reducer = BatteryConsumptionReducer(metric, a, b, initial)
        // Only crossing intervals need numerical clipping; fully contained intervals use cumulative prefixes.
        for (boundary in listOf(a, b).distinct()) store.select(
            "SELECT * FROM battery_intervals WHERE ride=? AND metric=? AND end_us>? AND end_us<=? AND start_us<? AND ${visible(revision)} ORDER BY end_us,to_id",
            arrayOf(id, metric, boundary, boundary + BatteryConsumptionPolicy.maximumCounterEdgeUs, boundary),
        ) { c ->
            while (c.moveToNext()) {
                val interval = c.interval()
                if (boundary == a || interval.startUs >= a) reducer.append(interval)
            }
        }
        return reducer.result(elapsedUs(RideDistance(store).activeSeconds(id, start, end)))
    }

    fun statistics(id: String, metric: String, start: Double, end: Double): Payload {
        val total = range(id, metric, start, end)
        return mapOf(
                "amount" to total.amount.takeIf { total.available },
                "coveredSeconds" to total.coveredUs / 1_000_000.0,
                "partial" to total.partial,
                "unresolvedBoundary" to total.unresolvedBoundary,
            )
            .filterValues { it != null }
    }

    fun summary(id: String): Payload {
        val source = store.metadata(id).str("batteryConsumptionSource")
        val end = store.timing(id).elapsed
        val energy = range(id, "${source}EnergyWh", 0.0, end)
        val charge = range(id, "${source}ChargeAh", 0.0, end)
        return mapOf(
            "source" to source,
            "energyWh" to energy.amount.takeIf { energy.available },
            "chargeAh" to charge.amount.takeIf { charge.available },
            "partial" to (energy.partial || charge.partial),
        )
    }

    fun boundaries(id: String, metric: String, startUs: Long, endUs: Long): List<DistanceInspectionPoint> {
        val upper =
            if (endUs > Long.MAX_VALUE - BatteryConsumptionPolicy.maximumCounterEdgeUs) Long.MAX_VALUE
            else endUs + BatteryConsumptionPolicy.maximumCounterEdgeUs
        return store.select(
            "SELECT d.*,a.event_id AS start_event,a.timestamp AS start_stamp,a.time AS start_time,b.event_id AS end_event,b.timestamp AS end_stamp,b.time AS end_time FROM battery_intervals d JOIN observations a ON a.id=d.from_id JOIN observations b ON b.id=d.to_id WHERE d.ride=? AND d.metric=? AND d.end_us>=? AND d.end_us<=? AND d.start_us<=? AND d.revision<=? AND (d.retired_revision IS NULL OR d.retired_revision>?) ORDER BY d.end_us,d.to_id",
            arrayOf(id, metric, startUs, upper, endUs, store.readRevision(id), store.readRevision(id)),
        ) { c ->
            buildList {
                    while (c.moveToNext()) {
                        val interval = c.interval()
                        for (edge in if (interval.baseline) listOf("end") else listOf("start", "end")) {
                            val row = c.long(if (edge == "start") "from_id" else "to_id")
                            add(
                                DistanceInspectionPoint(
                                    InspectionPoint(
                                        if (edge == "start") interval.startUs else interval.endUs,
                                        Original(
                                            row.toString(),
                                            c.text("${edge}_event"),
                                            c.text("${edge}_stamp"),
                                            c.double("${edge}_time"),
                                            Scalar.Real64(
                                                if (edge == "start") interval.startValue else interval.endValue
                                            ),
                                        ),
                                        Continuity(),
                                    ),
                                    interval.activeInterval,
                                    interval.startUs,
                                    interval.endUs,
                                    row,
                                    interval.segment,
                                )
                            )
                        }
                    }
                }
                .sortedWith(compareBy({ it.point.timeUs }, { it.tieKey }))
        }
    }
}
