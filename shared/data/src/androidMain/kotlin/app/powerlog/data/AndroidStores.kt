package app.powerlog.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.sqlite.driver.SupportSQLiteDriver
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import java.io.File

public object AndroidStores {
    public fun create(
        context: Context,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
    ): Stores = files(context, executors, log, clock).stores

    public fun diagnosticLog(context: Context, executors: StoreExecutors): DiagnosticLogRepository =
        DiagnosticLogFiles(JavaLogFiles { diagnosticDirectory(context) }, executors.log)

    // noBackupFilesDir's getter creates the directory, so call this off the main thread
    public fun diagnosticDirectory(context: Context): File = File(context.noBackupFilesDir, "diagnostics")

    internal fun files(
        context: Context,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
        driver: (SQLiteDriver) -> SQLiteDriver = { it },
        rulesVersion: Int = RULES_VERSION,
        layout: Int = CACHE_LAYOUT,
    ): SqlStores {
        val context = context.applicationContext ?: context
        return SqlStores(
            AndroidFile(context, ARCHIVE_FILE, noBackup = false, ARCHIVE_READERS, driver),
            AndroidFile(context, CACHE_FILE, noBackup = true, CACHE_READERS, driver),
            executors,
            log,
            clock,
            rulesVersion,
            layout,
        )
    }
}

// The framework's WAL pool (SQLiteGlobal.getWALConnectionPoolSize) keeps its primary connection for the writer.
@SuppressLint("DiscouragedApi")
internal fun walReaders(): Int {
    val resources = Resources.getSystem()
    val id = resources.getIdentifier("db_connection_pool_size", "integer", "android")
    return maxOf(2, if (id == 0) 2 else resources.getInteger(id)) - 1
}

internal class RefusedFile : IllegalStateException("the file holds another archive")

internal class AndroidFile(
    private val context: Context,
    private val name: String,
    private val noBackup: Boolean,
    private val readers: Int,
    private val driver: (SQLiteDriver) -> SQLiteDriver,
) : SqlFile {
    val file: File
        get() = if (noBackup) File(context.noBackupFilesDir, name) else context.getDatabasePath(name)

    override fun open(schema: SqlSchema): FileOpening {
        val file = file
        if (file.length() > 0 && !acceptable(file, schema)) return FileOpening.Refused
        if (file.length() == 0L) bootstrap(file)
        val helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(name)
                        .noBackupDirectory(noBackup)
                        .allowDataLossOnRecovery(false)
                        .callback(Callback(schema))
                        .build()
                )
        val connections = mutableListOf<SqlConnection>()
        val release = {
            for (connection in connections) connection.finish()
            helper.close()
        }
        try {
            val open = driver(SupportSQLiteDriver(helper))
            fun connect() = SqlConnection(open.open(name)).also { connections += it }
            val writer = connect()
            return FileOpening.Opened(writer, List(minOf(readers, walReaders())) { connect() }, release)
        } catch (_: RefusedFile) {
            release()
            return FileOpening.Refused
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun delete() {
        SQLiteDatabase.deleteDatabase(file)
    }

    // Read-only and without localized collators, so the framework writes nothing to a file it may refuse.
    private fun acceptable(file: File, schema: SqlSchema): Boolean {
        val database =
            try {
                SQLiteDatabase.openDatabase(
                    file,
                    SQLiteDatabase.OpenParams.Builder()
                        .setOpenFlags(SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                        .setErrorHandler {}
                        .build(),
                )
            } catch (error: Exception) {
                return storageError(error).kind != StorageError.Kind.corrupt
            }
        try {
            val version = database.version
            val tables = database.otherTables()
            return version == schema.version && database.accepts(schema) || version == 0 && tables == 0L
        } catch (error: Exception) {
            return storageError(error).kind != StorageError.Kind.corrupt
        } finally {
            database.close()
        }
    }

    // The framework writes the first page when it opens a file, after which auto_vacuum can't change.
    private fun bootstrap(file: File) {
        file.parentFile?.mkdirs()
        val database =
            SQLiteDatabase.openDatabase(
                file,
                SQLiteDatabase.OpenParams.Builder()
                    .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    .setJournalMode("DELETE")
                    .setErrorHandler {}
                    .build(),
            )
        try {
            database.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
            database.enableWriteAheadLogging()
        } finally {
            database.close()
        }
    }

    private inner class Callback(private val schema: SqlSchema) : SupportSQLiteOpenHelper.Callback(schema.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE)
            val connection = SqlConnection(SupportSQLiteConnection(db))
            connection.configureWriter()
            connection.finish()
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            val tables =
                db.query("SELECT count(*) FROM sqlite_master WHERE name != 'android_metadata'").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            if (tables > 0) throw RefusedFile()
            for (statement in schema.statements) db.execSQL(statement)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = throw RefusedFile()

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
            throw RefusedFile()

        override fun onCorruption(db: SupportSQLiteDatabase) {}
    }
}

private fun SQLiteDatabase.accepts(schema: SqlSchema): Boolean {
    val query = schema.accepts ?: return true
    return try {
        rawQuery(query, null).use { it.moveToFirst() }
    } catch (error: Exception) {
        if (!refusesSchema(error)) throw error
        false
    }
}

private fun SQLiteDatabase.otherTables(): Long =
    rawQuery("SELECT count(*) FROM sqlite_master WHERE name != 'android_metadata'", null).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }
