package app.powerlog.bridge

import android.content.Context
import android.database.Cursor
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
import org.json.JSONObject

internal class ExportException(code: String, message: String) : CodedException(code, message, null)

internal fun exportFailure(error: Exception, code: String, prefix: String): ExportException =
    error as? ExportException ?: ExportException(code, listOfNotNull(prefix, error.message).joinToString(" "))

internal fun interface MslConversion {
    fun convert(location: Location)
}

@RequiresApi(34)
private class PlatformMslConversion(private val context: Context) : MslConversion {
    private val converter = AltitudeConverter()

    override fun convert(location: Location) = converter.addMslAltitudeToLocation(context, location)
}

internal fun mslConversion(context: Context): MslConversion? =
    if (Build.VERSION.SDK_INT >= 34) PlatformMslConversion(context) else null

private enum class Fill(val numeric: Boolean) {
    Real(true),
    Flag(true),
    Interrupted(true),
    Msl(true),
    MslAccuracy(true),
    Text(false),
    Token(false),
    Phone(false),
}

private val BOTH = setOf("zip", "fit")
private val ZIP = setOf("zip")
private val FIT = setOf("fit")

private class Column(val name: String, val fill: Fill, val column: String? = name, val kinds: Set<String> = BOTH)

private class Projection(
    val name: String,
    val kinds: Set<String>,
    val columns: List<Column>,
    val range: String?,
    val ties: String? = null,
)

private fun real(vararg names: String, kinds: Set<String> = BOTH) = names.map { Column(it, Fill.Real, kinds = kinds) }

// Two exact seeks replace the (time,id) row value, which SQLite seeks only on time when id is the rowid.
private fun stream(
    name: String,
    kinds: Set<String>,
    columns: List<Column>,
    table: String,
    index: String,
    filter: String,
    select: String,
) =
    Projection(
        name,
        kinds,
        columns,
        "SELECT $select FROM $table INDEXED BY $index WHERE ride=?$filter AND time>? ORDER BY time,id LIMIT ?",
        "SELECT $select FROM $table INDEXED BY $index WHERE ride=?$filter AND time=? AND id>? ORDER BY id LIMIT ?",
    )

private val PROJECTIONS =
    listOf(
            stream(
                "telemetry",
                BOTH,
                listOf(
                    Column("elapsedSeconds", Fill.Real, "time"),
                    Column("timestamp", Fill.Text, kinds = ZIP),
                    Column("active", Fill.Flag),
                    Column("connection", Fill.Token, "epoch"),
                ) +
                    real(
                        "humanPowerW",
                        "cadenceRpm",
                        "motorInputPowerW",
                        "batteryVoltageV",
                        "batteryCurrentA",
                        "motorCurrentA",
                        "motorRpm",
                        "pedalTorqueNm",
                        "controllerTempC",
                        "motorTempC",
                        "consumedAh",
                        "consumedWh",
                    ) +
                    real("throttleVoltageV", "faultCode", kinds = ZIP) +
                    real("assistLevel") +
                    real("controllerSpeedMps", "raceMode", "speedRaw", kinds = ZIP),
                "observations",
                "observations_kind",
                " AND kind='telemetry'",
                "id,time,timestamp,active,epoch,identity," + telemetryMetrics.joinToString(","),
            ),
            stream(
                "gps",
                BOTH,
                listOf(
                    Column("elapsedSeconds", Fill.Real, "time"),
                    Column("timestamp", Fill.Text, kinds = ZIP),
                    Column("producer", Fill.Phone, null),
                    Column("active", Fill.Flag),
                ) +
                    real("latitude", "longitude") +
                    listOf(
                        Column("altitudeMeters", Fill.Msl, null),
                        Column("verticalAccuracyM", Fill.MslAccuracy, null),
                        Column("ellipsoidalAltitudeMeters", Fill.Real, "altitudeMeters", ZIP),
                        Column("ellipsoidalVerticalAccuracyM", Fill.Real, "verticalAccuracyM", ZIP),
                    ) +
                    real("horizontalAccuracyM", "speedMps", "speedAccuracyMps") +
                    real("courseDegrees", kinds = ZIP),
                "observations",
                "observations_kind",
                " AND kind='location'",
                "id,time,timestamp,active,latitude,longitude,altitudeMeters,verticalAccuracyM," +
                    "horizontalAccuracyM,speedMps,speedAccuracyMps,courseDegrees",
            ),
            stream(
                "gpsDiscovery",
                FIT,
                listOf(
                    Column("elapsedSeconds", Fill.Real, "time"),
                    Column("timestamp", Fill.Text),
                    Column("active", Fill.Flag),
                    Column("producer", Fill.Phone, null),
                    Column("horizontalAccuracyM", Fill.Real),
                ),
                "observations",
                "observations_kind",
                " AND kind='location'",
                "id,time,timestamp,active,horizontalAccuracyM",
            ),
            Projection("healthZip", ZIP, emptyList(), null),
            Projection("healthFit", FIT, emptyList(), null),
            stream(
                "lifecycle",
                BOTH,
                listOf(
                    Column("elapsedSeconds", Fill.Real, "time"),
                    Column("timestamp", Fill.Text, kinds = ZIP),
                    Column("producer", Fill.Phone, null),
                    Column("action", Fill.Text),
                    Column("interrupted", Fill.Interrupted, "action"),
                ),
                "lifecycle",
                "lifecycle_time",
                "",
                "id,time,timestamp,action",
            ),
            Projection(
                "distance",
                FIT,
                listOf(
                    Column("start", Fill.Real),
                    Column("end", Fill.Real),
                    Column("meters", Fill.Real),
                    Column("startSpeed", Fill.Real, "u"),
                    Column("endSpeed", Fill.Real, "v"),
                ),
                "SELECT start,end,meters,u,v FROM distance_intervals WHERE ride=? AND source=? AND end>? ORDER BY end LIMIT ?",
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
) {
    val sealRevision = (metadata["sealRevision"] as? Number)?.toLong()
}

private class ExportSession(
    val id: String,
    val ride: String,
    val kind: String,
    val revision: Long,
    val sealRevision: Long,
    val interrupted: Boolean,
    val distanceSource: String?,
    private val factory: () -> MslConversion?,
) {
    @Volatile var closed = false
    val busy = AtomicBoolean()
    val connections = HashSet<String>()
    private var resolved = false
    private var conversion: MslConversion? = null
    private var failed = false

    fun conversion(): MslConversion? {
        if (!resolved) {
            conversion = factory()
            resolved = true
        }
        return conversion.takeUnless { failed }
    }

    fun convert(conversion: MslConversion, location: Location): Boolean =
        try {
            conversion.convert(location)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (error: Exception) {
            Log.w("PowerLog", "Altitude conversion stopped for this export.", error)
            failed = true
            false
        }
}

internal class ExportSource(
    private val store: RideStore,
    private val distance: RideDistance,
    private val database: Executor,
    private val work: Executor,
    private val pageRows: Int = PAGE_ROWS,
    private val jobRows: Int = JOB_ROWS,
    private val ready: () -> Unit = {},
    private val conversion: () -> MslConversion?,
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
                    ?: throw ExportException("unsupported", "This distance source cannot be exported.")
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
                throw ExportException("unsupported", "This export does not read ${projection.name}.")
            val after = cursor(request["after"], projection.name != "distance")
            if (!session.busy.compareAndSet(false, true))
                throw ExportException("unsupported", "The previous page of this export is still loading.")
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
        session.closed = true
        store.release(session.ride)
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
                if (!page.converts) return@execute finish(page, promise)
                work.execute {
                    try {
                        if (session.closed) throw ExportException("cancelled", "This export was cancelled.")
                        page.convert()
                        finish(page, promise)
                    } catch (error: Exception) {
                        fail(session, error, promise)
                    }
                }
            } catch (error: Exception) {
                fail(session, error, promise)
            }
        }
    }

    private fun finish(page: Page, promise: Promise) {
        val result = page.result()
        page.session.busy.set(false)
        promise.resolve(result)
    }

    private fun fail(session: ExportSession, error: Exception, promise: Promise) {
        session.busy.set(false)
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
            val seal = saved.sealRevision
            val endedAt = metadata["endedAt"] as? String
            if (
                saved.phase != "completed" ||
                    seal == null ||
                    seal != (metadata["verifiedSealRevision"] as? Number)?.toLong() ||
                    endedAt == null
            )
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
            val health = metadata["healthExport"] as? Map<*, *>
            val session =
                ExportSession(
                    UUID.randomUUID().toString(),
                    ride,
                    kind,
                    saved.revision,
                    seal,
                    metadata.flag("interrupted"),
                    profile?.get("source"),
                    conversion,
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
                                    "state" to metadata.str("healthKitState"),
                                    "workoutUUID" to null,
                                    "export" to
                                        health?.let {
                                            mapOf(
                                                "written" to (it["written"] as? Number)?.toInt(),
                                                "omitted" to (it["omitted"] as? Number)?.toInt(),
                                                "reason" to it["reason"] as? String,
                                            )
                                        },
                                ),
                            "watchSyncState" to metadata.str("watchSyncState"),
                            "finalizationState" to metadata.str("finalizationState"),
                            "example" to metadata.flag("example"),
                            "sampleHz" to (metadata["sampleHz"] as? Number)?.toInt(),
                        ),
                    "elapsedEnd" to saved.elapsed,
                    "producers" to mapOf("gps" to if (gps) listOf("phone") else emptyList(), "health" to emptyList()),
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
            "SELECT phase,revision,elapsed,timer,checkpoint_at,metadata FROM rides WHERE id=?",
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
                    JSONObject(c.getString(5)).map(),
                )
        }

    private fun verify(session: ExportSession) {
        val saved = saved(session.ride) ?: throw ExportException("deleted", "This ride was deleted from Power Log.")
        if (saved.revision != session.revision || saved.sealRevision != session.sealRevision)
            throw ExportException("changed", "This ride changed during the export. Export it again.")
    }

    private inner class Page(val session: ExportSession, private val projection: Projection, after: List<Double>?) {
        private val keyed = projection.name != "distance"
        private val telemetry = projection.name == "telemetry"
        val converts = projection.name == "gps"
        private val delivered = projection.columns.filter { session.kind in it.kinds }
        private val numbers = delivered.filter { it.fill.numeric }
        private val texts = delivered.filter { !it.fill.numeric }
        private val data = Array(numbers.size) { ByteArray(pageRows * 8) }
        private val buffers = Array(numbers.size) { ByteBuffer.wrap(data[it]).order(ByteOrder.LITTLE_ENDIAN) }
        private val strings = Array(texts.size) { ArrayList<String?>() }
        private val scratch = arrayOfNulls<String>(texts.size)
        private val connections = ArrayList<Payload>()
        private val fixes = if (converts) Array(4) { DoubleArray(pageRows) } else emptyArray()
        private var first = after == null
        private var lastKey = after?.get(0) ?: Double.NEGATIVE_INFINITY
        private var lastId = if (keyed && after != null) after[1].toLong() else 0L
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
            val range = projection.range
            val source = session.distanceSource
            if (range == null || !keyed && source == null) {
                done = true
                return
            }
            val limit = minOf(jobRows, pageRows - rows).toLong()
            val tied = ties
            val received =
                when {
                    !keyed -> store.select(range, arrayOf(session.ride, checkNotNull(source), lastKey, limit), ::fill)
                    tied ->
                        store.select(
                            checkNotNull(projection.ties),
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
            val keySlot = c.getColumnIndexOrThrow(if (keyed) "time" else "end")
            val idSlot = if (keyed) c.getColumnIndexOrThrow("id") else -1
            val identitySlot = if (telemetry) c.getColumnIndexOrThrow("identity") else -1
            val fixSlots =
                if (converts)
                    listOf("latitude", "longitude", "altitudeMeters", "verticalAccuracyM")
                        .map(c::getColumnIndexOrThrow)
                        .toIntArray()
                else IntArray(0)
            var count = 0
            while (c.moveToNext()) {
                var size = numbers.size * 8L
                for (i in texts.indices) {
                    val value =
                        when (texts[i].fill) {
                            Fill.Phone -> "phone"
                            Fill.Token -> c.getString(textSlots[i]).ifEmpty { null }
                            else -> c.getString(textSlots[i])
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
                val id = if (keyed) c.getLong(idSlot) else 0L
                if (id > RideStore.MAX_SAFE_REVISION)
                    throw ExportException("limit", "This ride has too many saved values to export.")
                for (i in numbers.indices) {
                    val slot = numberSlots[i]
                    val value =
                        when (numbers[i].fill) {
                            Fill.Real -> if (c.isNull(slot)) Double.NaN else c.getDouble(slot)
                            Fill.Flag -> if (c.getLong(slot) != 0L) 1.0 else 0.0
                            Fill.Interrupted -> if (session.interrupted && c.getString(slot) == "stop") 1.0 else 0.0
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
                                "vendor" to "cyc",
                                "model" to identity.getOrNull(0)?.ifEmpty { null },
                                "firmware" to identity.getOrNull(1)?.ifEmpty { null },
                                "protocol" to identity.getOrNull(2)?.ifEmpty { null },
                            )
                        )
                    }
                }
                for (k in fixSlots.indices) fixes[k][rows] =
                    if (c.isNull(fixSlots[k])) Double.NaN else c.getDouble(fixSlots[k])
                lastKey = c.getDouble(keySlot)
                lastId = id
                bytes += size
                rows++
                count++
                if (rows == pageRows) full = true
            }
            return count
        }

        fun convert() {
            val msl = numbers.indexOfFirst { it.fill == Fill.Msl }
            val accuracy = numbers.indexOfFirst { it.fill == Fill.MslAccuracy }
            for (i in 0 until rows) {
                val conversion = session.conversion() ?: return
                val latitude = fixes[0][i]
                val longitude = fixes[1][i]
                val height = fixes[2][i]
                val vertical = fixes[3][i]
                if (!latitude.isFinite() || !longitude.isFinite() || !height.isFinite()) continue
                val location = Location("")
                location.latitude = latitude
                location.longitude = longitude
                location.altitude = height
                if (vertical.isFinite()) location.verticalAccuracyMeters = vertical.toFloat()
                if (!session.convert(conversion, location)) continue
                if (location.hasMslAltitude()) buffers[msl].putDouble(i * 8, location.mslAltitudeMeters)
                if (location.hasMslAltitudeAccuracy())
                    buffers[accuracy].putDouble(i * 8, location.mslAltitudeAccuracyMeters.toDouble())
            }
        }

        fun result(): Payload {
            val columns = LinkedHashMap<String, Any?>()
            numbers.forEachIndexed { i, field ->
                columns[field.name] = if (rows == pageRows) data[i] else data[i].copyOf(rows * 8)
            }
            texts.forEachIndexed { i, field -> columns[field.name] = strings[i] }
            return buildMap {
                put("rows", rows)
                put("last", if (rows == 0) null else if (keyed) listOf(lastKey, lastId.toDouble()) else listOf(lastKey))
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

        val work: ExecutorService by lazy { Executors.newSingleThreadExecutor { Thread(it, "PowerLogExport") } }

        fun create(engine: RecordingEngine) =
            ExportSource(
                engine.store,
                engine.distance,
                engine.reads,
                work,
                ready = engine::awaitReady,
            ) {
                mslConversion(engine.context)
            }

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
                    parts.size != (if (keyed) 2 else 1) ||
                    parts.any { !it.isFinite() } ||
                    keyed && (parts[1] % 1.0 != 0.0 || parts[1] !in 0.0..RideStore.MAX_SAFE_REVISION.toDouble())
            )
                throw ExportException("unsupported", "The export position is invalid.")
            return parts
        }
    }
}
