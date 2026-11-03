package app.powerlog.bridge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.CursorWindow
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import app.powerlog.core.analytics.ActiveTimeline
import app.powerlog.core.analytics.AnalysisPin
import app.powerlog.core.analytics.CacheIdentity
import app.powerlog.core.completion.*
import app.powerlog.core.health.*
import app.powerlog.core.lifecycle.*
import app.powerlog.core.presentation.RideFailure
import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.StorageColumns
import expo.modules.kotlin.exception.CodedException
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

internal val telemetryMetrics = StorageColumns.telemetry
internal val locationMetrics = StorageColumns.androidLocation
internal val storedMetrics = metricBits.keys.toList()

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
    val original: OriginalIdentity? = null,
    val revision: Long = 0,
    val queryUs: Long = 0,
    val elapsedUs: Long = 0,
    val evidence: Payload = emptyMap(),
)

internal fun Observation.projection(): Payload =
    evidence +
        mapOf(
            "rowId" to id.toString(),
            "revision" to revision.toString(),
            "queryUs" to queryUs.toString(),
            "elapsedUs" to elapsedUs.toString(),
        )

internal data class RideTiming(val elapsed: Double, val timer: Double, val timestamp: String)

internal data class RideEvent(
    val time: Double,
    val action: String,
    val timestamp: String,
    val interrupted: Boolean = false,
)

internal class RideStore(context: Context, name: String? = DATABASE) :
    SQLiteOpenHelper(
        context,
        name,
        null,
        6,
        DatabaseErrorHandler { db ->
            Log.e("PowerLog", "Corrupt ride database: ${db.path}")
            // Throwing here aborts SQLite's retry after the corruption callback.
            error("Power Log could not open your rides because the database is corrupt.")
        },
    ) {
    companion object {
        private const val DATABASE = "power-log.sqlite"
        @Volatile private var shared: RideStore? = null

        fun shared(context: Context): RideStore {
            val path = context.getDatabasePath(DATABASE).path
            shared
                ?.takeIf { it.path == path }
                ?.let {
                    return it
                }
            return synchronized(this) {
                shared?.takeIf { it.path == path } ?: RideStore(context.applicationContext).also { shared = it }
            }
        }

        val healthExecutorLock = Any()
        private val healthExecuting = AtomicInteger()
        val healthBusy: Boolean
            get() = healthExecuting.get() > 0

        fun <T> withHealthExecution(body: () -> T): T {
            if (healthExecuting.incrementAndGet() == 1) RecordingEngine.healthBusyChanged()
            try {
                return synchronized(healthExecutorLock, body)
            } finally {
                if (healthExecuting.decrementAndGet() == 0) RecordingEngine.healthBusyChanged()
            }
        }

        const val MAX_REVISION = 9_007_199_254_740_991L
        const val MAX_SAFE_REVISION = 9_007_199_254_740_991L

        fun nextRevision(current: Long): Long {
            if (current < 0 || current >= MAX_REVISION) throw SQLiteException("Revision exceeds the Int64 bound.")
            return current + 1
        }
    }

    private val path = name?.let { context.getDatabasePath(it).path } ?: ":memory:"
    val analytics = RideAnalytics(this)
    val energy = RideEnergyPerDistance(this)
    val summaries = RideSummaryCache(name?.let { File("$path.summaries") })
    private val writers = AtomicInteger()
    @Volatile private var conversion: CancellationSignal? = null
    internal var availableBytes: () -> Long = { File(path).parentFile?.usableSpace ?: 0 }
    internal var reclaimStepObserver: ((StorageReclaimAction) -> Unit)? = null
    internal var beforeVacuum: ((CancellationSignal) -> Unit)? = null

    fun publishSummary(pin: AnalysisPin, summary: Payload) {
        if (Thread.currentThread().isInterrupted) return
        val retained = pins.get()
        pins.remove()
        try {
            transaction {
                if (
                    !Thread.currentThread().isInterrupted &&
                        CacheIdentity.mayPublish(pin, RideAnalysis(this).pin(pin.rideId, pin.distanceSelection))
                )
                    summaries.write(pin, summary)
            }
        } finally {
            pins.set(retained)
        }
    }

    private val reads = mutableMapOf<String, Int>()

    private class TransactionState {
        val rollback = mutableListOf<() -> Unit>()
        val modified = mutableSetOf<String>()
        val healthOrigins = linkedMapOf<String, Map<String, String?>>()
        val consumptionTimelines = mutableMapOf<String, ActiveTimeline>()
    }

    fun consumptionTimeline(id: String): ActiveTimeline {
        check(writableDatabase.inTransaction())
        return requireNotNull(transactions.get()).consumptionTimelines.getOrPut(id) {
            // The open end is fixed for the batch; membership depends only on its lifecycle boundaries.
            ActiveTimeline(Long.MAX_VALUE, RideAnalysis(this).lifecycle(id))
        }
    }

    private val preparedHealthOrigins = linkedMapOf<String, Map<String, String?>>()

    private val transactions = ThreadLocal.withInitial(::TransactionState)
    private val rollback
        get() = transactions.get().rollback

    private val modified
        get() = transactions.get().modified

    private val pins = ThreadLocal<ReadPin?>()
    private val retainedPins = mutableMapOf<String, MutableMap<Long, Int>>()
    internal var queryObserver: ((String, Array<Any>) -> Unit)? = null

    internal data class ReadPin(
        val id: String,
        val revision: Long,
        val metadata: Payload,
        val timing: RideTiming,
        val metrics: List<String>,
        val highWater: Long,
        val selectionGeneration: Long,
        val analysisSelection: String = "auto",
    ) {
        var healthOrigins: Map<String, String?>? = null
    }

    fun healthOrigins(id: String, compute: () -> Map<String, String?>): Map<String, String?> {
        val pin = pins.get()?.takeIf { it.id == id }
        pin?.healthOrigins?.let {
            return it
        }
        val identity = CacheIdentity.of(RideAnalysis(this).pin(id, pin?.analysisSelection ?: "auto"))
        val retained =
            if (pin == null && readableDatabase.inTransaction()) transactions.get().healthOrigins
            else preparedHealthOrigins
        synchronized(retained) {
            retained[identity]?.let {
                pin?.healthOrigins = it
                return it
            }
        }
        val origins = compute()
        check(!Thread.currentThread().isInterrupted)
        synchronized(retained) {
            if (retained.size >= 16 && identity !in retained) retained.remove(retained.keys.first())
            retained[identity] = origins
        }
        pin?.healthOrigins = origins
        return origins
    }

    fun pin(id: String, analysisSelection: String = "auto"): ReadPin = transaction {
        val revision = revision(id)
        val pin =
            ReadPin(
                id,
                revision,
                metadata(id),
                timing(id),
                available(id),
                select("SELECT observation_high_water FROM rides WHERE id=?", arrayOf(id)) {
                    it.moveToFirst()
                    it.getLong(0)
                },
                distanceSelection(id).generation,
                analysisSelection,
            )
        if (revision(id) != revision)
            throw CodedException(
                "ERR_READ_CONTENTION",
                "Recording changed during pin admission.",
                null,
            )
        synchronized(reads) {
            val retained = retainedPins.getOrPut(id) { mutableMapOf() }
            retained[revision] = (retained[revision] ?: 0) + 1
            val previous = reads[id] ?: 0
            reads[id] = previous + 1
            if (previous == 0) RecordingEngine.workChanged()
        }
        onRollback { releasePin(pin) }
        pin
    }

    fun <T> withPin(pin: ReadPin, body: () -> T): T {
        check(pins.get() == null)
        try {
            fun checkSelection() {
                if (distanceSelection(pin.id).generation != pin.selectionGeneration)
                    throw CodedException(
                        "ERR_READ_CONTENTION",
                        "Distance selection changed during the read.",
                        null,
                    )
            }
            checkSelection()
            pins.set(pin)
            val result = body()
            checkSelection()
            return result
        } finally {
            pins.remove()
            releasePin(pin)
        }
    }

    fun <T> withReadPin(id: String, analysisSelection: String = "auto", body: () -> T): T {
        val active = pins.get()
        if (active != null) {
            check(active.id == id)
            return body()
        }
        return withPin(pin(id, analysisSelection), body)
    }

    private fun releasePin(pin: ReadPin) =
        synchronized(reads) {
            val retained = retainedPins.getValue(pin.id)
            val count = retained.getValue(pin.revision) - 1
            if (count == 0) retained.remove(pin.revision) else retained[pin.revision] = count
            if (retained.isEmpty()) retainedPins.remove(pin.id)
            release(pin.id)
        }

    fun readRevision(id: String): Long =
        pins.get()?.takeIf { it.id == id }?.revision
            ?: select("SELECT revision FROM rides WHERE id=?", arrayOf(id)) { c ->
                if (c.moveToFirst()) c.getLong(0) else MAX_REVISION
            }

    fun onRollback(body: () -> Unit) {
        check(writableDatabase.inTransaction())
        rollback.add(body)
    }

    private fun changed(id: String) {
        modified.add(id)
    }

    fun <T> withSavedRide(id: String, body: () -> T): T {
        retain(id)
        try {
            return body()
        } finally {
            release(id)
        }
    }

    fun retain(id: String) = transaction {
        if (!exists(id)) throw RideFailureException(RideFailure.rideDeleted)
        synchronized(reads) {
            val previous = reads[id] ?: 0
            reads[id] = previous + 1
            if (previous == 0) RecordingEngine.workChanged()
        }
        onRollback { release(id) }
    }

    fun release(id: String) =
        synchronized(reads) {
            val count = reads.getValue(id) - 1
            if (count == 0) {
                reads.remove(id)
                RecordingEngine.workChanged()
            } else reads[id] = count
        }

    // The factory binds each argument with its own SQLite type; rawQuery would bind every argument
    // as text.
    fun <T> select(sql: String, args: Array<Any>, read: (Cursor) -> T): T = query(sql, args, null, read)

    private fun <T> healthSelect(sql: String, args: Array<Any>, read: (Cursor) -> T): T = query(sql, args, null, read)

    private fun <T> query(
        sql: String,
        args: Array<Any>,
        windowBytes: Long?,
        read: (Cursor) -> T,
    ): T {
        queryObserver?.invoke(sql, args)
        return readableDatabase
            .rawQueryWithFactory(
                { _, driver, table, query ->
                    args.forEachIndexed { index, value ->
                        when (value) {
                            is String -> query.bindString(index + 1, value)
                            is Double -> query.bindDouble(index + 1, value)
                            is Long -> query.bindLong(index + 1, value)
                            is Int -> query.bindLong(index + 1, value.toLong())
                            else -> throw IllegalArgumentException("Unsupported query argument")
                        }
                    }
                    SQLiteCursor(driver, table, query).apply {
                        if (windowBytes != null) window = CursorWindow("Health publication", windowBytes)
                    }
                },
                sql,
                null,
                "",
            )
            .use(read)
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun close() {
        synchronized(Companion) { if (shared === this) shared = null }
        super.close()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
        db.rawQuery("PRAGMA wal_autocheckpoint=1000", null).use { it.moveToFirst() }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        for (table in
            listOf("lifecycle_selection", "lifecycle_states", "lifecycle_intents", "health_imported_records")) {
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                check(it.moveToFirst()) {
                    "Power Log can't open the earlier ride store shape. Reinstall the app to start over."
                }
            }
        }
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='publication_records'", null).use {
            check(!it.moveToFirst()) {
                "Power Log cannot open the earlier Health store. Reinstall the app to start over."
            }
        }
        db.rawQuery(
                "SELECT 1 FROM rides WHERE json_type(metadata,'$.healthKitState') IS NOT NULL OR json_type(metadata,'$.healthExport') IS NOT NULL OR json_type(metadata,'$.healthReason') IS NOT NULL OR json_type(metadata,'$.componentResults') IS NOT NULL OR json_type(metadata,'$.exportEligible') IS NOT NULL OR json_type(metadata,'$.healthComponents') IS NOT 'array' LIMIT 1",
                null,
            )
            .use {
                check(!it.moveToFirst()) {
                    "Power Log can't open the earlier Health evidence shape. Reinstall the app to start over."
                }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'",
                null,
            )
            .use {
                check(!it.moveToFirst()) {
                    "Power Log can't open an unversioned ride database. Reinstall the app to start over."
                }
            }
        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        db.execSQL(
            "CREATE TABLE analytics(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,metric TEXT NOT NULL,bucket INTEGER NOT NULL,revision INTEGER NOT NULL,first REAL NOT NULL,last REAL NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(ride,metric,bucket,revision)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE analytics_plot_blocks(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,metric TEXT NOT NULL,bucket INTEGER NOT NULL,revision INTEGER NOT NULL,payload TEXT,PRIMARY KEY(ride,metric,bucket,revision)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE rides(id TEXT PRIMARY KEY, started TEXT NOT NULL, phase TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND $MAX_REVISION AND typeof(revision)='integer'), elapsed REAL NOT NULL DEFAULT 0, timer REAL NOT NULL DEFAULT 0, checkpoint_at TEXT NOT NULL, metrics INTEGER NOT NULL DEFAULT 0,original_membership_count INTEGER NOT NULL DEFAULT 0,observation_count INTEGER NOT NULL DEFAULT 0,observation_high_water INTEGER NOT NULL DEFAULT 0, metadata TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE catalog_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL CHECK(revision>=0 AND typeof(revision)='integer'),last_deleted_workout_id TEXT)"
        )
        db.execSQL("INSERT INTO catalog_state VALUES(1,0,NULL)")
        db.execSQL(
            "CREATE TABLE installation_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),endpoint_id TEXT NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO installation_state VALUES(1,?)",
            arrayOf(UUID.randomUUID().toString()),
        )
        db.execSQL(
            "CREATE TABLE distance_selection(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,phone_endpoint_id TEXT NOT NULL,selection TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),PRIMARY KEY(ride,phone_endpoint_id)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE lifecycle_selection(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL)"
        )
        db.execSQL("INSERT INTO lifecycle_selection VALUES(1,?)", arrayOf(LifecycleCodec.encodeState(LifecycleState())))
        db.execSQL(
            "CREATE TABLE lifecycle_states(ride TEXT PRIMARY KEY REFERENCES rides(id) ON DELETE CASCADE,payload TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE lifecycle_intents(command_id TEXT PRIMARY KEY,ride TEXT NOT NULL,payload TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX lifecycle_intents_ride ON lifecycle_intents(ride)")
        db.execSQL("CREATE INDEX rides_catalog ON rides(started DESC,id DESC)")
        db.execSQL(
            "CREATE TABLE observations(id INTEGER PRIMARY KEY, ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE, time REAL NOT NULL, timestamp TEXT NOT NULL, kind TEXT NOT NULL, active INTEGER NOT NULL, segment INTEGER NOT NULL, identity TEXT NOT NULL, epoch TEXT NOT NULL, ${(telemetryMetrics + locationMetrics + listOf("heartRateBpm", "activeEnergyKcal")).joinToString { "$it REAL" }},original_altitude REAL,original_vertical_accuracy REAL,original_altitude_datum TEXT,event_id TEXT NOT NULL,producer TEXT NOT NULL,producer_sequence INTEGER NOT NULL,revision INTEGER NOT NULL,query_us INTEGER NOT NULL,elapsed_us INTEGER NOT NULL,evidence TEXT NOT NULL,UNIQUE(ride,event_id),UNIQUE(ride,producer,producer_sequence))"
        )
        db.execSQL("CREATE INDEX observations_battery_replay ON observations(ride,id) WHERE kind='telemetry'")
        db.execSQL("CREATE INDEX observations_time ON observations(ride,time,id)")
        db.execSQL("CREATE INDEX observations_kind ON observations(ride,kind,time,id)")
        db.execSQL("CREATE INDEX observations_elapsed_id ON observations(ride,elapsed_us,id)")
        db.execSQL("CREATE INDEX observations_health_second ON observations(ride,kind,query_us)")
        db.execSQL("CREATE INDEX observations_health_source ON observations(ride,revision) WHERE kind!='health'")
        db.execSQL(
            "CREATE INDEX observations_health_route ON observations(ride,query_us,event_id) WHERE kind='location' AND producer='phone'"
        )
        db.execSQL(
            "CREATE INDEX observations_kind_health_origin ON observations(ride,json_extract(evidence,'$.origin.dataOrigin')) WHERE kind='health' AND json_extract(evidence,'$.measure')='heartRate' AND json_extract(evidence,'$.representation') IN ('rawQuantity','rawSeries')"
        )
        db.execSQL(
            "CREATE INDEX observations_elapsed_health_measure ON observations(ride,json_extract(evidence,'$.measure'),elapsed_us,producer,producer_sequence) WHERE kind='health'"
        )
        db.execSQL(
            "CREATE INDEX observations_health_readback ON observations(ride,CASE WHEN json_extract(evidence,'$.representation')='workoutAssociation' THEN json_extract(evidence,'$.parentMetadata.queryMeasure') ELSE json_extract(evidence,'$.measure') END) WHERE kind='health'"
        )
        db.execSQL(
            "CREATE TABLE lifecycle(id INTEGER PRIMARY KEY,ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,time REAL NOT NULL,action TEXT NOT NULL,timestamp TEXT NOT NULL,event_id TEXT NOT NULL,producer TEXT NOT NULL,producer_sequence INTEGER NOT NULL,revision INTEGER NOT NULL,query_us INTEGER NOT NULL,elapsed_us INTEGER NOT NULL,interrupted INTEGER NOT NULL,evidence TEXT NOT NULL,UNIQUE(ride,event_id),UNIQUE(ride,producer,producer_sequence))"
        )
        db.execSQL("CREATE INDEX lifecycle_time ON lifecycle(ride,time,id)")
        db.execSQL("CREATE INDEX lifecycle_health_order ON lifecycle(ride,elapsed_us)")
        db.execSQL("CREATE INDEX lifecycle_health_revision ON lifecycle(ride,revision)")
        db.execSQL("CREATE INDEX lifecycle_health_phase ON lifecycle(ride,elapsed_us) WHERE action!='lap'")
        db.execSQL(
            "CREATE INDEX lifecycle_health_gaps ON lifecycle(ride,query_us,revision) WHERE action='pause' AND json_type(evidence,'$.gapStartUs')='text'"
        )
        db.execSQL(
            "CREATE TABLE distance_intervals(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,source TEXT NOT NULL,start_us INTEGER NOT NULL,end_us INTEGER NOT NULL,meters_q INTEGER NOT NULL,cumulative_q INTEGER NOT NULL,covered_us INTEGER NOT NULL,original_meters REAL NOT NULL,from_id INTEGER NOT NULL,to_id INTEGER NOT NULL,u REAL,v REAL,segment INTEGER NOT NULL,plot_segment INTEGER NOT NULL,indivisible INTEGER NOT NULL,revision INTEGER NOT NULL,start_anchor TEXT NOT NULL,end_anchor TEXT NOT NULL,PRIMARY KEY(ride,source,end_us,to_id,revision)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE battery_state(ride TEXT PRIMARY KEY REFERENCES rides(id) ON DELETE CASCADE,row_id INTEGER NOT NULL,payload TEXT NOT NULL) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE battery_intervals(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,metric TEXT NOT NULL,start_us INTEGER NOT NULL,end_us INTEGER NOT NULL,start_value REAL NOT NULL,end_value REAL NOT NULL,rate0 REAL,rate1 REAL,indivisible INTEGER NOT NULL,segment INTEGER NOT NULL,active_interval INTEGER NOT NULL,baseline INTEGER NOT NULL,from_id INTEGER NOT NULL,to_id INTEGER NOT NULL,covered_us INTEGER NOT NULL,revision INTEGER NOT NULL,retired_revision INTEGER,PRIMARY KEY(ride,metric,end_us,to_id,revision)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE battery_checkpoints(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,bucket INTEGER NOT NULL,row_id INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(ride,bucket)) WITHOUT ROWID"
        )
        db.execSQL("CREATE INDEX battery_original ON battery_intervals(ride,metric,to_id,revision)")
        db.execSQL("CREATE INDEX battery_retirement ON battery_intervals(ride,retired_revision,to_id)")
        db.execSQL("CREATE INDEX distance_revision ON distance_intervals(ride,source,revision,end_us,to_id)")
        db.execSQL("CREATE INDEX distance_original ON distance_intervals(ride,source,to_id,revision,cumulative_q)")
        db.execSQL(
            "CREATE TABLE ride_versions(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,revision INTEGER NOT NULL,record TEXT NOT NULL,PRIMARY KEY(ride,revision)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE health_samples(observation_id INTEGER PRIMARY KEY REFERENCES observations(id) ON DELETE CASCADE,provider TEXT NOT NULL,data_origin TEXT NOT NULL,provider_parent_id TEXT,measure TEXT NOT NULL,unit TEXT NOT NULL,representation TEXT NOT NULL,member_index INTEGER,start_timestamp TEXT NOT NULL,end_timestamp TEXT NOT NULL,origin_revision TEXT)"
        )
        db.execSQL("CREATE INDEX health_origin ON health_samples(provider,data_origin,provider_parent_id,member_index)")
        db.execSQL("CREATE INDEX health_parent_range ON health_samples(data_origin,provider_parent_id,member_index)")
        db.execSQL(
            "CREATE INDEX health_parent_association ON health_samples(data_origin,provider_parent_id,representation)"
        )
        db.execSQL(
            "CREATE TABLE health_changes(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,revision INTEGER NOT NULL,target_event_id TEXT NOT NULL,original_event_id TEXT NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(ride,original_event_id)) WITHOUT ROWID"
        )
        db.execSQL("CREATE INDEX health_changes_target ON health_changes(ride,target_event_id,revision)")
        db.execSQL(
            "CREATE TABLE health_query_progress(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,provider TEXT NOT NULL,measure TEXT NOT NULL,scope TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(ride,provider,measure,scope)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE health_quota_waits(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,scope TEXT NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(ride,scope)) WITHOUT ROWID"
        )
        db.execSQL("CREATE TABLE publication_jobs(ride TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE health_imported_records(ride TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,data_origin TEXT NOT NULL,provider_id TEXT NOT NULL,PRIMARY KEY(ride,data_origin,provider_id)) WITHOUT ROWID"
        )
        db.execSQL(
            "CREATE TABLE publication_budget(bucket TEXT PRIMARY KEY,payload TEXT NOT NULL,wall_us INTEGER NOT NULL,elapsed_us INTEGER NOT NULL,deadline_shift_us INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE health_read_deferrals(ride TEXT PRIMARY KEY REFERENCES rides(id) ON DELETE CASCADE,token TEXT NOT NULL)"
        )
        for (table in listOf("observations", "lifecycle")) {
            db.execSQL("CREATE INDEX ${table}_revision ON $table(ride,revision,id)")
            db.execSQL("CREATE INDEX ${table}_elapsed ON $table(ride,elapsed_us,producer,producer_sequence,id)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        error("Power Log can't open rides saved by an earlier version. Reinstall the app to start over.")
    }

    override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
        error("Update Power Log to open your rides")
    }

    fun <T> transaction(body: () -> T): T {
        val readPin = pins.get()
        pins.remove()
        try {
            return writeTransaction(body)
        } catch (error: SQLiteDatabaseLockedException) {
            throw CodedException(
                "ERR_STORAGE_BUSY",
                "Storage is busy. Retry the pending operation.",
                error,
            )
        } finally {
            if (readPin != null) pins.set(readPin)
        }
    }

    private fun <T> writeTransaction(body: () -> T): T {
        val db = writableDatabase
        if (db.inTransaction()) return body()
        // Paired with convert(): a conversion either sees this writer and does not start, or is cancelled here.
        writers.incrementAndGet()
        try {
            conversion?.cancel()
            db.beginTransactionNonExclusive()
        } finally {
            writers.decrementAndGet()
        }
        var committed = false
        try {
            val result = body()
            analytics.flush()
            modified.forEach { id ->
                db.rawQuery("SELECT id FROM rides WHERE id=?", arrayOf(id)).use { c ->
                    if (c.moveToFirst())
                        db.execSQL(
                            "INSERT OR REPLACE INTO ride_versions(ride,revision,record) VALUES(?,?,?)",
                            arrayOf(
                                id,
                                revision(id),
                                json(
                                        storedRecord(id).payload() +
                                            mapOf(
                                                "phase" to metadata(id).str("phase"),
                                                "collectionRevision" to revision(id).toString(),
                                                "elapsedSeconds" to timing(id).elapsed,
                                            ) +
                                            mapOf(
                                                "timerSeconds" to timing(id).timer,
                                                "checkpointAt" to timing(id).timestamp,
                                                "availableMetrics" to available(id),
                                            )
                                    )
                                    .toString(),
                            ),
                        )
                }
            }
            for (id in modified) {
                val keep = synchronized(reads) { retainedPins[id]?.keys?.toList().orEmpty() } + readRevision(id)
                db.execSQL(
                    "DELETE FROM battery_intervals WHERE ride=? AND retired_revision<=?",
                    arrayOf(id, keep.min()),
                )
                db.execSQL(
                    "DELETE FROM ride_versions WHERE ride=? AND revision NOT IN (${keep.joinToString()})",
                    arrayOf(id),
                )
            }
            RecordingTransactions.beforeCommit(this)
            db.setTransactionSuccessful()
            db.endTransaction()
            committed = true
            RecordingTransactions.afterCommit(this)
            return result
        } finally {
            try {
                if (db.inTransaction()) db.endTransaction()
                if (!committed) rollback.asReversed().forEach { it() }
            } finally {
                transactions.remove()
                analytics.clear()
            }
        }
    }

    data class Reclaim(
        val bytesBefore: Long,
        val bytesAfter: Long,
        val steps: Int,
        val converted: Boolean,
        val deferred: StorageReclaimAction?,
        val failure: Exception?,
    )

    val fileBytes: Long
        get() = File(path).length() + File("$path-wal").length()

    fun reclaimSpace(): Reclaim {
        val before = fileBytes
        var steps = 0
        var converted = false
        var deferred: StorageReclaimAction? = null
        var failure: Exception? = null
        try {
            while (true) {
                val action = reclaimStep()
                reclaimStepObserver?.invoke(action)
                when (action) {
                    StorageReclaimAction.reclaim -> steps++
                    StorageReclaimAction.enableIncremental,
                    StorageReclaimAction.convert -> converted = true
                    StorageReclaimAction.done -> break
                    else -> {
                        deferred = action
                        break
                    }
                }
            }
        } catch (error: Exception) {
            failure = error
        }
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (error: Exception) {
            failure = failure ?: error
        }
        val outcome = Reclaim(before, fileBytes, steps, converted, deferred, failure)
        val summary =
            "Storage reclaim: $before to ${outcome.bytesAfter} bytes in $steps steps, converted $converted, " +
                "deferred ${deferred?.name ?: "no"}"
        if (failure == null) Log.i("PowerLog", summary) else Log.w("PowerLog", summary, failure)
        return outcome
    }

    private fun reclaimStep(): StorageReclaimAction {
        val action = immediate { db ->
            val step =
                StorageReclaim.next(
                    pragma(db, "auto_vacuum"),
                    pragma(db, "page_size"),
                    pragma(db, "page_count"),
                    pragma(db, "freelist_count"),
                    db.rawQuery(
                            "SELECT 1 FROM rides WHERE id NOT LIKE 'live-%' AND phase IN (${StorageReclaim.recordingPhases.joinToString { "'$it'" }}) LIMIT 1",
                            null,
                        )
                        .use { it.moveToFirst() },
                    availableBytes(),
                )
            when (step.action) {
                // Each freed page yields one result row; the pragma frees pages only while it is stepped.
                StorageReclaimAction.reclaim ->
                    check(db.rawQuery("PRAGMA incremental_vacuum(${step.pages})", null).use { it.count } > 0) {
                        "Incremental vacuum made no progress."
                    }
                StorageReclaimAction.enableIncremental -> db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                else -> {}
            }
            step.action
        }
        if (action == StorageReclaimAction.convert && !convert()) return StorageReclaimAction.waitForIdle
        if (action == StorageReclaimAction.enableIncremental || action == StorageReclaimAction.convert)
            check(immediate { pragma(it, "auto_vacuum") } == 2L) { "The store did not switch to incremental vacuum." }
        return action
    }

    private fun convert(): Boolean {
        val signal = CancellationSignal()
        conversion = signal
        try {
            if (writers.get() > 0) return false
            beforeVacuum?.invoke(signal)
            // The pragma acts while it is prepared, which Android does on the primary connection that runs VACUUM.
            writableDatabase.rawQuery("PRAGMA auto_vacuum=INCREMENTAL", null, signal).use { it.count }
            writableDatabase.rawQuery("VACUUM", null, signal).use { it.count }
            return true
        } catch (_: OperationCanceledException) {
            return false
        } finally {
            conversion = null
        }
    }

    private fun <T> immediate(body: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            return body(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun pragma(db: SQLiteDatabase, name: String): Long =
        db.rawQuery("PRAGMA $name", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    fun create(
        options: RideOptions,
        reading: RecordingClockReading,
        live: Boolean = false,
        example: Boolean = false,
        id: String = (if (live) "live-" else "") + UUID.randomUUID(),
        initialPhase: String = "running",
        startEvent: Boolean = true,
    ): String = transaction {
        val metadata =
            RideRecord(
                    id,
                    reading.timestamp,
                    options.indoor,
                    options.saveToHealth,
                    options.recordGPS,
                    options.sampleHz,
                    example,
                    localEndpoint(),
                    distanceSource =
                        if (options.distanceSource == "auto") {
                            if (options.recordGPS && !options.indoor) "gps:phone" else "controller"
                        } else options.distanceSource,
                    batteryConsumptionSource = options.batteryConsumptionSource,
                )
                .payload()
        writableDatabase.insertOrThrow(
            "rides",
            null,
            ContentValues().apply {
                put("id", id)
                put("started", reading.timestamp)
                put("checkpoint_at", reading.timestamp)
                put("phase", initialPhase)
                put("metadata", json(metadata).toString())
            },
        )
        writableDatabase.execSQL(
            "INSERT INTO distance_selection VALUES(?,?,?,0)",
            arrayOf(id, localEndpoint(), metadata.str("distanceSource")),
        )
        changed(id)
        if (startEvent) lifecycle(id, RideTiming(0.0, 0.0, reading.timestamp), "start")
        if (!live) setCatalogRevision(nextCatalogRevision())
        id
    }

    fun metadata(id: String): Payload {
        pins
            .get()
            ?.takeIf { it.id == id }
            ?.let {
                return it.metadata
            }
        return readableDatabase.rawQuery("SELECT * FROM rides WHERE id=?", arrayOf(id)).use { c ->
            c.requireRide()
            val stored = RideRecord.read(JSONObject(c.text("metadata")).map()).payload()
            val record = RideRecord.read(stored)
            val owner = healthOwner(id, record)
            stored +
                mapOf(
                    "phase" to c.text("phase"),
                    "collectionRevision" to c.long("revision").toString(),
                    "elapsedSeconds" to c.double("elapsed"),
                    "eventCount" to originalMembershipCount(id).toString(),
                    "healthKitState" to owner.outcome.name,
                    "healthReason" to owner.reason?.name,
                    "healthRetryAvailable" to
                        (record.saveToHealth &&
                            healthJob(id)?.let {
                                it.status == "failed" &&
                                    it.reason in setOf("permission", "quota", "locked", "transientFailure")
                            } == true),
                    "exportEligible" to true,
                    "componentResults" to
                        record.healthComponents.map { JSONObject(HealthCodec.encodeComponentResult(it)).map() },
                    "physiologyProvenance" to "backfilled",
                )
        }
    }

    fun list(options: CatalogInput): List<Payload> {
        val limit = options.limit
        val cursor = options.cursor
        val args = if (cursor == null) emptyArray() else arrayOf(cursor.startedAt, cursor.startedAt, cursor.id)
        val clause = if (cursor == null) "" else " AND (started<? OR (started=? AND id<?))"
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
        timing: RideTiming,
        extra: Payload = emptyMap(),
    ) = transaction {
        val stored = storedRecord(id)
        val frozen = stored.payload()
        for (key in
            listOf(
                "id",
                "startedAt",
                "sampleHz",
                "distanceSource",
                "batteryConsumptionSource",
                "indoor",
                "saveToHealth",
                "recordGPS",
                "watchEnabled",
                "recordingOwner",
                "ownerDevice",
                "ownerEndpointId",
                "healthPublisher",
                "publisherEndpointId",
                "healthProvider",
                "storage",
                "example",
            )) {
            if (key in extra) {
                val value = extra[key]
                val matches =
                    if (key == "sampleHz") value is Number && value.toDouble() == stored.sampleHz.toDouble()
                    else value == frozen[key]
                require(matches) { "The ride's $key is frozen." }
            }
        }
        val fields = stored.payload() + extra
        val metadata = RideRecord.read(fields).payload()
        val revision = nextRevision(revision(id))
        changed(id)
        writableDatabase.execSQL(
            "UPDATE rides SET phase=?,elapsed=?,timer=?,checkpoint_at=?,metadata=?,revision=? WHERE id=?",
            arrayOf(
                phase,
                timing.elapsed,
                timing.timer,
                timing.timestamp,
                json(metadata).toString(),
                revision,
                id,
            ),
        )
    }

    fun healthComponents(id: String): List<ComponentResult> = storedRecord(id).healthComponents

    fun healthStatus(id: String, result: HealthExportResult) = transaction {
        if (!exists(id)) return@transaction
        val previous = storedRecord(id)
        if (previous.healthComponents != result.components) {
            changed(id)
            writableDatabase.execSQL(
                "UPDATE rides SET metadata=?,revision=revision+1 WHERE id=?",
                arrayOf(json(previous.copy(healthComponents = result.components).payload()).toString(), id),
            )
            setCatalogRevision(nextCatalogRevision())
        }
    }

    private fun healthOwner(id: String, record: RideRecord): OwnerHealth {
        if (!record.saveToHealth) return OwnerHealth(HealthOutcome.notRequested, null)
        val job = healthJob(id)
        val reason = job?.reason?.let { value -> HealthReason.entries.firstOrNull { it.name == value } }
        val outcome =
            when (job?.status) {
                "saved" -> HealthOutcome.saved
                "failed" -> HealthOutcome.failed
                "uncertain",
                "submitted" -> HealthOutcome.unknown
                else ->
                    if (
                        record.healthComponents.any {
                            it.component == Component.workout &&
                                it.state in setOf(ComponentState.failed, ComponentState.unavailable)
                        }
                    )
                        HealthOutcome.failed
                    else HealthOutcome.pending
            }
        return OwnerHealth(
            outcome,
            reason ?: record.healthComponents.firstOrNull { it.component == Component.workout }?.reason,
        )
    }

    fun completion(id: String): RideCompletionSnapshot =
        CompletionCodec.decodeSnapshot(json(metadata(id) + ("id" to id)).toString())

    fun canExport(id: String, pinRevision: Long): Boolean = RideCompletion.openExport(completion(id), id, pinRevision)

    fun lifecycleRecovering(id: String): Boolean =
        select("SELECT payload FROM lifecycle_states WHERE ride=?", arrayOf(id)) {
            if (!it.moveToFirst()) false
            else
                LifecycleCodec.decodeState(it.getString(0)).ride?.let { owner ->
                    owner.phase == LifecyclePhase.interrupted ||
                        owner.storageInterrupted ||
                        owner.recovery == LifecycleRecovery.checking
                } == true
        }

    val readWorkoutIds: List<String>
        get() = synchronized(reads) { reads.keys.filterNot { it.startsWith("live-") }.sorted() }

    fun hasReaders(id: String): Boolean = synchronized(reads) { reads.containsKey(id) }

    fun localEndpoint(): String =
        select("SELECT endpoint_id FROM installation_state WHERE singleton=1", emptyArray()) {
            it.moveToFirst()
            it.getString(0)
        }

    data class DistanceSelection(val selection: String, val generation: Long)

    fun distanceSelection(id: String): DistanceSelection =
        select(
            "SELECT selection,generation FROM distance_selection WHERE ride=? AND phone_endpoint_id=?",
            arrayOf(id, localEndpoint()),
        ) { c ->
            check(c.moveToFirst())
            DistanceSelection(c.getString(0), c.getLong(1))
        }

    fun selectDistance(id: String, selection: DistanceSource): DistanceSelection {
        val current = distanceSelection(id)
        require(selection.wire == current.selection || selection == DistanceSource.Auto) {
            "Distance source is frozen at Start."
        }
        return current
    }

    fun catalogState(): CatalogState =
        select(
            "SELECT revision,last_deleted_workout_id FROM catalog_state WHERE singleton=1",
            emptyArray(),
        ) { c ->
            check(c.moveToFirst())
            CatalogState(c.getLong(0), c.getString(1))
        }

    fun nextCatalogRevision(): Long = Math.addExact(catalogState().revision, 1)

    fun setCatalogRevision(revision: Long) = transaction {
        require(revision >= catalogState().revision && revision >= 0)
        writableDatabase.execSQL(
            "UPDATE catalog_state SET revision=? WHERE singleton=1",
            arrayOf(revision),
        )
    }

    fun pendingHealthJobs(includeBlocked: Boolean = false): List<String> =
        healthSelect("SELECT ride,payload FROM publication_jobs", emptyArray()) { c ->
            buildList {
                while (c.moveToNext()) {
                    val job = AndroidHealthJob.decode(c.getString(1))
                    if (job.status == "ready" || job.status == "submitted" || includeBlocked) add(c.getString(0))
                }
            }
        }

    fun timing(id: String): RideTiming {
        pins
            .get()
            ?.takeIf { it.id == id }
            ?.let {
                return it.timing
            }
        return readableDatabase.rawQuery("SELECT elapsed,timer,checkpoint_at FROM rides WHERE id=?", arrayOf(id)).use {
            c ->
            c.requireRide()
            RideTiming(c.getDouble(0), c.getDouble(1), c.getString(2))
        }
    }

    private fun storedRecord(id: String): RideRecord =
        readableDatabase.rawQuery("SELECT metadata FROM rides WHERE id=?", arrayOf(id)).use { c ->
            c.requireRide()
            RideRecord.read(JSONObject(c.getString(0)).map())
        }

    fun revision(id: String) =
        readableDatabase.rawQuery("SELECT revision FROM rides WHERE id=?", arrayOf(id)).use { c ->
            c.requireRide()
            c.getLong(0)
        }

    fun available(id: String): List<String> {
        pins
            .get()
            ?.takeIf { it.id == id }
            ?.let {
                return it.metrics
            }
        return readableDatabase.rawQuery("SELECT metrics FROM rides WHERE id=?", arrayOf(id)).use { c ->
            c.requireRide()
            val bits = c.getLong(0)
            metricBits.filterValues { bits and (1L shl it) != 0L }.keys.toList()
        }
    }

    fun originalMembershipCount(id: String): Long =
        pins.get()?.takeIf { it.id == id }?.metadata?.get("eventCount")?.let { (it as String).toLong() }
            ?: select("SELECT original_membership_count FROM rides WHERE id=?", arrayOf(id)) { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }

    fun selectedEventCount(id: String): Long = selectedCount(id) + lifecycleCount(id)

    fun lifecycleCount(id: String): Long =
        select(
            "SELECT count(*) FROM lifecycle WHERE ride=? AND revision<=?",
            arrayOf(id, readRevision(id)),
        ) {
            it.moveToFirst()
            it.getLong(0)
        }

    fun selectedCount(id: String, kind: String? = null): Long {
        require(kind == null || kind in setOf("telemetry", "location", "health"))
        return select(
            "SELECT count(*) FROM observations o WHERE o.ride=? AND o.revision<=? ${if (kind == null) "" else "AND o.kind='$kind'"} ${selectedPredicate("o")}",
            arrayOf(id, readRevision(id)),
        ) {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    fun count(id: String, kind: String? = null): Long {
        if (kind == null && pins.get() == null)
            return select("SELECT observation_count FROM rides WHERE id=?", arrayOf(id)) { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        return readableDatabase
            .rawQuery(
                "SELECT count(*) FROM observations WHERE ride=? AND revision<=${pins.get()?.takeIf { it.id == id }?.revision ?: MAX_REVISION}${if (kind == null) "" else " AND kind=?"}",
                if (kind == null) arrayOf(id) else arrayOf(id, kind),
            )
            .use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
    }

    fun nextOriginal(
        id: String,
        producer: String,
        eventId: String = UUID.randomUUID().toString(),
    ): OriginalIdentity = OriginalIdentity(eventId, producer, Math.addExact(producerHighWater(id, producer), 1))

    fun producerHighWater(id: String, producer: String): Long =
        select(
            "SELECT coalesce(max(sequence),0) FROM (SELECT max(producer_sequence) AS sequence FROM observations WHERE ride=? AND producer=? UNION ALL SELECT max(producer_sequence) FROM lifecycle WHERE ride=? AND producer=?)",
            arrayOf(id, producer, id, producer),
        ) {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun existingOriginal(
        ride: String,
        original: OriginalIdentity,
        table: String,
        content: String,
    ): Long? =
        select(
            "SELECT id,event_id,producer,producer_sequence,evidence,kind FROM observations WHERE ride=? AND (event_id=? OR (producer=? AND producer_sequence=?)) UNION ALL SELECT id,event_id,producer,producer_sequence,evidence,'lifecycle' FROM lifecycle WHERE ride=? AND (event_id=? OR (producer=? AND producer_sequence=?))",
            arrayOf(
                ride,
                original.eventId,
                original.producer,
                original.sequence,
                ride,
                original.eventId,
                original.producer,
                original.sequence,
            ),
        ) { c ->
            if (!c.moveToFirst()) null
            else {
                require(
                    c.getString(1) == original.eventId &&
                        c.getString(2) == original.producer &&
                        c.getLong(3) == original.sequence &&
                        c.getString(4) == content &&
                        (c.getString(5) == "lifecycle") == (table == "lifecycle")
                ) {
                    "Original identity conflicts with committed content."
                }
                c.getLong(0)
            }
        }

    fun lifecycle(
        id: String,
        timing: RideTiming,
        action: String,
        interrupted: Boolean = false,
        original: OriginalIdentity? = null,
        evidence: Payload = emptyMap(),
    ) = transaction {
        require(action in setOf("start", "pause", "resume", "lap", "stop"))
        val identity = original ?: nextOriginal(id, "phone")
        val content =
            json(
                    evidence +
                        identity.payload() +
                        mapOf(
                            "time" to timing.elapsed,
                            "timestamp" to timing.timestamp,
                            "action" to action,
                            "interrupted" to interrupted,
                        )
                )
                .toString()
        require(content.toByteArray(Charsets.UTF_8).size <= 32768) {
            "Lifecycle observation exceeds 32 KiB."
        }
        if (existingOriginal(id, identity, "lifecycle", content) != null) return@transaction
        val revision = nextRevision(revision(id))
        writableDatabase.execSQL(
            "INSERT INTO lifecycle(ride,time,action,timestamp,event_id,producer,producer_sequence,revision,query_us,elapsed_us,interrupted,evidence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                id,
                timing.elapsed,
                action,
                timing.timestamp,
                identity.eventId,
                identity.producer,
                identity.sequence,
                revision,
                timeUs(timing.timestamp),
                elapsedUs(timing.elapsed),
                if (interrupted) 1 else 0,
                content,
            ),
        )
        writableDatabase.execSQL(
            "UPDATE rides SET revision=?,original_membership_count=original_membership_count+1 WHERE id=?",
            arrayOf(revision, id),
        )
        requireNotNull(transactions.get()).consumptionTimelines.remove(id)
        if (action == "pause" || action == "resume") RideConsumption(this).lifecycle(id, elapsedUs(timing.elapsed))
        analytics.changed(id, timing.elapsed, available(id))
        changed(id)
    }

    fun events(id: String): List<RideEvent> =
        readableDatabase
            .rawQuery(
                "SELECT time,action,timestamp,interrupted FROM lifecycle WHERE ride=? AND revision<=? ORDER BY elapsed_us,producer,producer_sequence",
                arrayOf(id, readRevision(id).toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        RideEvent(c.getDouble(0), c.getString(1), c.getString(2), c.getInt(3) != 0)
                    )
                }
            }

    fun telemetryOriginal(ride: String, eventId: String): app.powerlog.core.telemetry.TelemetryCaptureEvidence? =
        select("SELECT * FROM observations WHERE ride=? AND event_id=? AND kind='telemetry'", arrayOf(ride, eventId)) {
            c ->
            if (c.moveToFirst()) c.observation().telemetryEvidence(ride) else null
        }

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
        original: OriginalIdentity? = null,
        evidence: OriginalEvidence = OriginalEvidence(),
        altitude: AltitudeEvidence? = null,
        detail: Payload = emptyMap(),
        collectionUs: Long? = null,
    ): Long = transaction {
        require(kind in setOf("telemetry", "location", "health"))
        val origin = original ?: nextOriginal(ride, if (kind == "telemetry") "controller" else "phone")
        val content =
            json(
                    evidence.payload() +
                        detail +
                        origin.payload() +
                        mapOf(
                            "time" to time,
                            "timestamp" to timestamp,
                            "kind" to kind,
                            "active" to active,
                            "segment" to segment,
                            "identity" to identity,
                            "epoch" to epoch,
                            "values" to values,
                            "altitude" to altitude?.payload(),
                            "collectionUs" to (collectionUs ?: elapsedUs(time)).toString(),
                        )
                )
                .toString()
        require(content.toByteArray(Charsets.UTF_8).size <= 32768) {
            "Original observation exceeds 32 KiB."
        }
        existingOriginal(ride, origin, "observations", content)?.let {
            return@transaction it
        }
        val revision = nextRevision(revision(ride))
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
                put("event_id", origin.eventId)
                put("producer", origin.producer)
                put("producer_sequence", origin.sequence)
                put("revision", revision)
                put("query_us", timeUs(timestamp))
                put("elapsed_us", collectionUs ?: elapsedUs(time))
                put("evidence", content)
                altitude?.let {
                    put("original_altitude", it.originalAltitudeMeters)
                    put("original_vertical_accuracy", it.originalVerticalAccuracyM)
                    put("original_altitude_datum", it.originalAltitudeDatum)
                }
                values.forEach { (key, value) ->
                    if (key == "courseAccuracyDegrees") return@forEach
                    require(key in storedMetrics && !projectedMetric(key) && value.isFinite())
                    put(key, value)
                }
            }
        val id = writableDatabase.insertOrThrow("observations", null, row)
        writableDatabase.execSQL(
            "UPDATE rides SET revision=revision+1,elapsed=CASE WHEN ? THEN elapsed ELSE max(elapsed,?) END,metrics=metrics|?,original_membership_count=original_membership_count+1,observation_count=observation_count+1,observation_high_water=max(observation_high_water,?) WHERE id=?",
            arrayOf(
                if (kind == "health") 1 else 0,
                time,
                availabilityBits(values.keys.filter { it in metricBits }),
                id,
                ride,
            ),
        )
        changed(ride)
        analytics.changed(
            ride,
            time,
            when (kind) {
                "telemetry" -> available(ride).filter { it in telemetryMetrics }
                "location" -> available(ride).filter { it in locationMetrics }
                else -> values.keys
            },
        )
        if (kind == "telemetry") RideConsumption(this).append(ride, id)
        id
    }

    fun remove(id: String, discard: Boolean = false) {
        fun removeRecords() = transaction {
            check(discard || !healthBusy) {
                "Wait for Health work to finish before deleting a ride."
            }
            writableDatabase.delete("publication_jobs", "ride=?", arrayOf(id))
            synchronized(reads) {
                if (reads.containsKey(id)) throw RideFailureException(RideFailure.storageBusy)
                if (writableDatabase.delete("rides", "id=?", arrayOf(id)) > 0 && !id.startsWith("live-")) {
                    setCatalogRevision(nextCatalogRevision())
                    writableDatabase.execSQL(
                        "UPDATE catalog_state SET last_deleted_workout_id=? WHERE singleton=1",
                        arrayOf(id),
                    )
                }
            }
        }
            .also {
                summaries.remove(id)
                analytics.summaries.forget(id)
                energy.forget(id)
            }
        if (discard) removeRecords() else synchronized(healthExecutorLock) { removeRecords() }
    }

    fun replaceLive(reading: RecordingClockReading): String = transaction {
        writableDatabase.execSQL("DELETE FROM rides WHERE id LIKE 'live-%'")
        create(
            RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
            reading,
            live = true,
        )
    }

    fun lifecycleState(id: String? = null): LifecycleState =
        select(
            if (id == null) "SELECT payload FROM lifecycle_selection WHERE singleton=1"
            else "SELECT payload FROM lifecycle_states WHERE ride=?",
            if (id == null) emptyArray() else arrayOf(id),
        ) {
            check(it.moveToFirst()) { "The stored lifecycle is missing." }
            LifecycleCodec.decodeState(it.getString(0))
        }

    fun startLifecycle(state: LifecycleState, request: LifecycleStart) = RideLifecycle.start(state, request)

    fun requestLifecycle(state: LifecycleState, request: LifecycleRequest) =
        RideLifecycle.request(state, request, lifecycleIntent(request.commandId))

    fun receiptLifecycle(state: LifecycleState, receipt: LifecycleReceipt) = RideLifecycle.receipt(state, receipt)

    fun checkpointLifecycle(
        state: LifecycleState,
        timing: app.powerlog.core.protocol.OwnerTiming,
        clock: LifecycleClock,
        retainedCapture: Boolean = false,
    ) = RideLifecycle.checkpoint(state, requireNotNull(state.ride).key, timing, clock, retainedCapture)

    fun lifecycleIntent(commandId: String): LifecycleIntent? =
        select("SELECT payload FROM lifecycle_intents WHERE command_id=?", arrayOf(commandId)) {
            if (it.moveToFirst()) LifecycleCodec.decodeIntent(it.getString(0)) else null
        }

    private fun lifecycleOriginal(id: String, eventId: String, reservedPhoneSequence: Long = 0): OriginalIdentity =
        select("SELECT producer,producer_sequence FROM lifecycle WHERE ride=? AND event_id=?", arrayOf(id, eventId)) {
            if (it.moveToFirst()) OriginalIdentity(eventId, it.getString(0), it.getLong(1))
            else
                nextOriginal(id, "phone")
                    .copy(
                        eventId = eventId,
                        sequence = Math.addExact(maxOf(producerHighWater(id, "phone"), reservedPhoneSequence), 1),
                    )
        }

    fun saveLifecycle(state: LifecycleState) {
        check(writableDatabase.inTransaction())
        writableDatabase.execSQL(
            "UPDATE lifecycle_selection SET payload=? WHERE singleton=1",
            arrayOf(LifecycleCodec.encodeState(state)),
        )
        state.ride?.let { owner ->
            listOfNotNull(owner.pending, owner.terminal)
                .distinctBy { it.commandId }
                .forEach {
                    writableDatabase.execSQL(
                        "INSERT OR REPLACE INTO lifecycle_intents VALUES(?,?,?)",
                        arrayOf(it.commandId, it.key.rideId, LifecycleCodec.encodeIntent(it)),
                    )
                }
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO lifecycle_states VALUES(?,?)",
                arrayOf(owner.key.rideId, LifecycleCodec.encodeState(state)),
            )
        }
    }

    fun commitLifecycle(before: LifecycleState, transition: LifecycleTransition): LifecycleOutcome {
        transition.rejection?.let { throw RideFailureException(it) }
        val mutation = transition.mutation ?: return transition.afterCommit(true)
        transaction {
            check(lifecycleState() == before) { "The recording owner changed before commit." }
            val owner = mutation.state.ride
            if (mutation.events.any { it.action == "stop" }) setCatalogRevision(nextCatalogRevision())
            if (owner != null && !exists(owner.key.rideId)) {
                val frozen = owner.options
                create(
                    RideOptions(
                        frozen.indoor,
                        frozen.useWatch,
                        frozen.saveToHealth,
                        frozen.recordGPS,
                        frozen.sampleHz,
                        frozen.distanceSource,
                        frozen.batteryConsumptionSource,
                    ),
                    RecordingClockReading(owner.startedAt.original, 0),
                    id = owner.key.rideId,
                    initialPhase = owner.phase.name,
                    startEvent = false,
                )
            }
            listOfNotNull(mutation.intent, mutation.failedCommand).forEach {
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO lifecycle_intents VALUES(?,?,?)",
                    arrayOf(it.commandId, it.key.rideId, LifecycleCodec.encodeIntent(it)),
                )
            }
            val gap = RideLifecycle.recoveryGap(before, transition)
            mutation.events.forEach { persistLifecycleEvent(it, gap = gap) }
            if (owner != null) {
                val ended = owner.terminal?.timing?.timestamp?.original
                val extra =
                    buildMap<String, Any?> {
                        if (ended != null) put("endedAt", ended)
                        if (
                            owner.phase == LifecyclePhase.interrupted ||
                                owner.storageInterrupted ||
                                owner.uncertainEpoch ||
                                mutation.events.any { it.interrupted }
                        )
                            put("interrupted", true)
                    }
                update(
                    owner.key.rideId,
                    owner.phase.name,
                    RideTiming(owner.timing.elapsedSeconds, owner.timing.timerSeconds, owner.timing.timestamp.original),
                    extra,
                )
            }
            saveLifecycle(mutation.state)
        }
        return transition.afterCommit(true)
    }

    private fun persistLifecycleEvent(
        event: LifecycleEvent,
        reservedPhoneSequence: Long = 0,
        gap: LifecycleRecoveryGap? = null,
    ) {
        val id = event.key.rideId
        val sequence =
            select(
                "SELECT json_extract(evidence,'$.controllerSequence') FROM lifecycle WHERE ride=? AND event_id=?",
                arrayOf(id, event.eventId),
            ) {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: producerHighWater(id, "controller").toString()
        lifecycle(
            id,
            RideTiming(event.timing.elapsedSeconds, event.timing.timerSeconds, event.timing.timestamp.original),
            event.action,
            event.interrupted,
            lifecycleOriginal(id, event.eventId, reservedPhoneSequence),
            mapOf(
                "eventId" to event.eventId,
                "cutoffUs" to elapsedUs(event.timing.elapsedSeconds).toString(),
                "clockEpoch" to event.clockEpoch,
                "controllerSequence" to sequence,
                "gapStartUs" to gap?.start?.comparisonUs?.toString(),
            ),
        )
    }

    fun persistStorage(state: LifecycleState, batch: CaptureBatch) = transaction {
        saveLifecycle(state)
        LifecycleStorage.interruption(state, batch)?.let { event ->
            val reserved =
                batch.originals
                    .flatMap { original ->
                        val rows = JSONObject(original.content).getJSONArray("rows")
                        (0 until rows.length()).map(rows::getJSONObject)
                    }
                    .filter {
                        it.getString("ride") == event.key.rideId &&
                            it.getJSONObject("original").getString("producer") == "phone"
                    }
                    .maxOfOrNull { it.getJSONObject("original").getString("producerSequence").toLong() } ?: 0
            persistLifecycleEvent(event, reserved)
        }
        state.ride
            ?.takeIf { it.storageInterrupted }
            ?.let {
                update(
                    it.key.rideId,
                    it.phase.name,
                    RideTiming(it.timing.elapsedSeconds, it.timing.timerSeconds, it.timing.timestamp.original),
                    mapOf("interrupted" to true),
                )
            }
    }

    fun exists(id: String): Boolean = select("SELECT 1 FROM rides WHERE id=?", arrayOf(id)) { it.moveToFirst() }

    fun recoverOrphans(
        clock: LifecycleClock = LifecycleClock(Timestamps.parse(iso()), UUID.randomUUID().toString(), 0)
    ): LifecycleOutcome = transaction {
        val before = lifecycleState()
        commitLifecycle(before, RideLifecycle.restart(before, clock, UUID.randomUUID().toString()))
    }

    fun finishCollection(id: String) = transaction {
        val record = storedRecord(id)
        check(record.endedAt != null) { "The owner has no committed terminal cutoff." }
        val revision = revision(id)
        if (record.saveToHealth) {
            val terminal = HealthExport.clockFailure(Instant.parse(record.startedAt), Instant.parse(record.endedAt))
            if (terminal != null) {
                healthStatus(
                    id,
                    terminal.copy(
                        components =
                            listOf(
                                ComponentResult(
                                    Component.workout,
                                    revision,
                                    true,
                                    ComponentState.failed,
                                    HealthReason.clock,
                                    emptyList(),
                                )
                            )
                    ),
                )
            } else {
                val job = ensureHealthJob(id)
                putHealthJob(job)
            }
        }
    }

    fun lifecyclePending(id: String): Boolean =
        select("SELECT payload FROM lifecycle_states WHERE ride=?", arrayOf(id)) {
            it.moveToFirst() && LifecycleCodec.decodeState(it.getString(0)).ride?.pending != null
        }

    fun deleteHistorical(id: String, owner: LifecycleState) {
        check(!healthBusy) { "Wait for Health work to finish before deleting this ride." }
        synchronized(healthExecutorLock) {
            transaction {
                check(
                    HistoricalDeletionAdmission.canDelete(
                        HistoricalDeleteContext(
                            metadata(id).str("phase"),
                            owner.ride?.takeIf { it.key.rideId == id }?.phase?.name,
                            healthBusy,
                            owner.ride?.pending?.effect == LifecycleEffectKind.startOwner,
                            owner.ride?.pending?.terminal == true,
                            lifecyclePending(id),
                            lifecycleRecovering(id),
                            hasReaders(id),
                        )
                    )
                ) {
                    "Finish owner, recovery, Health and read work before deleting this ride."
                }
                remove(id)
                writableDatabase.delete("lifecycle_intents", "ride=?", arrayOf(id))
                if (owner.ride?.key?.rideId == id) saveLifecycle(LifecycleState(owner.generation))
            }
        }
    }

    fun removeFailedStart(before: LifecycleState, transition: LifecycleTransition): LifecycleOutcome =
        synchronized(healthExecutorLock) {
            transaction {
                val id = requireNotNull(before.ride).key.rideId
                remove(id, discard = true)
                writableDatabase.delete("lifecycle_intents", "ride=?", arrayOf(id))
                commitLifecycle(before, transition)
            }
        }

    fun page(id: String, after: Long = 0, limit: Int = 256): List<Observation> {
        require(limit in 1..512 && after >= 0)
        return readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND id>? AND revision<=? ORDER BY id LIMIT ?",
                arrayOf(id, after.toString(), readRevision(id).toString(), limit.toString()),
            )
            .use { c ->
                buildList {
                    var bytes = 0
                    while (c.moveToNext()) {
                        val observation = c.observation()
                        val size = json(observation.projection()).toString().toByteArray(Charsets.UTF_8).size
                        if (bytes + size > 512 * 1024) break
                        bytes += size
                        add(observation)
                    }
                }
            }
    }

    fun each(id: String, body: (Observation) -> Unit) {
        var last = 0L
        while (true) {
            val rows = page(id, last)
            if (rows.isEmpty()) return
            rows.forEach(body)
            last = rows.last().id
        }
    }

    fun appendHealthPage(
        id: String,
        originals: List<HealthOriginal>,
        progress: HealthQueryProgress,
        changes: List<HealthChange> = emptyList(),
    ) = transaction {
        require(originals.size <= 128)
        val priorRevision = revision(id)
        originals.forEach { h ->
            val origin = h.origin
            val row =
                insert(
                    id,
                    h.collectionUs / 1000000.0,
                    h.originalStartTimestamp,
                    "health",
                    h.collectionMapped,
                    h.collectionSegment,
                    h.readings(),
                    epoch = if (h.collectionSegment == 0) "" else "health:${h.collectionSegment}",
                    original = OriginalIdentity(origin.originEventId, origin.producer, origin.sequence),
                    detail = h.payload(),
                    collectionUs = h.collectionUs,
                )
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO health_samples(observation_id,provider,data_origin,provider_parent_id,measure,unit,representation,member_index,start_timestamp,end_timestamp,origin_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    row,
                    origin.provider,
                    origin.dataOrigin,
                    origin.providerObservationId,
                    h.measure,
                    h.unit,
                    h.representation,
                    h.memberIndex,
                    h.originalStartTimestamp,
                    h.originalEndTimestamp,
                    origin.originRevision,
                ),
            )
        }
        for (change in changes) {
            val h = originals.singleOrNull { it.origin.originEventId == change.originalEventId }
            require(
                h != null && h.targetEventId == change.targetEventId && (h.measure == "tombstone") == change.deleted
            ) {
                "Selection changes require their immutable correction or tombstone original."
            }
            require(
                select(
                    "SELECT 1 FROM observations WHERE ride=? AND event_id=? AND kind='health'",
                    arrayOf(id, change.targetEventId),
                ) {
                    it.moveToFirst()
                }
            )
            val existing =
                select(
                    "SELECT target_event_id,deleted FROM health_changes WHERE ride=? AND original_event_id=?",
                    arrayOf(id, change.originalEventId),
                ) { c ->
                    if (c.moveToFirst()) c.getString(0) to (c.getInt(1) != 0) else null
                }
            require(existing == null || existing == change.targetEventId to change.deleted)
            if (existing == null) {
                val revision = nextRevision(revision(id))
                writableDatabase.execSQL(
                    "INSERT INTO health_changes(ride,revision,target_event_id,original_event_id,deleted) VALUES(?,?,?,?,?)",
                    arrayOf(
                        id,
                        revision,
                        change.targetEventId,
                        change.originalEventId,
                        if (change.deleted) 1 else 0,
                    ),
                )
                select(
                    "SELECT time FROM observations WHERE ride=? AND event_id=?",
                    arrayOf(id, change.targetEventId),
                ) { c ->
                    if (c.moveToFirst()) analytics.changed(id, c.getDouble(0), metricBits.keys)
                }
                writableDatabase.execSQL(
                    "UPDATE rides SET revision=? WHERE id=?",
                    arrayOf(revision, id),
                )
                changed(id)
            }
        }
        setHealthProgress(id, progress)
        var bits = availabilityBits(available(id).filter { it in telemetryMetrics || it in locationMetrics })
        for (metric in
            metricBits.keys.filter {
                it !in telemetryMetrics && it !in locationMetrics && !it.endsWith("DistanceMeters")
            }) {
            if (
                select(
                    "SELECT 1 FROM (${metricQuery(id, metric)}) WHERE $metric IS NOT NULL LIMIT 1",
                    arrayOf(id),
                ) {
                    it.moveToFirst()
                }
            )
                bits = bits or availabilityBits(listOf(metric))
        }
        for ((metric, source) in
            listOf(
                "gpsDistanceMeters" to "gps:phone",
                "controllerDistanceMeters" to "controller",
            )) {
            if (
                select(
                    "SELECT 1 FROM distance_intervals WHERE ride=? AND source=? LIMIT 1",
                    arrayOf(id, source),
                ) {
                    it.moveToFirst()
                }
            )
                bits = bits or availabilityBits(listOf(metric))
        }
        writableDatabase.execSQL("UPDATE rides SET metrics=? WHERE id=?", arrayOf(bits, id))
        if (revision(id) > priorRevision) {
            setCatalogRevision(nextCatalogRevision())
        }
        changed(id)
    }

    fun healthProgress(id: String, progress: HealthQueryProgress): Payload? =
        select(
            "SELECT payload FROM health_query_progress WHERE ride=? AND provider=? AND measure=? AND scope=?",
            arrayOf(id, progress.provider, progress.measure, progress.scope),
        ) { c ->
            if (c.moveToFirst()) JSONObject(c.getString(0)).map() else null
        }

    fun pruneCheckpoints(id: String, metric: String, bucket: Long, plot: Boolean = false) {
        val table = if (plot) "analytics_plot_blocks" else "analytics"
        val targets = synchronized(reads) { retainedPins[id]?.keys?.toList().orEmpty() } + revision(id)
        val revisions =
            targets
                .mapNotNull { target ->
                    select(
                        "SELECT max(revision) FROM $table WHERE ride=? AND metric=? AND bucket=? AND revision<=?",
                        arrayOf(id, metric, bucket, target),
                    ) { c ->
                        c.moveToFirst()
                        if (c.isNull(0)) null else c.getLong(0)
                    }
                }
                .distinct()
        if (revisions.isNotEmpty())
            writableDatabase.execSQL(
                "DELETE FROM $table WHERE ride=? AND metric=? AND bucket=? AND revision NOT IN (${revisions.joinToString()})",
                arrayOf(id, metric, bucket),
            )
    }

    fun selectedPredicate(alias: String): String {
        val pin = readRevisionForQuery()
        return "AND NOT EXISTS (SELECT 1 FROM health_changes h WHERE h.ride=$alias.ride AND h.target_event_id=$alias.event_id AND h.revision<=$pin)"
    }

    internal data class HealthCollectionSpan(
        val utcStartUs: Long,
        val utcEndUs: Long,
        val startUs: Long,
        val endUs: Long,
        val segment: Int,
    )

    fun healthCollectionIntervals(
        id: String,
        startUs: Long,
        endUs: Long,
    ): List<HealthCollectionSpan> {
        require(endUs >= startUs)
        val gaps =
            select(
                "SELECT CAST(json_extract(evidence,'$.gapStartUs') AS INTEGER),query_us,elapsed_us FROM lifecycle INDEXED BY lifecycle_health_gaps WHERE ride=? AND revision<=? AND action='pause' AND json_type(evidence,'$.gapStartUs')='text' ORDER BY query_us,revision",
                arrayOf(id, readRevision(id)),
            ) { c ->
                buildList {
                    while (c.moveToNext()) add(Triple(c.getLong(0), c.getLong(1), c.getLong(2)))
                }
            }
        var anchor = timeUs(metadata(id).str("startedAt"))
        var collection = 0L
        var cursor = startUs
        var segment = 0
        val spans = mutableListOf<HealthCollectionSpan>()
        fun span(a: Long, b: Long) {
            val mapped = Math.addExact(collection, Math.subtractExact(a, anchor))
            spans.add(
                HealthCollectionSpan(
                    a,
                    b,
                    mapped,
                    Math.addExact(mapped, Math.subtractExact(b, a)),
                    segment,
                )
            )
        }
        for ((gapStart, gapEnd, cutoff) in gaps) {
            require(gapEnd >= gapStart)
            if (startUs == endUs) {
                if (startUs < gapStart) break
                if (startUs < gapEnd) return emptyList()
            } else {
                if (cursor < gapStart && endUs > cursor) span(cursor, minOf(endUs, gapStart))
                if (endUs <= gapStart) return spans
                cursor = maxOf(cursor, gapEnd)
            }
            anchor = gapEnd
            collection = cutoff
            segment++
        }
        if (startUs == endUs) span(startUs, endUs) else if (endUs > cursor) span(cursor, endUs)
        return spans
    }

    fun healthCollectionUs(id: String, utcUs: Long): Long? =
        healthCollectionIntervals(id, utcUs, utcUs).singleOrNull()?.startUs

    fun healthQuotaWait(id: String, scope: String, nowUs: Long): Long = transaction {
        val count =
            select(
                "SELECT count FROM health_quota_waits WHERE ride=? AND scope=?",
                arrayOf(id, scope),
            ) {
                if (it.moveToFirst()) it.getInt(0) + 1 else 1
            }
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO health_quota_waits VALUES(?,?,?)",
            arrayOf(id, scope, count),
        )
        val until = nowUs + checkNotNull(HealthBudget.retry(count, true, null, nowUs).delayUs)
        val bucket = if (scope == "write") CallBucket.write else CallBucket.read
        val budget = healthBudget(bucket, nowUs)
        putHealthBudget(budget.copy(nextEligibleUs = maxOf(budget.nextEligibleUs ?: 0, until)), bucket, nowUs)
        if (bucket == CallBucket.write) putHealthJob(ensureHealthJob(id).copy(resumeAtUs = until))
        until
    }

    fun clearHealthQuota(id: String, scope: String) {
        writableDatabase.delete("health_quota_waits", "ride=? AND scope=?", arrayOf(id, scope))
    }

    private fun readRevisionForQuery(): Long = pins.get()?.revision ?: MAX_REVISION

    fun metricQuery(
        id: String,
        metric: String,
        elapsedOrder: Boolean = false,
        pointRead: Boolean = false,
        idOrder: Boolean = false,
    ): String {
        require(metric in metricBits)
        val pin = readRevision(id)
        val source =
            when (metric) {
                "gpsDistanceMeters" -> "gps:phone"
                "controllerDistanceMeters" -> "controller"
                else -> null
            }
        val kind =
            when (metric) {
                in telemetryMetrics -> "telemetry"
                in locationMetrics -> "location"
                else -> "health"
            }
        val health = if (kind == "health") HealthReadings(this).predicate(id, metric) else ""
        val index =
            if (elapsedOrder) "INDEXED BY ${if (idOrder) "observations_elapsed_id" else "observations_elapsed"}" else ""
        if (metric in consumptionMetrics) {
            val batteryFrom =
                if (elapsedOrder)
                    "observations o $index CROSS JOIN battery_intervals d INDEXED BY battery_original ON d.ride=o.ride AND d.to_id=o.id"
                else "battery_intervals d JOIN observations o ON o.id=d.to_id"
            val rideColumn = if (elapsedOrder) "o.ride" else "d.ride"
            val point = if (pointRead) "AND d.to_id=?" else ""
            return "SELECT o.*,d.end_value AS $metric FROM $batteryFrom WHERE $rideColumn=? AND d.metric='$metric' AND d.revision<=$pin AND (d.retired_revision IS NULL OR d.retired_revision>$pin) AND o.revision<=$pin $point"
        }
        val pointColumn = if (source == null) "o.id" else "d.to_id"
        val point = if (pointRead) "AND $pointColumn=?" else ""
        val distanceFrom =
            if (elapsedOrder)
                "observations o $index CROSS JOIN distance_intervals d INDEXED BY distance_original ON d.ride=o.ride AND d.to_id=o.id"
            else "distance_intervals d JOIN observations o ON o.id=d.to_id"
        val rideColumn = if (elapsedOrder) "o.ride" else "d.ride"
        return if (source == null)
            "SELECT o.* FROM observations o $index WHERE o.ride=? AND o.kind='$kind' AND o.revision<=$pin ${selectedPredicate("o")} $health $point"
        else
            "SELECT o.*,d.cumulative_q/1000000.0 AS $metric FROM $distanceFrom WHERE $rideColumn=? AND d.source='$source' AND d.revision<=$pin AND o.revision<=$pin $point"
    }

    fun metricObservation(id: String, metric: String, row: Long): Observation? {
        val sql = "SELECT * FROM (${metricQuery(id, metric, pointRead = true)}) WHERE $metric IS NOT NULL"
        queryObserver?.invoke(sql, arrayOf(id, row))
        return readableDatabase.rawQuery(sql, arrayOf(id, row.toString())).use { c ->
            if (c.moveToFirst()) c.observation() else null
        }
    }

    fun sourceHealthRevision(id: String): Long =
        select(
            "SELECT max(revision) FROM (SELECT max(revision) AS revision FROM observations WHERE ride=? AND kind!='health' UNION ALL SELECT max(revision) FROM lifecycle WHERE ride=?)",
            arrayOf(id, id),
        ) {
            if (it.moveToFirst()) it.getLong(0) else 0
        }

    fun setHealthProgress(
        id: String,
        progress: HealthQueryProgress,
        attempts: Int = 0,
        error: String? = null,
        nextRefreshUs: Long? = null,
    ) = transaction {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO health_query_progress VALUES(?,?,?,?,?)",
            arrayOf(
                id,
                progress.provider,
                progress.measure,
                progress.scope,
                json(
                        progress.payload() +
                            mapOf(
                                "attempts" to attempts,
                                "error" to error,
                                "nextRefreshUs" to nextRefreshUs?.toString(),
                            )
                    )
                    .toString(),
            ),
        )
    }

    fun healthJob(id: String): AndroidHealthJob? =
        healthSelect("SELECT payload FROM publication_jobs WHERE ride=?", arrayOf(id)) {
            if (it.moveToFirst()) AndroidHealthJob.decode(it.getString(0)) else null
        }

    fun putHealthJob(job: AndroidHealthJob): Boolean = transaction {
        if (!exists(job.rideId)) return@transaction false
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO publication_jobs VALUES(?,?)",
            arrayOf(job.rideId, job.encode()),
        )
        true
    }

    fun ensureHealthJob(id: String): AndroidHealthJob = transaction {
        healthJob(id) ?: AndroidHealthJob(id, sourceHealthRevision(id)).also(::putHealthJob)
    }

    fun healthBudget(
        bucket: CallBucket = CallBucket.write,
        nowUs: Long = timeUs(Instant.now().toString()),
    ): PublicationBudget = transaction {
        select(
            "SELECT payload,wall_us,elapsed_us,deadline_shift_us FROM publication_budget WHERE bucket=?",
            arrayOf(bucket.name),
        ) {
            if (!it.moveToFirst()) PublicationBudget(Provider.healthConnect, emptyList(), null)
            else {
                val stored = HealthCodec.decodePublicationBudget(it.getString(0))
                val elapsed = android.os.SystemClock.elapsedRealtimeNanos() / 1000
                val sampledWall = it.getLong(1)
                val sampledElapsed = it.getLong(2)
                val advance = if (elapsed >= sampledElapsed) elapsed - sampledElapsed else 0L
                val expected = sampledWall + advance
                if (nowUs < sampledWall || expected - nowUs > 1_000_000L) {
                    val shift = expected - nowUs
                    val rebased =
                        stored.copy(
                            calls = stored.calls.map { call -> call.copy(atUs = minOf(nowUs, call.atUs - shift)) },
                            nextEligibleUs = stored.nextEligibleUs?.let { due -> maxOf(nowUs, due - shift) },
                        )
                    val deadlines = if (bucket == CallBucket.write) Math.addExact(it.getLong(3), shift) else 0L
                    writableDatabase.execSQL(
                        "UPDATE publication_budget SET payload=?,wall_us=?,elapsed_us=?,deadline_shift_us=? WHERE bucket=?",
                        arrayOf(HealthCodec.encodePublicationBudget(rebased), nowUs, elapsed, deadlines, bucket.name),
                    )
                    rebased
                } else stored
            }
        }
    }

    fun putHealthBudget(
        budget: PublicationBudget,
        bucket: CallBucket = CallBucket.write,
        nowUs: Long = timeUs(Instant.now().toString()),
    ) = transaction {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO publication_budget VALUES(?,?,?,?,?)",
            arrayOf(
                bucket.name,
                HealthCodec.encodePublicationBudget(budget),
                nowUs,
                android.os.SystemClock.elapsedRealtimeNanos() / 1000,
                0L,
            ),
        )
    }

    fun deferHealthReads(id: String, deferred: Boolean) {
        if (deferred)
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO health_read_deferrals VALUES(?,?)",
                arrayOf(id, UUID.randomUUID().toString()),
            )
        else writableDatabase.delete("health_read_deferrals", "ride=?", arrayOf(id))
    }

    fun healthReadsDeferred(id: String): Boolean =
        select("SELECT 1 FROM health_read_deferrals WHERE ride=?", arrayOf(id)) { it.moveToFirst() }

    fun resumeHealthReads(): List<Pair<String, String>> =
        select("SELECT ride,token FROM health_read_deferrals", emptyArray()) {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
        }

    fun acknowledgeHealthReads(id: String, token: String) {
        writableDatabase.delete("health_read_deferrals", "ride=? AND token=?", arrayOf(id, token))
    }

    fun healthOriginalRows(
        id: String,
        measure: String? = null,
        measures: List<String> = emptyList(),
        body: (Observation) -> Unit,
    ) {
        require(measure == null || measures.isEmpty())
        val selectedMeasures = (measure?.let { listOf(it) } ?: measures).distinct()
        require(selectedMeasures.isNotEmpty())
        val revision = readRevision(id)
        val maximumArgs = mutableListOf<Any>()
        val maxima = selectedMeasures.map { metric ->
            maximumArgs.addAll(listOf(id, metric))
            "SELECT max(o.elapsed_us) AS last_elapsed FROM observations AS o WHERE o.ride=? AND o.kind='health' AND json_extract(o.evidence,'$.measure')=?"
        }
        val endUs =
            select(
                "SELECT max(last_elapsed) FROM (${maxima.joinToString(" UNION ALL ")})",
                maximumArgs.toTypedArray(),
            ) { c ->
                if (!c.moveToFirst() || c.isNull(0)) null else c.getLong(0)
            } ?: return
        var after: Observation? = null
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val cursor = after
            val continuation =
                if (cursor == null) "" else "AND (o.elapsed_us,o.producer,o.producer_sequence,o.id)>(?,?,?,?)"
            val args = mutableListOf<Any>()
            val pages = selectedMeasures.map { metric ->
                args.addAll(listOf(id, metric, cursor?.elapsedUs ?: Long.MIN_VALUE, endUs))
                cursor?.let {
                    args.addAll(listOf(it.elapsedUs, requireNotNull(it.original).producer, it.original.sequence, it.id))
                }
                // Unary + keeps the scalar lower bound from overriding the full continuation seek.
                "SELECT o.*, (o.revision<=$revision ${selectedPredicate("o")}) AS health_selected FROM observations o WHERE o.ride=? AND o.kind='health' AND json_extract(o.evidence,'$.measure')=? AND +o.elapsed_us>=? AND o.elapsed_us<=? $continuation ORDER BY o.elapsed_us,o.producer,o.producer_sequence,o.id LIMIT 256"
            }
            val sql =
                if (pages.size == 1) pages.single()
                else
                    "${pages.joinToString(" UNION ALL ") { "SELECT * FROM ($it)" }} ORDER BY elapsed_us,producer,producer_sequence,id LIMIT 256"
            val (last, rows) =
                select(
                    sql,
                    args.toTypedArray(),
                ) { c ->
                    val candidates = c.observationPage()
                    candidates.lastOrNull() to
                        candidates.filterIndexed { index, _ ->
                            c.moveToPosition(index)
                            c.long("health_selected") == 1L
                        }
                }
            if (last == null) return
            rows.forEachIndexed { index, row ->
                if (index % 16 == 0) check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
                body(row)
            }
            after = last
        }
    }

    fun existingHealthOriginal(id: String, eventId: String): Observation? =
        select("SELECT * FROM observations WHERE ride=? AND event_id=?", arrayOf(id, eventId)) { c ->
            if (c.moveToFirst()) c.observation() else null
        }

    fun Cursor.observationPage(): List<Observation> = buildList {
        var bytes = 0
        while (size < 256 && moveToNext()) {
            if (size % 16 == 0) check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            val rowBytes = text("evidence").toByteArray(Charsets.UTF_8).size * 2 + 1024
            require(rowBytes <= 524288) { "Analysis original exceeds the page byte bound" }
            if (bytes + rowBytes > 524288) break
            add(observation())
            bytes += rowBytes
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
                    val i = getColumnIndex(key)
                    if (i < 0 || isNull(i)) null else key to getDouble(i)
                }
                .toMap(),
            text("kind"),
            long("active") == 1L,
            text("identity"),
            text("epoch"),
            OriginalIdentity(text("event_id"), text("producer"), long("producer_sequence")),
            long("revision"),
            long("query_us"),
            long("elapsed_us"),
            JSONObject(text("evidence")).map(),
        )
}

internal fun Cursor.text(key: String): String = getString(getColumnIndexOrThrow(key))

internal fun Cursor.double(key: String): Double = getDouble(getColumnIndexOrThrow(key))

internal fun Cursor.long(key: String): Long = getLong(getColumnIndexOrThrow(key))

private fun Cursor.requireRide() {
    if (!moveToFirst()) throw RideFailureException(RideFailure.rideDeleted)
}
