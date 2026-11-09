package app.powerlog.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.NativeSQLiteDriver
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink

public object AppleStores {
    public fun create(
        archiveDirectory: String,
        cacheDirectory: String,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
    ): Stores = files(archiveDirectory, cacheDirectory, executors, log, clock).stores

    public fun diagnosticLog(directory: String, executors: StoreExecutors): DiagnosticLogRepository =
        DiagnosticLogFiles(AppleLogFiles(directory), executors.log)

    internal fun files(
        archiveDirectory: String,
        cacheDirectory: String,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
        driver: SQLiteDriver = NativeSQLiteDriver(),
        rulesVersion: Int = RULES_VERSION,
        layout: Int = CACHE_LAYOUT,
    ): SqlStores =
        SqlStores(
            file(driver, archiveDirectory, ARCHIVE_FILE, ARCHIVE_READERS),
            file(driver, cacheDirectory, CACHE_FILE, CACHE_READERS),
            executors,
            log,
            clock,
            rulesVersion,
            layout,
        )

    private fun file(driver: SQLiteDriver, directory: String, name: String, readers: Int): DriverFile {
        val path = "$directory/$name"
        return DriverFile(
            driver,
            path,
            readers,
            prepare = { protectedDirectory(directory) },
            remove = { for (suffix in listOf("", "-wal", "-shm", "-journal")) unlink(path + suffix) },
            readOnly = {
                if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                    NativeSQLiteDriver().open(path, SQLITE_OPEN_READONLY)
                } else {
                    null
                }
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun protectedDirectory(path: String) {
    val files = NSFileManager.defaultManager
    val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
    files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = protection, error = null)
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!files.setAttributes(protection, ofItemAtPath = path, error = error.ptr)) {
            throw IllegalStateException("cannot protect $path: ${error.value?.localizedDescription}")
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AppleLogFiles(private val directory: String) : LogFiles {
    private var prepared = false

    override fun size(name: String): Long = memScoped {
        val status = alloc<stat>()
        if (stat(path(name), status.ptr) != 0) 0L else status.st_size
    }

    override fun append(name: String, bytes: ByteArray) {
        prepare()
        val file = fopen(path(name), "ab") ?: throw IllegalStateException("cannot open $name")
        try {
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
            if (written.toInt() != bytes.size) throw IllegalStateException("cannot append to $name")
        } finally {
            fclose(file)
        }
    }

    override fun read(name: String, offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val file = fopen(path(name), "rb") ?: return ByteArray(0)
        try {
            if (fseeko(file, offset, SEEK_SET) != 0) throw IllegalStateException("cannot seek in $name")
            val bytes = ByteArray(length)
            val read = bytes.usePinned { fread(it.addressOf(0), 1u, length.convert(), file) }.toInt()
            return if (read == length) bytes else bytes.copyOf(read)
        } finally {
            fclose(file)
        }
    }

    override fun truncate(name: String, length: Long) {
        if (platform.posix.truncate(path(name), length) != 0) throw IllegalStateException("cannot truncate $name")
    }

    override fun replace(source: String, target: String) {
        if (rename(path(source), path(target)) != 0) throw IllegalStateException("cannot move $source")
    }

    private fun prepare() {
        if (prepared) return
        protectedDirectory(directory)
        NSURL.fileURLWithPath(directory)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        prepared = true
    }

    private fun path(name: String): String = "$directory/$name"
}
