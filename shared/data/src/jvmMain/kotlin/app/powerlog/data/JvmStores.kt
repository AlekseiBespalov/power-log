package app.powerlog.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import java.io.File

public object JvmStores {
    public fun create(
        directory: File,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
    ): Stores = files(directory, executors, log, clock).stores

    public fun diagnosticLog(directory: File, executors: StoreExecutors): DiagnosticLogRepository =
        DiagnosticLogFiles(JavaLogFiles(File(directory, "diagnostics")), executors.log)

    public fun inMemory(executors: StoreExecutors, log: DiagnosticLogRepository, clock: () -> ClockReading): Stores =
        memory(executors, log, clock).stores

    internal fun files(
        directory: File,
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
        driver: SQLiteDriver = BundledSQLiteDriver(),
        rulesVersion: Int = RULES_VERSION,
        layout: Int = CACHE_LAYOUT,
    ): SqlStores =
        SqlStores(
            file(driver, File(directory, ARCHIVE_FILE), ARCHIVE_READERS),
            file(driver, File(directory, CACHE_FILE), CACHE_READERS),
            executors,
            log,
            clock,
            rulesVersion,
            layout,
        )

    internal fun memory(
        executors: StoreExecutors,
        log: DiagnosticLogRepository,
        clock: () -> ClockReading,
        driver: SQLiteDriver = BundledSQLiteDriver(),
    ): SqlStores {
        val memory = DriverFile(driver, ":memory:", readers = 0, prepare = {}, remove = {}, readOnly = { null })
        return SqlStores(memory, memory, executors, log, clock)
    }

    private fun file(driver: SQLiteDriver, file: File, readers: Int) =
        DriverFile(
            driver,
            file.path,
            readers,
            prepare = { file.parentFile?.mkdirs() },
            remove = { for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete() },
            readOnly = { if (file.exists()) BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY) else null },
        )
}

internal actual fun typedResultCode(error: SQLiteException): Int? = null
