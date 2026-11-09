package app.powerlog.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.time.ClockReading
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

internal object JvmSqlPlatform : SqlTestPlatform {
    override fun newDirectory(): String = Files.createTempDirectory("power-log-store").toString()

    override fun files(directory: String): Map<String, ByteArray> =
        File(directory).listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }

    override fun write(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
    }

    override fun length(path: String): Long = File(path).length()

    override fun delete(directory: String) {
        File(directory).deleteRecursively()
    }

    override fun sqlite(path: String, statements: List<String>) {
        val connection = BundledSQLiteDriver().open(path)
        try {
            for (statement in statements) connection.execSQL(statement)
        } finally {
            connection.close()
        }
    }

    override fun walSqlite(path: String, statements: List<String>) {
        val source = "$path.source"
        val connection = BundledSQLiteDriver().open(source)
        try {
            connection.execSQL("PRAGMA journal_mode = WAL")
            for (statement in statements) connection.execSQL(statement)
            for (suffix in listOf("", "-wal")) File(path + suffix).writeBytes(File(source + suffix).readBytes())
        } finally {
            connection.close()
            File(source).delete()
        }
    }

    override fun executors(): TestExecutors = JvmTestExecutors()

    override fun stores(
        directory: String?,
        driver: (SQLiteDriver) -> SQLiteDriver,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        rulesVersion: Int,
        layout: Int,
    ): SqlStores {
        val clock = { ClockReading(0, 0) }
        val base = driver(BundledSQLiteDriver())
        if (directory == null) return JvmStores.memory(executors, log, clock, base)
        return JvmStores.files(File(directory), executors, log, clock, base, rulesVersion, layout)
    }
}

private class JvmTestExecutors : TestExecutors {
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
