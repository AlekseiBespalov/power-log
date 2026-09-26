package app.powerlog.bridge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import org.json.JSONObject

internal val telemetryMetrics =
    listOf(
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
        "throttleVoltageV",
        "faultCode",
        "assistLevel",
        "raceMode",
        "speedRaw",
        "controllerSpeedMps",
    )
internal val locationMetrics =
    listOf(
        "speedMps",
        "altitudeMeters",
        "horizontalAccuracyM",
        "verticalAccuracyM",
        "courseDegrees",
        "latitude",
        "longitude",
        "speedAccuracyMps",
    )
internal val storedMetrics =
    telemetryMetrics + locationMetrics + listOf("gpsDistanceMeters", "controllerDistanceMeters")

internal data class Observation(
    val id: Long,
    val time: Double,
    val timestamp: String,
    val segment: Int,
    val values: Map<String, Double>,
    val kind: String,
    val active: Boolean,
    val identity: String,
    val epoch: String,
)

/**
 * Originals are wide typed rows. Reads page metadata or bounded geometry, never whole-ride JSON.
 */
internal class RideStore(context: Context, name: String = "power-log.sqlite") :
    SQLiteOpenHelper(context, name, null, 2) {
    val analytics = RideAnalytics(this)
    private val reads = mutableMapOf<String, Int>()

    fun <T> withSavedRide(id: String, body: () -> T): T {
        synchronized(reads) {
            check(metadata(id).str("phase") == "completed") { "Finish the ride before exporting." }
            reads[id] = (reads[id] ?: 0) + 1
        }
        try {
            return body()
        } finally {
            synchronized(reads) {
                val count = reads.getValue(id) - 1
                if (count == 0) reads.remove(id) else reads[id] = count
            }
        }
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE analytics(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,metric TEXT NOT NULL,bucket INTEGER NOT NULL,first REAL NOT NULL,last REAL NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(ride,metric,bucket)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE rides(id TEXT PRIMARY KEY, started TEXT NOT NULL, phase TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, elapsed REAL NOT NULL DEFAULT 0, timer REAL NOT NULL DEFAULT 0, metrics INTEGER NOT NULL DEFAULT 0, metadata TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX rides_catalog ON rides(started DESC,id DESC)")
        db.execSQL(
            "CREATE TABLE observations(id INTEGER PRIMARY KEY, ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE, time REAL NOT NULL, timestamp TEXT NOT NULL, kind TEXT NOT NULL, active INTEGER NOT NULL, segment INTEGER NOT NULL, identity TEXT NOT NULL, epoch TEXT NOT NULL, ${storedMetrics.joinToString { "$it REAL" }})"
        )
        db.execSQL("CREATE INDEX observations_time ON observations(ride,time,id)")
        db.execSQL("CREATE INDEX observations_kind ON observations(ride,kind,time,id)")
        db.execSQL(
            "CREATE TABLE lifecycle(id INTEGER PRIMARY KEY,ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,time REAL NOT NULL,action TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX lifecycle_time ON lifecycle(ride,time,id)")
        db.execSQL(
            "CREATE TABLE distance_intervals(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,source TEXT NOT NULL,start REAL NOT NULL,end REAL NOT NULL,meters REAL NOT NULL,cumulative REAL NOT NULL,covered REAL NOT NULL,from_id INTEGER NOT NULL,to_id INTEGER NOT NULL,u REAL,v REAL,PRIMARY KEY(ride,source,end)) WITHOUT ROWID"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        error("Export recordings and reinstall for this storage version.")
    }

    fun <T> transaction(body: () -> T): T {
        val db = writableDatabase
        if (db.inTransaction()) return body()
        db.beginTransactionNonExclusive()
        try {
            return body().also {
                analytics.flush()
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
            analytics.clear()
        }
    }

    fun create(options: Payload, live: Boolean = false): String {
        val id = (if (live) "live-" else "") + UUID.randomUUID()
        val metadata =
            mapOf(
                "schemaVersion" to 1,
                "id" to id,
                "startedAt" to iso(),
                "phase" to "running",
                "indoor" to options.flag("indoor"),
                "watchEnabled" to false,
                "saveToHealth" to options.flag("saveToHealth"),
                "healthProvider" to "healthConnect",
                "recordGPS" to options.flag("recordGPS"),
                "storage" to "native",
                "sport" to "cycling",
                "subSport" to if (options.flag("indoor")) "indoorCycling" else "eBiking",
                "eventCount" to 0,
                "interrupted" to false,
                "healthKitState" to "notRequested",
                "warnings" to emptyList<String>(),
                "watchSyncState" to "notRequired",
                "sampleHz" to options.num("sampleHz", 2.0),
                "example" to options.flag("example"),
            )
        writableDatabase.insertOrThrow(
            "rides",
            null,
            ContentValues().apply {
                put("id", id)
                put("started", metadata["startedAt"] as String)
                put("phase", "running")
                put("metadata", json(metadata).toString())
            },
        )
        lifecycle(id, 0.0, "start")
        return id
    }

    fun metadata(id: String): Payload =
        readableDatabase.rawQuery("SELECT * FROM rides WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) error("This ride was deleted from Power Log.")
            JSONObject(c.getString(c.getColumnIndexOrThrow("metadata"))).map() +
                mapOf("phase" to c.text("phase"), "collectionRevision" to c.long("revision"))
        }

    fun list(options: Payload): List<Payload> {
        val limit = options.num("limit", 50.0).toInt().coerceIn(1, 100)
        val cursor = options.str("beforeStartedAt")
        val args =
            if (cursor.isEmpty()) emptyArray() else arrayOf(cursor, cursor, options.str("beforeID"))
        val clause = if (cursor.isEmpty()) "" else " AND (started<? OR (started=? AND id<?))"
        return readableDatabase
            .rawQuery(
                "SELECT id FROM rides WHERE id NOT LIKE 'live-%'$clause ORDER BY started DESC,id DESC LIMIT $limit",
                args,
            )
            .use { c -> buildList { while (c.moveToNext()) add(metadata(c.getString(0))) } }
    }

    fun update(
        id: String,
        phase: String,
        elapsed: Double,
        timer: Double,
        extra: Payload = emptyMap(),
    ) {
        val metadata = metadata(id) + extra + mapOf("phase" to phase, "eventCount" to count(id))
        writableDatabase.execSQL(
            "UPDATE rides SET phase=?,elapsed=?,timer=?,metadata=?,revision=revision+1 WHERE id=?",
            arrayOf(phase, elapsed, timer, json(metadata).toString(), id),
        )
    }

    fun healthStatus(id: String, status: String) {
        val data = metadata(id) + mapOf("healthKitState" to status)
        writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(json(data).toString(), id),
        )
    }

    fun timing(id: String): Pair<Double, Double> =
        readableDatabase.rawQuery("SELECT elapsed,timer FROM rides WHERE id=?", arrayOf(id)).use { c
            ->
            check(c.moveToFirst())
            c.getDouble(0) to c.getDouble(1)
        }

    fun revision(id: String) =
        readableDatabase.rawQuery("SELECT revision FROM rides WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst())
            c.getLong(0)
        }

    fun available(id: String): List<String> =
        readableDatabase.rawQuery("SELECT metrics FROM rides WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst())
            val bits = c.getLong(0)
            storedMetrics.filterIndexed { i, _ -> bits and (1L shl i) != 0L }
        }

    fun eachChronological(id: String, body: (Observation) -> Unit) {
        var time = -1.0
        var last = 0L
        while (true) {
            val rows =
                readableDatabase
                    .rawQuery(
                        "SELECT * FROM observations WHERE ride=? AND (time>? OR (time=? AND id>?)) ORDER BY time,id LIMIT 512",
                        arrayOf(id, time.toString(), time.toString(), last.toString()),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.observation()) } }
            if (rows.isEmpty()) return
            rows.forEach(body)
            time = rows.last().time
            last = rows.last().id
        }
    }

    fun count(id: String, kind: String? = null): Long =
        readableDatabase
            .rawQuery(
                "SELECT count(*) FROM observations WHERE ride=?${if (kind == null) "" else " AND kind=?"}",
                if (kind == null) arrayOf(id) else arrayOf(id, kind),
            )
            .use { c ->
                c.moveToFirst()
                c.getLong(0)
            }

    fun lifecycle(id: String, time: Double, action: String) {
        writableDatabase.execSQL(
            "INSERT INTO lifecycle(ride,time,action) VALUES(?,?,?)",
            arrayOf(id, time, action),
        )
    }

    fun events(id: String): List<Pair<Double, String>> =
        readableDatabase
            .rawQuery(
                "SELECT time,action FROM lifecycle WHERE ride=? ORDER BY time,id",
                arrayOf(id),
            )
            .use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getString(1)) } }

    fun insert(
        ride: String,
        time: Double,
        timestamp: String,
        kind: String,
        active: Boolean,
        segment: Int,
        values: Map<String, Double>,
        identity: String = "",
        epoch: String = "",
    ): Long {
        val row =
            ContentValues().apply {
                put("ride", ride)
                put("time", time)
                put("timestamp", timestamp)
                put("kind", kind)
                put("active", if (active) 1 else 0)
                put("segment", segment)
                put("identity", identity)
                put("epoch", epoch)
                values.forEach { (key, value) ->
                    require(key in storedMetrics && value.isFinite())
                    put(key, value)
                }
            }
        val id = writableDatabase.insertOrThrow("observations", null, row)
        writableDatabase.execSQL(
            "UPDATE rides SET revision=revision+1,elapsed=max(elapsed,?),metrics=metrics|? WHERE id=?",
            arrayOf(
                time,
                values.keys.fold(0L) { bits, key -> bits or (1L shl storedMetrics.indexOf(key)) },
                ride,
            ),
        )
        analytics.changed(ride, time, values.keys)
        return id
    }

    fun remove(id: String) {
        synchronized(reads) {
            check(!reads.containsKey(id)) {
                "This ride is being exported. Try deleting it when the export finishes."
            }
            writableDatabase.delete("rides", "id=?", arrayOf(id))
        }
    }

    fun recoverOrphans() = transaction {
        writableDatabase.delete("rides", "id LIKE 'live-%'", null)
        val ids =
            readableDatabase
                .rawQuery(
                    "SELECT id FROM rides WHERE phase IN ('running','paused','finishing')",
                    null,
                )
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ids.forEach { id ->
            val (elapsed, timer) = timing(id)
            val started = java.time.Instant.parse(metadata(id).str("startedAt")).toEpochMilli()
            seal(id, elapsed, timer, iso(started + (elapsed * 1000).toLong()), true)
        }
    }

    fun seal(
        id: String,
        elapsed: Double,
        timer: Double,
        ended: String = iso(),
        interrupted: Boolean = false,
    ) = transaction {
        lifecycle(id, elapsed, "stop")
        val revision = revision(id) + 1
        update(
            id,
            "completed",
            elapsed,
            timer,
            mapOf(
                "endedAt" to ended,
                "interrupted" to interrupted,
                "sealRevision" to revision,
                "verifiedSealRevision" to revision,
                "finalizationState" to if (interrupted) "partial" else "complete",
            ),
        )
    }

    fun page(id: String, after: Long = 0, limit: Int = 512): List<Observation> =
        readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND id>? ORDER BY id LIMIT ?",
                arrayOf(id, after.toString(), limit.toString()),
            )
            .use { c -> buildList { while (c.moveToNext()) add(c.observation()) } }

    fun each(id: String, body: (Observation) -> Unit) {
        var last = 0L
        while (true) {
            val rows = page(id, last)
            if (rows.isEmpty()) return
            rows.forEach(body)
            last = rows.last().id
        }
    }

    fun Cursor.observation(): Observation =
        Observation(
            long("id"),
            double("time"),
            text("timestamp"),
            long("segment").toInt(),
            storedMetrics
                .mapNotNull { key ->
                    val i = getColumnIndexOrThrow(key)
                    if (isNull(i)) null else key to getDouble(i)
                }
                .toMap(),
            text("kind"),
            long("active") == 1L,
            text("identity"),
            text("epoch"),
        )
}

internal fun Cursor.text(key: String): String = getString(getColumnIndexOrThrow(key))

internal fun Cursor.double(key: String): Double = getDouble(getColumnIndexOrThrow(key))

internal fun Cursor.long(key: String): Long = getLong(getColumnIndexOrThrow(key))
