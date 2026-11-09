package app.powerlog.runtime.diagnostics

import android.util.Log
import app.powerlog.application.port.Clock
import app.powerlog.application.store.LogLevel
import app.powerlog.data.LogLines
import java.io.File
import java.io.FileOutputStream

internal object AndroidDiagnostics {
    private val executors =
        listOf(
            "PowerLogRide" to "ride",
            "PowerLogHealth" to "health",
            "PowerLogMaintenance" to "maintenance",
            "PowerLogRead-" to "reads",
            "PowerLogArchiveRead-" to "archiveReads",
            "PowerLogArchive" to "archiveWriter",
            "PowerLogCacheRead-" to "cacheReads",
            "PowerLogCache" to "cacheWriter",
            "PowerLogLog" to "log",
        )

    fun install(directory: () -> File, clock: Clock) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(directory(), thread, error, clock)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun record(directory: File, thread: Thread, error: Throwable, clock: Clock) {
        val name = executors.firstOrNull { (prefix, _) -> thread.name.startsWith(prefix) }?.second ?: "other"
        val line = LogLines.encode(Unexpected.crash(error, clock.read(), name)) + "\n"
        directory.mkdirs()
        FileOutputStream(File(directory, CRASH_FILE), true).use { file ->
            file.write(line.encodeToByteArray())
            file.fd.sync()
        }
    }

    fun lines(directory: () -> File): CrashLines =
        CrashLines(
            read = { File(directory(), CRASH_FILE).takeIf(File::isFile)?.readText() },
            remove = { File(directory(), CRASH_FILE).delete() },
        )

    fun osLog(level: LogLevel, line: String) {
        when (level) {
            LogLevel.error -> Log.e("PowerLog", line)
            LogLevel.warning -> Log.w("PowerLog", line)
            LogLevel.info -> Log.i("PowerLog", line)
        }
    }
}
