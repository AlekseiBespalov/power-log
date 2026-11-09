package app.powerlog.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.time.ClockReading
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object AppleSqlPlatform : SqlTestPlatform {
    private val files = NSFileManager.defaultManager

    override fun newDirectory(): String {
        val path = NSTemporaryDirectory() + "power-log-store-" + NSUUID().UUIDString
        files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        return path
    }

    override fun files(directory: String): Map<String, ByteArray> =
        files
            .contentsOfDirectoryAtPath(directory, error = null)
            .orEmpty()
            .map { it as String }
            .associateWith { name ->
                files.contentsAtPath("$directory/$name")?.toByteArray() ?: ByteArray(0)
            }

    override fun write(path: String, bytes: ByteArray) {
        bytes
            .usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
            .writeToFile(path, true)
    }

    override fun length(path: String): Long =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun delete(directory: String) {
        files.removeItemAtPath(directory, error = null)
    }

    override fun sqlite(path: String, statements: List<String>) {
        val connection = NativeSQLiteDriver().open(path)
        try {
            for (statement in statements) connection.execSQL(statement)
        } finally {
            connection.close()
        }
    }

    override fun walSqlite(path: String, statements: List<String>) {
        val source = "$path.source"
        val connection = NativeSQLiteDriver().open(source)
        try {
            connection.execSQL("PRAGMA journal_mode = WAL")
            for (statement in statements) connection.execSQL(statement)
            for (suffix in listOf("", "-wal")) {
                write(path + suffix, checkNotNull(files.contentsAtPath(source + suffix)).toByteArray())
            }
        } finally {
            connection.close()
            files.removeItemAtPath(source, error = null)
        }
    }

    override fun executors(): TestExecutors = AppleTestExecutors()

    override fun stores(
        directory: String?,
        driver: (SQLiteDriver) -> SQLiteDriver,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        rulesVersion: Int,
        layout: Int,
    ): SqlStores {
        requireNotNull(directory) { "the Apple runner opens files only" }
        return AppleStores.files(
            directory,
            directory,
            executors,
            log,
            { ClockReading(0, 0) },
            driver(NativeSQLiteDriver()),
            rulesVersion,
            layout,
        )
    }

    private fun NSData.toByteArray(): ByteArray {
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return bytes
    }
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class AppleTestExecutors : TestExecutors {
    private val dispatchers = mutableListOf<CloseableCoroutineDispatcher>()

    override val store: StoreExecutors =
        StoreExecutors(
            archiveWriter = single("archive"),
            archiveReads = pool("archive-reads", ARCHIVE_READERS),
            cacheWriter = single("cache"),
            cacheReads = pool("cache-reads", CACHE_READERS),
            log = single("log"),
        )

    override fun close() {
        for (dispatcher in dispatchers) dispatcher.close()
    }

    private fun single(name: String) = newSingleThreadContext("power-log-$name").also { dispatchers += it }

    private fun pool(name: String, threads: Int) =
        newFixedThreadPoolContext(threads, "power-log-$name").also { dispatchers += it }
}
