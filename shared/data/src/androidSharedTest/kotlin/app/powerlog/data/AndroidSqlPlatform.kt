package app.powerlog.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.SQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.StoreFile
import app.powerlog.domain.core.time.ClockReading
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

internal object AndroidSqlPlatform : SqlTestPlatform {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Application>()

    override fun newDirectory(): String {
        val directory = context.getDatabasePath(ARCHIVE_FILE).parentFile!!
        delete(directory.path)
        directory.mkdirs()
        return directory.path
    }

    override fun files(directory: String): Map<String, ByteArray> =
        File(directory).listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }

    override fun path(directory: String, file: StoreFile): String =
        when (file) {
            StoreFile.archive -> File(directory, ARCHIVE_FILE).path
            StoreFile.cache -> File(context.noBackupFilesDir, CACHE_FILE).path
        }

    override fun write(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
    }

    override fun length(path: String): Long = File(path).length()

    override fun delete(directory: String) {
        File(directory).deleteRecursively()
        SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, CACHE_FILE))
    }

    override fun sqlite(path: String, statements: List<String>) {
        val database =
            SQLiteDatabase.openDatabase(
                File(path),
                SQLiteDatabase.OpenParams.Builder()
                    .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    .setJournalMode("DELETE")
                    .build(),
            )
        try {
            for (statement in statements) database.execSQL(statement)
        } finally {
            database.close()
        }
    }

    override fun walSqlite(path: String, statements: List<String>) {
        val source = File("$path.source")
        val database =
            SQLiteDatabase.openDatabase(
                source,
                SQLiteDatabase.OpenParams.Builder()
                    .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    .setJournalMode("WAL")
                    .build(),
            )
        try {
            for (statement in statements) database.execSQL(statement)
            for (suffix in listOf("", "-wal")) File(path + suffix).writeBytes(File(source.path + suffix).readBytes())
        } finally {
            database.close()
            SQLiteDatabase.deleteDatabase(source)
        }
    }

    override fun executors(): TestExecutors = AndroidTestExecutors()

    // androidx runs EXPLAIN as a statement without rows, which the framework refuses.
    override fun explain(archivePath: String, sql: String): List<String> {
        val database =
            SQLiteDatabase.openDatabase(
                File(archivePath),
                SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    .build(),
            )
        try {
            return database.rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
            }
        } finally {
            database.close()
        }
    }

    override fun stores(
        directory: String?,
        driver: (SQLiteDriver) -> SQLiteDriver,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        rulesVersion: Int,
        layout: Int,
    ): SqlStores {
        requireNotNull(directory) { "the Android runner opens files only" }
        return AndroidStores.files(context, executors, log, { ClockReading(0, 0) }, driver, rulesVersion, layout)
    }
}

private class AndroidTestExecutors : TestExecutors {
    private val pools = mutableListOf<ExecutorService>()

    override val store: StoreExecutors =
        StoreExecutors(
            archiveWriter = pool("archive", 1),
            archiveReads = pool("archive-reads", ARCHIVE_READERS),
            cacheWriter = pool("cache", 1),
            cacheReads = pool("cache-reads", CACHE_READERS),
            log = pool("log", 1),
        )

    override fun close() {
        for (pool in pools) pool.shutdownNow()
    }

    private fun pool(name: String, threads: Int) =
        Executors.newFixedThreadPool(threads) { task -> Thread(task, "power-log-$name").apply { isDaemon = true } }
            .also { pools += it }
            .asCoroutineDispatcher()
}
