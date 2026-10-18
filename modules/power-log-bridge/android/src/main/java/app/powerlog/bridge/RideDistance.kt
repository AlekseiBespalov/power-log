package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*
import kotlin.math.*

internal class RideDistance(private val store: RideStore) {
    private data class State(
        val row: Long,
        val gps: GPSDistanceCheckpoint? = null,
        val controller: ControllerDistanceCheckpoint? = null,
    )

    private val previous = mutableMapOf<String, State>()

    fun forget(ride: String) {
        previous.keys.removeAll { it.startsWith("$ride:") }
    }

    fun retainOnly(ride: String) = previous.keys.retainAll { it.startsWith("$ride:") }

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
    ): String? {
        check(store.writableDatabase.inTransaction()) {
            "Distance must commit with its original observation."
        }
        val source = if (gps) "gps:phone" else "controller"
        val key = "$ride:$source"
        val previousBefore = previous[key]
        store.onRollback {
            if (previousBefore == null) previous.remove(key) else previous[key] = previousBefore
        }
        if (previousBefore?.row == row) return null
        val column = if (gps) "gpsDistanceMeters" else "controllerDistanceMeters"
        if (
            store.select(
                "SELECT 1 FROM distance_intervals WHERE ride=? AND source=? AND to_id=?",
                arrayOf(ride, source, row),
            ) {
                it.moveToFirst()
            }
        )
            return column
        val observed =
            store.select("SELECT * FROM observations WHERE ride=? AND id=?", arrayOf(ride, row)) { c ->
                check(c.moveToFirst())
                with(store) { c.observation() }
            }
        val item = observed.analysisItem()
        val interval =
            if (gps) {
                val kernel = GPSKernel()
                previousBefore?.gps?.let(kernel::restoreDistanceCheckpoint)
                val result = kernel.append(GPSFix(item, segment.takeIf { active }))
                previous[key] = State(row, gps = kernel.distanceCheckpoint())
                result.interval
            } else {
                val descriptor = identity.split('|')
                val kernel = ControllerDistanceKernel(previousBefore?.controller)
                val speed = values["controllerSpeedMps"]
                val result =
                    kernel.append(
                        ControllerDistanceEvidence(
                            item.collectionUs,
                            time,
                            speed,
                            descriptor.getOrNull(0),
                            descriptor.getOrNull(2),
                            descriptor.getOrNull(1),
                            epoch,
                            segment.takeIf { active },
                            (observed.evidence["acquisitionMonotonic"] as? Number)?.toDouble(),
                            values["crankRevolutions"]?.let { Scalar.Real64(it) },
                            false,
                            Original(
                                item.observationId,
                                item.eventId,
                                item.timestamp,
                                time,
                                Scalar.Real64(speed ?: 0.0),
                            ),
                        )
                    )
                previous[key] = State(row, controller = kernel.checkpoint())
                result
            } ?: return null
        val prior =
            store.select(
                "SELECT cumulative_q,covered_us FROM distance_intervals WHERE ride=? AND source=? ORDER BY end_us DESC,to_id DESC LIMIT 1",
                arrayOf(ride, source),
            ) { c ->
                if (c.moveToFirst()) DistanceRange(c.getLong(0), c.getLong(1), false, false)
                else DistanceRange(0, 0, false, false)
            }
        val totals = DistanceReducer(AnalysisRange(0, interval.endUs), prior)
        totals.append(interval)
        val total = totals.result()
        val startUs = interval.startUs
        val endUs = interval.endUs
        val distanceQ = interval.distanceQ
        val from =
            store.select(
                "SELECT id FROM observations WHERE ride=? AND event_id=?",
                arrayOf(ride, interval.start.eventId),
            ) { c ->
                check(c.moveToFirst())
                c.getLong(0)
            }
        val previousRendering =
            store.select(
                "SELECT d.plot_segment,o.* FROM distance_intervals d JOIN observations o ON o.id=d.to_id WHERE d.ride=? AND d.source=? ORDER BY d.end_us DESC,d.to_id DESC LIMIT 1",
                arrayOf(ride, source),
            ) { c ->
                if (c.moveToFirst()) c.long("plot_segment").toInt() to with(store) { c.observation() } else null
            }
        val startOriginal =
            store.metricObservation(ride, column, from)
                ?: store.select(
                    "SELECT * FROM observations WHERE ride=? AND id=?",
                    arrayOf(ride, from),
                ) { c ->
                    check(c.moveToFirst())
                    with(store) { c.observation() }
                }
        fun renderingPoint(original: Observation) =
            InspectionPoint(
                original.elapsedUs,
                Original(original.id.toString(), "", original.timestamp, original.time, Scalar.Real64(0.0)),
                original.displayContinuity(),
            )
        val plotSegment =
            DistanceInspection.plotSegment(
                previousRendering?.first ?: 0,
                previousRendering?.second?.let(::renderingPoint),
                renderingPoint(startOriginal),
            )
        fun anchor(id: Long): String =
            store.select(
                "SELECT event_id,producer,producer_sequence,timestamp FROM observations WHERE id=?",
                arrayOf(id),
            ) { c ->
                check(c.moveToFirst())
                json(
                        mapOf(
                            "eventId" to c.getString(0),
                            "producer" to c.getString(1),
                            "producerSequence" to c.getLong(2).toString(),
                            "timestamp" to c.getString(3),
                        )
                    )
                    .toString()
            }
        store.writableDatabase.execSQL(
            "INSERT INTO distance_intervals(ride,source,start_us,end_us,meters_q,cumulative_q,covered_us,original_meters,from_id,to_id,u,v,segment,plot_segment,indivisible,revision,start_anchor,end_anchor) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                ride,
                source,
                startUs,
                endUs,
                distanceQ,
                total.distanceQ,
                total.coveredUs,
                interval.originalMeters,
                from,
                row,
                previousBefore?.controller?.previous?.speedMps,
                values["controllerSpeedMps"].takeUnless { gps },
                segment,
                plotSegment,
                if (gps) 1 else 0,
                store.revision(ride),
                anchor(from),
                anchor(row),
            ),
        )
        store.analytics.changed(ride, time, listOf(column))
        store.writableDatabase.execSQL(
            "UPDATE rides SET metrics=metrics|? WHERE id=?",
            arrayOf(availabilityBits(listOf(column)), ride),
        )
        return column
    }

    fun total(id: String, source: String): Pair<Double, Double> =
        store.readableDatabase
            .rawQuery(
                "SELECT cumulative_q,covered_us FROM distance_intervals WHERE ride=? AND source=? AND revision<=? ORDER BY end_us DESC,to_id DESC LIMIT 1",
                arrayOf(id, source, store.readRevision(id).toString()),
            )
            .use { c ->
                if (c.moveToFirst())
                    FixedPoint.divide(c.getLong(0), 1000000) to FixedPoint.divide(c.getLong(1), 1000000)
                else 0.0 to 0.0
            }

    fun coreInfo(id: String, selection: String): DistanceInfo {
        val analysis = RideAnalysis(store)
        val pin = analysis.pin(id, selection)
        val timeline = analysis.timeline(id)
        val available =
            listOf("gps:phone", "controller").mapNotNull { source ->
                distanceProfile(
                    source,
                    coreTotal(id, source, pin.metadata.elapsedSeconds),
                    timeline.timerUs,
                    pin.metadata.sources,
                )
            }
        return selectDistance(store.distanceSelection(id).selection, available, false, pin.metadata.indoor)
    }

    fun info(id: String, selection: String): Payload = coreInfo(id, selection).payload()

    fun selected(id: String, selection: String) =
        (info(id, selection)["selected"] as? Map<*, *>)?.get("source") as? String

    fun activeSeconds(id: String, start: Double, end: Double): Double =
        FixedPoint.divide(
            RideAnalysis(store).timeline(id).intervals.fold(0L) { sum, span ->
                FixedPoint.add(
                    sum,
                    maxOf(
                        0L,
                        minOf(span.endUs, elapsedUs(end)) - maxOf(span.startUs, elapsedUs(start)),
                    ),
                )
            },
            1000000,
        )

    private data class StoredTotal(val range: DistanceRange, val endUs: Long, val row: Long)

    private fun coreTotal(id: String, source: String, end: Double): DistanceRange {
        val total =
            store.select(
                "SELECT cumulative_q,covered_us,end_us,to_id FROM distance_intervals WHERE ride=? AND source=? AND end_us<=? AND revision<=? ORDER BY end_us DESC,to_id DESC LIMIT 1",
                arrayOf(id, source, elapsedUs(end), store.readRevision(id)),
            ) { c ->
                if (!c.moveToFirst()) null
                else StoredTotal(DistanceRange(c.getLong(0), c.getLong(1), false, false), c.getLong(2), c.getLong(3))
            }
        return reduceRange(id, source, 0.0, end, total)
    }

    fun coreRange(id: String, source: String, start: Double, end: Double): DistanceRange =
        reduceRange(id, source, start, end, null)

    private fun reduceRange(
        id: String,
        source: String,
        start: Double,
        end: Double,
        total: StoredTotal?,
    ): DistanceRange {
        val range = AnalysisRange(elapsedUs(start), elapsedUs(end))
        val reducer = DistanceReducer(range, total?.range)
        var afterEnd = total?.endUs ?: Long.MIN_VALUE
        var afterId = total?.row ?: 0L
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val rows =
                store.select(
                    "SELECT d.*,a.event_id AS start_event,a.timestamp AS start_stamp,a.time AS start_time,b.event_id AS end_event,b.timestamp AS end_stamp,b.time AS end_time FROM distance_intervals d JOIN observations a ON a.id=d.from_id JOIN observations b ON b.id=d.to_id WHERE d.ride=? AND d.source=? AND d.end_us>=? AND d.start_us<? AND d.revision<=? AND (d.end_us,d.to_id)>(?,?) ORDER BY end_us,to_id LIMIT 16",
                    arrayOf(
                        id,
                        source,
                        maxOf(range.startUs, afterEnd),
                        range.endUs,
                        store.readRevision(id),
                        afterEnd,
                        afterId,
                    ),
                ) { c ->
                    buildList {
                        while (c.moveToNext()) {
                            fun anchor(prefix: String) =
                                Original(
                                    "event:${c.text("${prefix}_event")}",
                                    c.text("${prefix}_event"),
                                    c.text("${prefix}_stamp"),
                                    c.double("${prefix}_time"),
                                    Scalar.Real64(0.0),
                                )
                            fun speed(key: String) =
                                c.getColumnIndexOrThrow(key).let {
                                    if (c.isNull(it)) null else FixedPoint.quantize(c.getDouble(it), 1000000)
                                }
                            add(
                                c.long("end_us") to
                                    (c.long("to_id") to
                                        DistanceInterval(
                                            c.long("start_us"),
                                            c.long("end_us"),
                                            c.long("meters_q"),
                                            c.long("segment").toInt(),
                                            c.long("segment").toInt(),
                                            anchor("start"),
                                            anchor("end"),
                                            speed("u"),
                                            speed("v"),
                                        ))
                            )
                        }
                    }
                }
            if (rows.isEmpty()) break
            rows.forEach { reducer.append(it.second.second) }
            afterEnd = rows.last().first
            afterId = rows.last().second.first
        }
        return reducer.result(elapsedUs(activeSeconds(id, start, end)))
    }

    fun range(id: String, source: String, start: Double, end: Double): Pair<Double, Double> =
        coreRange(id, source, start, end).let { it.distanceMeters to it.coveredSeconds }

    companion object {
        fun haversine(a: Double, b: Double, c: Double, d: Double): Double = PureMath.haversine(a, b, c, d)
    }
}
