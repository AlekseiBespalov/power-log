@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.powerlog.runtime.diagnostics

import app.powerlog.application.port.Clock
import app.powerlog.data.LogLines
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.darwin.dispatch_queue_get_label
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.write

internal object AppleDiagnostics {
    private const val MODE_RW_R_R = 420

    private val executors =
        mapOf(
            "app.powerlog.ride" to "ride",
            "app.powerlog.health" to "health",
            "app.powerlog.maintenance" to "maintenance",
            "app.powerlog.reads" to "reads",
            "app.powerlog.archive" to "archiveWriter",
            "app.powerlog.archive.reads" to "archiveReads",
            "app.powerlog.cache" to "cacheWriter",
            "app.powerlog.cache.reads" to "cacheReads",
            "app.powerlog.log" to "log",
        )

    fun install(directory: String, clock: Clock) {
        setUnhandledExceptionHook { error ->
            record(directory, error, clock)
            terminateWithUnhandledException(error)
        }
    }

    fun record(directory: String, error: Throwable, clock: Clock) {
        val thread = executors[dispatch_queue_get_label(null)?.toKString()] ?: "other"
        val bytes = (LogLines.encode(Unexpected.crash(error, clock.read(), thread)) + "\n").encodeToByteArray()
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val file = open("$directory/$CRASH_FILE", O_WRONLY or O_CREAT or O_APPEND, MODE_RW_R_R)
        if (file < 0) return
        bytes.usePinned { write(file, it.addressOf(0), bytes.size.convert()) }
        fsync(file)
        close(file)
    }

    fun lines(directory: String): CrashLines {
        val path = "$directory/$CRASH_FILE"
        return CrashLines(
            read = { NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) },
            remove = { NSFileManager.defaultManager.removeItemAtPath(path, null) },
        )
    }

    fun osLog(line: String) {
        NSLog("PowerLog %@", NSString.create(string = line))
    }
}
