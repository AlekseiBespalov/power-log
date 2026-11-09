package app.powerlog.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheChange
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.ForeignArchive
import app.powerlog.application.store.ForeignKind
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.SqliteArchiveHarness
import app.powerlog.application.store.SqliteStoreUnderTest
import app.powerlog.application.store.Stall
import app.powerlog.application.store.StatementPlan
import app.powerlog.application.store.StoreFile
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.StoreUnderTest
import app.powerlog.application.store.ok
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.rules.RULES_VERSION
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull

internal interface SqlTestPlatform {
    fun newDirectory(): String

    fun files(directory: String): Map<String, ByteArray>

    fun path(directory: String, file: StoreFile): String =
        when (file) {
            StoreFile.archive -> "$directory/$ARCHIVE_FILE"
            StoreFile.cache -> "$directory/$CACHE_FILE"
        }

    fun write(path: String, bytes: ByteArray)

    fun length(path: String): Long

    fun delete(directory: String)

    // Writes a SQLite file in rollback-journal mode, as another build of SQLite would leave it.
    fun sqlite(path: String, statements: List<String>)

    // Writes a SQLite file in WAL mode with its rows still in the -wal beside it, as a process that ended with the file
    // open leaves it.
    fun walSqlite(path: String, statements: List<String>)

    fun executors(): TestExecutors

    // null: read the plan on the archive's writer
    fun explain(archivePath: String, sql: String): List<String>? = null

    // directory null: in memory
    fun stores(
        directory: String?,
        driver: (SQLiteDriver) -> SQLiteDriver,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        rulesVersion: Int,
        layout: Int,
    ): SqlStores
}

internal interface TestExecutors {
    val store: StoreExecutors

    fun close()
}

internal class SilentLog : DiagnosticLogRepository {
    override suspend fun append(line: LogLine) {}

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
        StoreResult.Ok(LogChunk(ByteArray(0), null))
}

internal class SqlArchiveHarness(private val platform: SqlTestPlatform, private val memory: Boolean = false) :
    SqliteArchiveHarness {
    override val storesHealth: Boolean = true
    override val queueJobs: Int = StorePolicy.queueJobs
    override val archiveVersion: Int = ARCHIVE_VERSION

    override val foreignArchives: List<ForeignArchive> =
        listOf(
            Foreign(ForeignKind.otherVersion, "user_version ${ARCHIVE_VERSION + 1}") { path ->
                platform.sqlite(
                    path,
                    listOf(
                        "CREATE TABLE ride (id INTEGER PRIMARY KEY)",
                        "PRAGMA user_version = ${ARCHIVE_VERSION + 1}",
                    ),
                )
            },
            Foreign(ForeignKind.otherVersion, "user_version ${ARCHIVE_VERSION + 1} in WAL mode") { path ->
                platform.walSqlite(
                    path,
                    listOf(
                        "CREATE TABLE ride (id INTEGER PRIMARY KEY)",
                        "PRAGMA user_version = ${ARCHIVE_VERSION + 1}",
                    ),
                )
            },
            Foreign(ForeignKind.versionZeroWithTables, "user_version 0 with a table") { path ->
                platform.sqlite(path, listOf("CREATE TABLE notes (text TEXT)", "INSERT INTO notes VALUES ('kept')"))
            },
            Foreign(ForeignKind.garbage, "bytes that are not SQLite") { path ->
                platform.write(path, ByteArray(4_096) { (it * 31 + 7).toByte() })
            },
        )

    override suspend fun create(scope: TestScope): SqlStore {
        val session = Session(platform, memory)
        scope.backgroundScope.coroutineContext.job.invokeOnCompletion { session.close() }
        return session.open(scope, RULES_VERSION, CACHE_LAYOUT)
    }

    private inner class Foreign(
        override val kind: ForeignKind,
        override val name: String,
        private val writeTo: (String) -> Unit,
    ) : ForeignArchive {
        override suspend fun write(store: StoreUnderTest) {
            writeTo((store as SqlStore).archivePath)
        }
    }
}

internal class Session(private val platform: SqlTestPlatform, private val memory: Boolean) {
    val directory: String = platform.newDirectory()
    private val executors = platform.executors()
    private val log = SilentLog()
    private val opened = mutableListOf<SqlStores>()

    fun open(scope: TestScope, rulesVersion: Int, layout: Int): SqlStore {
        val driver = TestDriver()
        val stores =
            platform.stores(
                directory.takeUnless { memory },
                driver::wrap,
                executors.store,
                log,
                rulesVersion,
                layout,
            )
        opened += stores
        return SqlStore(this, scope, driver, stores, rulesVersion, layout)
    }

    fun files(): Map<String, ByteArray> = if (memory) emptyMap() else platform.files(directory)

    fun path(file: StoreFile): String = platform.path(directory, file)

    fun explain(sql: String): List<String>? = platform.explain(path(StoreFile.archive), sql)

    fun length(file: StoreFile): Long = platform.length(path(file))

    fun walLength(file: StoreFile): Long = platform.length(path(file) + "-wal")

    fun close() {
        runBlocking { for (stores in opened) withTimeoutOrNull(30.seconds) { stores.stores.host.close() } }
        executors.close()
        platform.delete(directory)
    }
}

internal class SqlStore(
    private val session: Session,
    private val scope: TestScope,
    private val driver: TestDriver,
    private val sql: SqlStores,
    private val rulesVersion: Int,
    private val layout: Int,
) : SqliteStoreUnderTest {
    override val host: StoreHost = sql.stores.host
    override val writer: ArchiveWriter = sql.stores.writer
    override val rides: RideRepository = sql.stores.rides
    override val measurements: MeasurementRepository = sql.stores.measurements
    override val connection: ConnectionRepository = sql.stores.connection
    override val maintenance: MaintenanceRepository = sql.stores.maintenance
    override val health: HealthRepository = sql.stores.health
    override val settings: SettingsRepository = sql.stores.settings
    override val cache: CacheRepository = sql.stores.cache

    val archivePath: String
        get() = session.path(StoreFile.archive)

    override suspend fun reopen(): SqlStore {
        host.close().ok()
        return session.open(scope, rulesVersion, layout)
    }

    override suspend fun reopenAfter(change: CacheChange): SqlStore {
        host.close().ok()
        return when (change) {
            CacheChange.rulesVersion -> session.open(scope, rulesVersion + 1, layout)
            CacheChange.layout -> session.open(scope, rulesVersion, layout + 1)
        }
    }

    override suspend fun stallWriter(): Stall = hold {}

    override suspend fun holdWriteTransaction(): Stall = hold { db ->
        db.execute("BEGIN IMMEDIATE")
        await()
        db.execute("ROLLBACK")
    }

    private suspend fun hold(during: suspend CompletableDeferred<Unit>.(SqlConnection) -> Unit): Stall {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        scope.backgroundScope.launch {
            sql.archive.job { db ->
                started.complete(Unit)
                released.during(db)
                released.await()
            }
        }
        started.await()
        return object : Stall {
            override suspend fun release() {
                released.complete(Unit)
            }
        }
    }

    override suspend fun storedRows(): Long =
        sql.archive
            .read { db ->
                db.long(
                    listOf(
                            "ride",
                            "ride_event",
                            "controller_session",
                            "telemetry",
                            "location",
                            "health_sample",
                            "health_work",
                        )
                        .joinToString(" + ", "SELECT ") { "(SELECT count(*) FROM $it)" }
                )
            }
            .ok()

    // -shm holds no rows: it is the WAL index a connection rebuilds from the -wal.
    override suspend fun snapshot(): Map<String, ByteArray> = session.files().filterKeys { !it.endsWith("-shm") }

    override suspend fun writeSettingsText(text: String?) {
        sql.archive.write { it.saveSettings(text) }.ok()
    }

    override suspend fun writeOutOfVocabulary(rideId: RideId) {
        sql.archive
            .write { db ->
                db.statement("UPDATE ride SET phase = 'flying' WHERE uuid = ?") {
                    it.bindText(1, rideId.value)
                    it.step()
                }
            }
            .ok()
    }

    override suspend fun corruptCachedValues(rideId: RideId) {
        sql.cache
            .job { db ->
                db.statement("UPDATE derived SET value = X'00FF' WHERE ride = ?") {
                    it.bindText(1, rideId.value)
                    it.step()
                }
            }
            .ok()
    }

    override suspend fun failCacheWrites() {
        sql.cache.job { it.execute("PRAGMA query_only = 1") }.ok()
    }

    override suspend fun archiveFiles(): Set<String> = session.files().keys

    override suspend fun freelistCount(file: StoreFile): Long =
        when (file) {
            StoreFile.archive -> sql.archive.job { it.long("PRAGMA freelist_count") }.ok()
            StoreFile.cache -> sql.cache.job { it.long("PRAGMA freelist_count") }.ok()
        }

    override suspend fun fileBytes(file: StoreFile): Long = session.length(file)

    override suspend fun walBytes(file: StoreFile): Long = session.walLength(file)

    override suspend fun writerPragmas(): List<FilePragmas> =
        listOf(
            sql.archive.job { it.pragmas(StoreFile.archive) }.ok(),
            sql.cache.job { it.pragmas(StoreFile.cache) }.ok(),
        )

    override suspend fun injectCorruption() {
        driver.corrupt = true
    }

    suspend fun executed(block: suspend () -> Unit): List<Executed> = driver.record(block)

    override suspend fun statementPlans(block: suspend () -> Unit): List<StatementPlan> {
        val statements = driver.record(block).map { it.sql }
        return statements.map { statement ->
            val plan =
                session.explain(statement)
                    ?: sql.archive
                        .job { db ->
                            db.statement("EXPLAIN QUERY PLAN $statement") {
                                buildList { while (it.step()) add(it.getText(3)) }
                            }
                        }
                        .ok()
            StatementPlan(statement, plan)
        }
    }
}

internal class Executed(val sql: String, val longs: Map<Int, Long>)

internal class TestDriver {
    @Volatile var corrupt: Boolean = false
    @Volatile private var recording: MutableList<Executed>? = null

    suspend fun record(block: suspend () -> Unit): List<Executed> {
        val statements = mutableListOf<Executed>()
        recording = statements
        try {
            block()
        } finally {
            recording = null
        }
        return statements.toList()
    }

    fun wrap(driver: SQLiteDriver): SQLiteDriver =
        object : SQLiteDriver by driver {
            override fun open(fileName: String): SQLiteConnection = Connection(driver.open(fileName))
        }

    private inner class Connection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement = Statement(connection.prepare(sql), sql)
    }

    private inner class Statement(private val statement: SQLiteStatement, private val sql: String) :
        SQLiteStatement by statement {
        private var fresh = true
        private val longs = HashMap<Int, Long>()

        override fun bindLong(index: Int, value: Long) {
            longs[index] = value
            statement.bindLong(index, value)
        }

        override fun clearBindings() {
            longs.clear()
            statement.clearBindings()
        }

        override fun step(): Boolean {
            if (corrupt) throw SQLiteException("Error code: 11, message: database disk image is malformed")
            if (fresh) recording?.add(Executed(sql, longs.toMap()))
            fresh = false
            return statement.step()
        }

        override fun reset() {
            fresh = true
            statement.reset()
        }
    }
}
