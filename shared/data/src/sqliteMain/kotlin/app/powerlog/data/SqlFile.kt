package app.powerlog.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreFile

// accepts: a query a file at this version answers a row to, or the open refuses the file
internal class SqlSchema(val version: Int, val statements: List<String>, val accepts: String? = null)

internal sealed interface FileOpening {
    class Opened(val writer: SqlConnection, val readers: List<SqlConnection>, val release: () -> Unit) : FileOpening

    data object Refused : FileOpening
}

internal interface SqlFile {
    fun open(schema: SqlSchema): FileOpening

    fun delete()
}

internal fun SqlConnection.pragmas(file: StoreFile): FilePragmas =
    transaction(keep = { false }) {
        FilePragmas(
            file,
            long("PRAGMA synchronous").toInt(),
            text("PRAGMA journal_mode"),
            long("PRAGMA auto_vacuum").toInt(),
            long("PRAGMA wal_autocheckpoint").toInt(),
        )
    }

internal fun SqlConnection.configureWriter() {
    execute("PRAGMA journal_mode = WAL")
    execute("PRAGMA synchronous = FULL")
    busyTimeout()
    execute("PRAGMA journal_size_limit = ${StorePolicy.journalSizeLimitBytes}")
    execute("PRAGMA wal_autocheckpoint = ${StorePolicy.walAutocheckpointPages}")
}

internal fun SqlConnection.busyTimeout() {
    execute("PRAGMA busy_timeout = ${StorePolicy.busyTimeoutUs / 1_000}")
}

internal fun SqlConnection.create(schema: SqlSchema) {
    transaction {
        for (statement in schema.statements) execute(statement)
        execute("PRAGMA user_version = ${schema.version}")
    }
}

// Busy and full are failures of the moment; any other failure of a schema probe means another schema.
internal fun refusesSchema(error: Exception): Boolean {
    val kind = storageError(error).kind
    return kind != StorageError.Kind.busy && kind != StorageError.Kind.full
}

internal fun SqlConnection.accepts(schema: SqlSchema): Boolean {
    val query = schema.accepts ?: return true
    return try {
        statement(query) { it.step() }
    } catch (error: Exception) {
        if (!refusesSchema(error)) throw error
        false
    }
}

internal const val SQLITE_OPEN_READONLY = 0x1

internal class DriverFile(
    private val driver: SQLiteDriver,
    private val path: String,
    private val readers: Int,
    private val prepare: () -> Unit,
    private val remove: () -> Unit,
    // null when there is no file to read
    private val readOnly: () -> SQLiteConnection?,
) : SqlFile {
    private enum class Found {
        nothing,
        current,
        foreign,
    }

    override fun open(schema: SqlSchema): FileOpening {
        prepare()
        val found = found(schema)
        if (found == Found.foreign) return FileOpening.Refused
        val raw = mutableListOf<SQLiteConnection>()
        val connections = mutableListOf<SqlConnection>()
        fun connect(): SqlConnection = SqlConnection(driver.open(path).also { raw += it }).also { connections += it }
        val release = {
            for (connection in connections) connection.finish()
            for (connection in raw) runCatching { connection.close() }
        }
        try {
            val writer = connect()
            if (found == Found.nothing) {
                writer.execute("PRAGMA auto_vacuum = INCREMENTAL")
                writer.execute("PRAGMA journal_mode = WAL")
                writer.create(schema)
            }
            writer.configureWriter()
            return FileOpening.Opened(writer, List(readers) { connect().also { it.busyTimeout() } }, release)
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun delete() {
        remove()
    }

    // A writer turns the file to WAL and its close checkpoints a WAL into the file, so only a read-only connection may
    // look at a file the open refuses.
    private fun found(schema: SqlSchema): Found {
        val connection = readOnly() ?: return Found.nothing
        val probe = SqlConnection(connection)
        try {
            val found = probe.long("PRAGMA user_version") to probe.long("SELECT count(*) FROM sqlite_master")
            return when {
                found == Pair(0L, 0L) -> Found.nothing
                found.first == schema.version.toLong() && probe.accepts(schema) -> Found.current
                else -> Found.foreign
            }
        } catch (error: Exception) {
            if (storageError(error).kind != StorageError.Kind.corrupt) throw error
            return Found.foreign
        } finally {
            probe.finish()
            runCatching { connection.close() }
        }
    }
}
