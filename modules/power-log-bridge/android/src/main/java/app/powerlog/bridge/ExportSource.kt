package app.powerlog.bridge

import android.database.Cursor
import app.powerlog.core.telemetry.ExportCatalog
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class ExportException(code: String, message: String) : CodedException(code, message, null)

internal fun exportFailure(error: Exception, code: String, message: String): ExportException =
    error as? ExportException ?: ExportException(code, message)

private enum class Fill(val numeric: Boolean) {
    Real(true),
    Flag(true),
    Text(false),
    Token(false),
    Phone(false),
}

private val BOTH = setOf("zip", "fit")

private class Column(
    val name: String,
    val fill: Fill,
    val column: String? = name,
    val kinds: Set<String> = BOTH,
)

private class Projection(
    val name: String,
    val kinds: Set<String>,
    val columns: List<Column>,
    val range: String?,
    val ties: String? = null,
)

private fun columns(name: String): List<Column> =
    ExportCatalog.projections
        .first { it.name == name }
        .columns
        .filter { "android" in it.platforms }
        .map { field ->
            val source =
                when (field.name) {
                    "elapsedSeconds" -> "time"
                    "producerSequence" -> "producer_sequence"
                    "clockEpoch" -> if (name == "lifecycle") "clockEpoch" else "epoch"
                    "connection",
                    "connectionEpoch" -> "epoch"
                    "originalAltitudeMeters" -> "original_altitude"
                    "originalVerticalAccuracyM" -> "original_vertical_accuracy"
                    "originalAltitudeDatum" -> "original_altitude_datum"
                    "channel",
                    "identifier" -> if (name == "healthFit") "energyMetric" else field.name
                    "originalEventId" -> "event_id"
                    "observationRevision" -> "revision"
                    "originalStartTimestamp" -> "timestamp"
                    "startUs" -> if (name == "distance") "start_us" else "elapsed_us"
                    "endUs" -> if (name == "distance") "end_us" else field.name
                    "startSpeed" -> "u"
                    "endSpeed" -> "v"
                    else -> field.name
                }
            val fill =
                when {
                    field.name == "connection" -> Fill.Token
                    field.name == "producer" && name !in setOf("healthZip", "healthFit") -> Fill.Phone
                    field.name in setOf("active", "interrupted") -> Fill.Flag
                    field.type == "number" -> Fill.Real
                    else -> Fill.Text
                }
            val kinds = field.consumers.map { if (it == "discovery") "fit" else it }.toSet()
            Column(field.name, fill, if (fill == Fill.Phone) null else source, kinds)
        }

// Two exact seeks replace the (time,id) row value, which SQLite seeks only on time when id is the
// rowid.
private fun stream(
    name: String,
    table: String,
    index: String,
    filter: String,
    select: String,
) =
    Projection(
        name,
        ExportCatalog.projections.first { it.name == name }.kinds.toSet(),
        columns(name),
        "SELECT $select FROM $table INDEXED BY $index WHERE ride=?$filter AND time>? ORDER BY time,id LIMIT ?",
        "SELECT $select FROM $table INDEXED BY $index WHERE ride=?$filter AND time=? AND id>? ORDER BY id LIMIT ?",
    )

private val PROJECTIONS =
    listOf(
            stream(
                "telemetry",
                "observations",
                "observations_kind",
                " AND kind='telemetry'",
                "id,time,timestamp,active,epoch,identity,producer_sequence," + telemetryMetrics.joinToString(","),
            ),
            stream(
                "gps",
                "observations",
                "observations_kind",
                " AND kind='location'",
                "id,time,timestamp,active,epoch,producer_sequence,latitude,longitude,altitudeMeters,verticalAccuracyM,original_altitude,original_vertical_accuracy,original_altitude_datum," +
                    "horizontalAccuracyM,speedMps,speedAccuracyMps,courseDegrees,json_extract(evidence,'$.values.courseAccuracyDegrees') AS courseAccuracyDegrees",
            ),
            stream(
                "gpsDiscovery",
                "observations",
                "observations_kind",
                " AND kind='location'",
                "id,time,horizontalAccuracyM",
            ),
            stream(
                "healthZip",
                "observations",
                "observations_kind",
                " AND kind='health'",
                "*,NULL AS distanceMeters,NULL AS riderPowerW,NULL AS cadenceRpm,json_extract(evidence,'$.measure') AS identifier,json_extract(evidence,'$.value') AS value,json_extract(evidence,'$.unit') AS unit,json_extract(evidence,'$.representation') AS representation,1 AS sampleCount,json_extract(evidence,'$.originalStartTimestamp') AS sampleStart,json_extract(evidence,'$.origin.provider') AS provider,json_extract(evidence,'$.origin.providerObservationId') AS providerParentId,json_extract(evidence,'$.origin.dataOrigin') AS dataOrigin,json_extract(evidence,'$.originalEndTimestamp') AS originalEndTimestamp,json_extract(evidence,'$.memberIndex') AS memberIndex,json_extract(evidence,'$.logicalKey') AS logicalKey,json_extract(evidence,'$.targetEventId') AS targetEventId,json_extract(evidence,'$.origin.originRevision') AS originRevision,json_extract(evidence,'$.valueBits') AS valueBits",
            ),
            stream(
                "healthFit",
                "observations",
                "observations_kind",
                " AND kind='health'",
                "*,json_extract(evidence,'$.measure') AS energyMetric,json_extract(evidence,'$.representation') AS representation,1 AS sampleCount,json_extract(evidence,'$.endUs') AS endUs,json_extract(evidence,'$.origin.provider') AS provider,json_extract(evidence,'$.origin.dataOrigin') AS dataOrigin,json_extract(evidence,'$.origin.providerObservationId') AS providerParentId,json_extract(evidence,'$.originalEndTimestamp') AS originalEndTimestamp",
            ),
            stream(
                "lifecycle",
                "lifecycle",
                "lifecycle_time",
                "",
                "id,time,timestamp,action,interrupted,producer_sequence,json_extract(evidence,'$.clockEpoch') AS clockEpoch,json_extract(evidence,'$.controllerSequence') AS controllerSequence",
            ),
            Projection(
                "distance",
                ExportCatalog.projections.first { it.name == "distance" }.kinds.toSet(),
                columns("distance"),
                "SELECT end_us,to_id,start_us,meters_q/1000000.0 AS meters,u,v FROM distance_intervals WHERE ride=? AND source=? AND (end_us,to_id)>(?,?) ORDER BY end_us,to_id LIMIT ?",
            ),
        )
        .associateBy { it.name }

private class SavedRide(
    val phase: String,
    val revision: Long,
    val elapsed: Double,
    val timer: Double,
    val checkpoint: String,
    val metadata: Payload,
)

private class ExportSession(
    val id: String,
    val ride: String,
    val kind: String,
    val revision: Long,
    val distanceSource: String?,
) {
    @Volatile var closed = false
    val busy = AtomicBoolean()
    val released = AtomicBoolean()
    val connections = HashSet<String>()
}

internal class ExportSource(
    private val store: RideStore,
    private val distance: RideDistance,
    private val database: Executor,
    private val pageRows: Int = PAGE_ROWS,
    private val jobRows: Int = JOB_ROWS,
    private val ready: () -> Unit = {},
) {
    private val sessions = ConcurrentHashMap<String, ExportSession>()
    @Volatile private var stopped = false

    fun open(request: Map<String, Any?>, promise: Promise) {
        val ride: String
        val kind: String
        val source: String
        try {
            ride = text(request["rideId"], "ride")
            kind = request["kind"] as? String ?: ""
            if (kind !in BOTH) throw ExportException("unsupported", "This export kind is not supported.")
            source =
                DistanceSource.entries.firstOrNull { it.wire == (request["distanceSource"] ?: "auto") }?.wire
                    ?: throw ExportException(
                        "unsupported",
                        "This distance source cannot be exported.",
                    )
            val context = request["context"] as? Map<*, *>
            if (context?.get("exportedAt") !is String || context["platform"] != "android")
                throw ExportException("unsupported", "The export context is invalid.")
        } catch (error: ExportException) {
            promise.reject(error)
            return
        }
        database.execute {
            try {
                ready()
                promise.resolve(admit(ride, kind, source))
            } catch (error: Exception) {
                promise.reject(exportFailure(error, "gate", "Power Log could not open this ride for export."))
            }
        }
    }

    fun page(request: Map<String, Any?>, promise: Promise) {
        val page: Page
        try {
            val session =
                sessions[text(request["session"], "session")]
                    ?: throw ExportException("cancelled", "This export was cancelled.")
            val projection =
                PROJECTIONS[request["projection"] as? String ?: ""]
                    ?: throw ExportException("unsupported", "This export reads an unknown table.")
            if (session.kind !in projection.kinds)
                throw ExportException(
                    "unsupported",
                    "This export does not read ${projection.name}.",
                )
            val after = cursor(request["after"], projection.name != "distance")
            synchronized(session) {
                if (session.closed) throw ExportException("cancelled", "This export was cancelled.")
                if (!session.busy.compareAndSet(false, true))
                    throw ExportException("unsupported", "The previous page of this export is still loading.")
            }
            page = Page(session, projection, after)
        } catch (error: ExportException) {
            promise.reject(error)
            return
        }
        read(page, promise)
    }

    fun close(id: String, promise: Promise) {
        close(id)
        promise.resolve(null)
    }

    fun close(id: String) {
        val session = sessions.remove(id) ?: return
        synchronized(session) {
            session.closed = true
            if (!session.busy.get()) release(session)
        }
    }

    private fun release(session: ExportSession) {
        if (session.released.compareAndSet(false, true)) store.release(session.ride)
    }

    fun closeAll() {
        stopped = true
        sessions.keys.toList().forEach(::close)
    }

    private fun read(page: Page, promise: Promise) {
        val session = page.session
        database.execute {
            try {
                if (session.closed) throw ExportException("cancelled", "This export was cancelled.")
                page.read()
                // Each further query is a new job, so other reads run between the export's queries.
                if (!page.done && !page.full) return@execute read(page, promise)
                verify(session)
                finish(page, promise)
            } catch (error: Exception) {
                fail(session, error, promise)
            }
        }
    }

    private fun finish(page: Page, promise: Promise) {
        val result = page.result()
        synchronized(page.session) {
            page.session.busy.set(false)
            if (page.session.closed) {
                release(page.session)
                throw ExportException("cancelled", "This export was cancelled.")
            }
            promise.resolve(result)
        }
    }

    private fun fail(session: ExportSession, error: Exception, promise: Promise) {
        synchronized(session) {
            session.busy.set(false)
            if (session.closed) release(session)
        }
        promise.reject(exportFailure(error, "gate", "Power Log could not read this ride."))
    }

    private fun admit(ride: String, kind: String, source: String): Payload {
        try {
            store.retain(ride)
        } catch (_: IllegalStateException) {
            if (saved(ride) == null) throw ExportException("deleted", "This ride was deleted from Power Log.")
            throw ExportException("gate", "Finish the ride before exporting.")
        }
        var admitted = false
        try {
            val saved = saved(ride) ?: throw ExportException("deleted", "This ride was deleted from Power Log.")
            val metadata = saved.metadata
            val endedAt = metadata["endedAt"] as? String
            if (!store.canExport(ride, saved.revision))
                throw ExportException("gate", "Finish saving this ride before exporting.")
            val profile =
                if (kind != "fit") null
                else
                    when (distance.selected(ride, source)) {
                        "gps:phone" -> mapOf("source" to "gps:phone", "kind" to "gps", "producer" to "phone")
                        "controller" -> mapOf("source" to "controller", "kind" to "controller")
                        else -> null
                    }
            val gps =
                store.select(
                    "SELECT 1 FROM observations INDEXED BY observations_kind WHERE ride=? AND kind='location' LIMIT 1",
                    arrayOf(ride),
                ) {
                    it.moveToFirst()
                }
            val session =
                ExportSession(
                    UUID.randomUUID().toString(),
                    ride,
                    kind,
                    saved.revision,
                    profile?.get("source"),
                )
            val result =
                mapOf(
                    "session" to session.id,
                    "metadata" to
                        mapOf(
                            "startedAt" to metadata.str("startedAt"),
                            "endedAt" to endedAt,
                            "ownerTiming" to
                                mapOf(
                                    "timestamp" to saved.checkpoint,
                                    "elapsedSeconds" to saved.elapsed,
                                    "timerSeconds" to saved.timer,
                                ),
                            "indoor" to metadata.flag("indoor"),
                            "interrupted" to metadata.flag("interrupted"),
                            "watchEnabled" to metadata.flag("watchEnabled"),
                            "saveToHealth" to metadata.flag("saveToHealth"),
                            "recordGPS" to metadata.flag("recordGPS"),
                            "health" to
                                mapOf(
                                    "provider" to metadata["healthProvider"] as? String,
                                    "components" to metadata["componentResults"],
                                    "projectionFailures" to metadata["healthProjectionFailures"],
                                    "physiologyProvenance" to "backfilled",
                                    "readback" to HealthReadings(store).summary(ride),
                                    "state" to metadata.str("healthKitState"),
                                    "workoutUUID" to store.healthJob(ride)?.workoutId,
                                    "export" to null,
                                ),
                            "collectionRevision" to metadata["collectionRevision"],
                            "example" to metadata.flag("example"),
                            "sampleHz" to (metadata["sampleHz"] as? Number)?.toInt(),
                            "batteryConsumptionSource" to metadata["batteryConsumptionSource"],
                        ),
                    "elapsedEnd" to saved.elapsed,
                    "producers" to
                        mapOf(
                            "gps" to if (gps) listOf("phone") else emptyList(),
                            "health" to if (store.count(ride, "health") > 0) listOf("phone") else emptyList(),
                        ),
                    "distanceProfile" to profile,
                )
            sessions[session.id] = session
            admitted = true
            // closeAll may have run while this ride was being admitted.
            if (stopped) {
                close(session.id)
                throw ExportException("cancelled", "This export was cancelled.")
            }
            return result
        } finally {
            if (!admitted) store.release(ride)
        }
    }

    private fun saved(ride: String): SavedRide? =
        store.select(
            "SELECT phase,revision,elapsed,timer,checkpoint_at FROM rides WHERE id=?",
            arrayOf(ride),
        ) { c ->
            if (!c.moveToFirst()) null
            else
                SavedRide(
                    c.getString(0),
                    c.getLong(1),
                    c.getDouble(2),
                    c.getDouble(3),
                    c.getString(4),
                    store.metadata(ride),
                )
        }

    private fun verify(session: ExportSession) {
        val saved = saved(session.ride) ?: throw ExportException("deleted", "This ride was deleted from Power Log.")
        if (saved.revision != session.revision)
            throw ExportException(
                "changed",
                "This ride changed during the export. Export it again.",
            )
    }

    private inner class Page(
        val session: ExportSession,
        private val projection: Projection,
        after: List<Double>?,
    ) {
        private val keyed = projection.name != "distance"
        private val telemetry = projection.name == "telemetry"
        private val delivered = projection.columns.filter { session.kind in it.kinds }
        private val numbers = delivered.filter { it.fill.numeric }
        private val texts = delivered.filter { !it.fill.numeric }
        private val data = Array(numbers.size) { ByteArray(pageRows * 8) }
        private val buffers = Array(numbers.size) { ByteBuffer.wrap(data[it]).order(ByteOrder.LITTLE_ENDIAN) }
        private val strings = Array(texts.size) { ArrayList<String?>() }
        private val scratch = arrayOfNulls<String>(texts.size)
        private val connections = ArrayList<Payload>()
        private var selectedHealth: Map<String, String?> = emptyMap()
        private var first = after == null
        private var lastKey = after?.get(0) ?: Double.NEGATIVE_INFINITY
        private var lastUs = if (!keyed && after != null) after[0].toLong() else Long.MIN_VALUE
        private var lastId = after?.get(1)?.toLong() ?: 0L
        private var ties = keyed && after != null
        private var rows = 0
        private var bytes = 0L
        var done = false
            private set

        var full = false
            private set

        fun read() {
            if (first && telemetry) session.connections.clear()
            first = false
            val rawRange =
                if (projection.name in setOf("healthZip", "healthFit")) {
                    projection.range?.replace(
                        " AND kind='health'",
                        " AND kind='health' ${store.selectedPredicate("observations")}",
                    )
                } else projection.range
            val range =
                if (projection.name == "healthFit" && rawRange != null) {
                    val selected = HealthReadings(store).origins(session.ride)
                    selectedHealth = selected
                    fun literal(value: String?) = value?.let { "'${it.replace("'","''")}'" } ?: "NULL"
                    rawRange.replace(
                        " AND time",
                        " AND coalesce(json_extract(evidence,'$.collectionMapped'),1)!=0 AND ((json_extract(evidence,'$.measure')='heartRate' AND json_extract(evidence,'$.origin.dataOrigin')=${literal(selected["heartRate"])}) OR (json_extract(evidence,'$.measure')='activeEnergy' AND json_extract(evidence,'$.origin.dataOrigin')=${literal(selected["activeEnergy"])})) AND time",
                    )
                } else rawRange
            val source = session.distanceSource
            if (range == null || !keyed && source == null) {
                done = true
                return
            }
            val limit = minOf(jobRows, pageRows - rows).toLong()
            val tied = ties
            val received =
                when {
                    !keyed ->
                        store.select(
                            range,
                            arrayOf(
                                session.ride,
                                checkNotNull(source),
                                lastUs,
                                lastId,
                                limit,
                            ),
                            ::fill,
                        )
                    tied ->
                        store.select(
                            if (projection.name == "healthFit")
                                checkNotNull(range)
                                    .replace(
                                        "time>? ORDER BY time,id",
                                        "time=? AND id>? ORDER BY id",
                                    )
                            else checkNotNull(projection.ties),
                            arrayOf(session.ride, lastKey, lastId, limit),
                            ::fill,
                        )
                    else -> store.select(range, arrayOf(session.ride, lastKey, limit), ::fill)
                }
            if (full) return
            if (received < limit) {
                if (tied) ties = false else done = true
            } else ties = keyed
        }

        private fun fill(c: Cursor): Int {
            val numberSlots = IntArray(numbers.size) { numbers[it].column?.let(c::getColumnIndexOrThrow) ?: -1 }
            val textSlots = IntArray(texts.size) { texts[it].column?.let(c::getColumnIndexOrThrow) ?: -1 }
            val keySlot = c.getColumnIndexOrThrow(if (keyed) "time" else "end_us")
            val idSlot = c.getColumnIndexOrThrow(if (keyed) "id" else "to_id")
            val identitySlot = if (telemetry) c.getColumnIndexOrThrow("identity") else -1
            var count = 0
            while (c.moveToNext()) {
                val unmapped =
                    projection.name == "healthZip" &&
                        org.json
                            .JSONObject(c.getString(c.getColumnIndexOrThrow("evidence")))
                            .optBoolean("collectionMapped", true) == false
                val energy =
                    if (
                        projection.name == "healthFit" &&
                            c.getString(c.getColumnIndexOrThrow("representation")) == "intervalRecord"
                    )
                        HealthReadings(store)
                            .interval(
                                with(store) { c.observation() },
                                app.powerlog.core.analytics.AnalysisRange(
                                    0,
                                    elapsedUs(store.timing(session.ride).elapsed),
                                ),
                            )
                    else null
                var size = numbers.size * 8L
                for (i in texts.indices) {
                    val value =
                        when {
                            energy != null && texts[i].name == "startUs" -> energy.startUs.toString()
                            energy != null && texts[i].name == "endUs" -> energy.endUs.toString()
                            else ->
                                when (texts[i].fill) {
                                    Fill.Phone -> "phone"
                                    Fill.Token -> c.getString(textSlots[i]).ifEmpty { null }
                                    else -> c.getString(textSlots[i])
                                }
                        }
                    scratch[i] = value
                    // A UTF-16 unit never needs more than three UTF-8 bytes.
                    size += (value?.length ?: 0) * 3L
                }
                if (bytes + size > PAGE_BYTES) {
                    if (rows == 0) throw ExportException("limit", "A saved value is too large to export.")
                    full = true
                    break
                }
                val id = c.getLong(idSlot)
                if (id > RideStore.MAX_SAFE_REVISION)
                    throw ExportException("limit", "This ride has too many saved values to export.")
                for (i in numbers.indices) {
                    val slot = numberSlots[i]
                    val value =
                        when (numbers[i].fill) {
                            Fill.Real ->
                                if (unmapped && numbers[i].name == "elapsedSeconds") Double.NaN
                                else if (projection.name == "healthFit" && numbers[i].name == "activeEnergyKcal") {
                                    val metric = c.getString(c.getColumnIndexOrThrow("energyMetric"))
                                    val origin = c.getString(c.getColumnIndexOrThrow("dataOrigin"))
                                    val admitted = metric == "activeEnergy" && origin == selectedHealth["activeEnergy"]
                                    if (admitted) energy?.valueKcal ?: Double.NaN else Double.NaN
                                } else if (c.isNull(slot)) Double.NaN else c.getDouble(slot)
                            Fill.Flag -> if (c.getLong(slot) != 0L) 1.0 else 0.0
                            else -> Double.NaN
                        }
                    buffers[i].putDouble(rows * 8, value)
                }
                for (i in texts.indices) {
                    val value = scratch[i]
                    strings[i].add(value)
                    if (texts[i].fill == Fill.Token && value != null && session.connections.add(value)) {
                        val identity = c.getString(identitySlot).split('|')
                        connections.add(
                            mapOf(
                                "token" to value,
                                "vendor" to "controller",
                                "model" to identity.getOrNull(0)?.ifEmpty { null },
                                "firmware" to identity.getOrNull(1)?.ifEmpty { null },
                                "protocol" to identity.getOrNull(2)?.ifEmpty { null },
                            )
                        )
                    }
                }
                if (keyed) lastKey = c.getDouble(keySlot)
                else {
                    lastUs = c.getLong(keySlot)
                    if (lastUs !in -RideStore.MAX_SAFE_REVISION..RideStore.MAX_SAFE_REVISION)
                        throw ExportException(
                            "limit",
                            "This ride's distance timeline exceeds the export cursor bound.",
                        )
                }
                lastId = id
                bytes += size
                rows++
                count++
                if (rows == pageRows) full = true
            }
            return count
        }

        fun result(): Payload {
            val columns = LinkedHashMap<String, Any?>()
            numbers.forEachIndexed { i, field ->
                columns[field.name] = if (rows == pageRows) data[i] else data[i].copyOf(rows * 8)
            }
            texts.forEachIndexed { i, field -> columns[field.name] = strings[i] }
            return buildMap {
                put("rows", rows)
                put(
                    "last",
                    if (rows == 0) null else listOf(if (keyed) lastKey else lastUs.toDouble(), lastId.toDouble()),
                )
                put("done", done)
                put("columns", columns)
                if (telemetry) put("connections", connections)
            }
        }
    }

    companion object {
        const val PAGE_ROWS = 4096
        const val PAGE_BYTES = 4L * 1024 * 1024
        const val JOB_ROWS = 1024

        val work: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "PowerLogExport") }
        }

        fun create(engine: RecordingEngine) =
            ExportSource(
                engine.store,
                engine.distance,
                engine.reads,
                ready = engine::awaitReady,
            )

        fun sql(projection: String): List<String> = PROJECTIONS[projection].let { listOfNotNull(it?.range, it?.ties) }

        private fun text(value: Any?, field: String): String {
            if (value !is String || value.isBlank() || value.any { it.isISOControl() })
                throw ExportException("unsupported", "The export request names no valid $field.")
            return value
        }

        private fun cursor(value: Any?, keyed: Boolean): List<Double>? {
            if (value == null) return null
            val parts = (value as? List<*>)?.map { (it as? Number)?.toDouble() ?: Double.NaN }
            if (
                parts == null ||
                    parts.size != 2 ||
                    parts.any { !it.isFinite() } ||
                    parts[1] % 1.0 != 0.0 ||
                    parts[1] !in 0.0..RideStore.MAX_SAFE_REVISION.toDouble() ||
                    !keyed &&
                        (parts[0] % 1.0 != 0.0 ||
                            parts[0] !in
                                -RideStore.MAX_SAFE_REVISION.toDouble()..RideStore.MAX_SAFE_REVISION.toDouble())
            )
                throw ExportException("unsupported", "The export position is invalid.")
            return parts
        }
    }
}
