package app.powerlog.data

import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreFile
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.RefusalDetail
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

internal val ARCHIVE_SCHEMA: SqlSchema =
    SqlSchema(
        ARCHIVE_VERSION,
        ARCHIVE_DDL + "INSERT INTO app_state (k, history_revision) VALUES (1, 0)",
        accepts = "SELECT 1 FROM app_state WHERE k = 1",
    )

internal class SqlArchive(
    private val file: SqlFile,
    private val cache: SqlCache,
    private val executors: StoreExecutors,
) : StoreHost {
    private enum class Lifecycle {
        unopened,
        open,
        closed,
        corrupt,
    }

    private class Connections(
        val writer: SqlConnection,
        val readers: Channel<SqlConnection>?,
        val readerCount: Int,
        val pragmas: List<FilePragmas>,
        val release: () -> Unit,
    )

    @Volatile private var lifecycle = Lifecycle.unopened
    @Volatile private var connections: Connections? = null
    private val queue = WriterQueue()
    val notices: Notices = Notices()

    // Read and written only by writer jobs, which the queue runs one at a time.
    var historyRevision: Long = 0L

    override suspend fun open(): StoreResult<StoreOpen> = queue.exclusive {
        withContext(executors.archiveWriter) {
            val current = connections
            when {
                lifecycle == Lifecycle.open && current != null -> StoreResult.Ok(StoreOpen.Opened(current.pragmas))
                lifecycle != Lifecycle.unopened -> unavailable()
                else -> openFile()
            }
        }
    }

    private suspend fun openFile(): StoreResult<StoreOpen> {
        lifecycle = Lifecycle.closed
        val opening =
            try {
                file.open(ARCHIVE_SCHEMA)
            } catch (error: Exception) {
                return refusedOrFailed(error)
            }
        if (opening !is FileOpening.Opened) return StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unreadable))
        val revision =
            try {
                opening.writer.statement("SELECT history_revision FROM app_state WHERE k = 1") {
                    if (it.step()) it.getLong(0) else null
                }
            } catch (error: Exception) {
                if (!refusesSchema(error)) {
                    opening.release()
                    return StoreResult.Failed(storageError(error))
                }
                null
            }
        if (revision == null) {
            opening.release()
            return StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unreadable))
        }
        return try {
            historyRevision = revision
            val pragmas = listOfNotNull(opening.writer.pragmas(StoreFile.archive), cache.open())
            connections =
                Connections(opening.writer, pool(opening.readers), opening.readers.size, pragmas, opening.release)
            lifecycle = Lifecycle.open
            StoreResult.Ok(StoreOpen.Opened(pragmas))
        } catch (error: Exception) {
            opening.release()
            refusedOrFailed(error)
        }
    }

    override suspend fun close(): StoreResult<Unit> = queue.exclusive {
        withContext(executors.archiveWriter) {
            if (lifecycle != Lifecycle.corrupt) lifecycle = Lifecycle.closed
            val current = connections
            connections = null
            if (current != null) {
                current.readers?.let { readers ->
                    repeat(current.readerCount) { readers.receive() }
                    readers.close()
                }
                current.release()
            }
            cache.close()
            StoreResult.Ok(Unit)
        }
    }

    suspend fun <T> read(block: (SqlConnection) -> T): StoreResult<T> {
        val current = live() ?: return unavailable()
        val readers = current.readers ?: return withContext(executors.archiveWriter) { onWriter(block) }
        return withContext(executors.archiveReads) {
            val reader = readers.receiveCatching().getOrNull() ?: return@withContext unavailable()
            try {
                if (live() == null) unavailable() else guard { block(reader) }
            } finally {
                readers.trySend(reader)
            }
        }
    }

    suspend fun <T> write(
        keep: (T) -> Boolean = { true },
        committed: (T) -> Unit = {},
        block: (SqlConnection) -> T,
    ): StoreResult<T> = job { writer ->
        val result = writer.transaction(keep) { block(writer) }
        if (keep(result)) committed(result)
        result
    }

    suspend fun <T> job(block: suspend (SqlConnection) -> T): StoreResult<T> {
        if (live() == null) return unavailable()
        return queue.run {
            withContext(executors.archiveWriter) {
                val current = live() ?: return@withContext unavailable()
                guard { block(current.writer) }
            }
        } ?: StoreResult.Failed(StorageError(StorageError.Kind.busy))
    }

    private fun <T> onWriter(block: (SqlConnection) -> T): StoreResult<T> {
        val current = live() ?: return unavailable()
        return guard { block(current.writer) }
    }

    private fun live(): Connections? = connections.takeIf { lifecycle == Lifecycle.open }

    private inline fun <T> guard(block: () -> T): StoreResult<T> =
        try {
            StoreResult.Ok(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = storageError(error)
            if (failure.kind == StorageError.Kind.corrupt) lifecycle = Lifecycle.corrupt
            StoreResult.Failed(failure)
        }

    private fun pool(readers: List<SqlConnection>): Channel<SqlConnection>? {
        if (readers.isEmpty()) return null
        val pool = Channel<SqlConnection>(readers.size)
        for (reader in readers) pool.trySend(reader)
        return pool
    }

    private fun unavailable(): StoreResult.Failed =
        StoreResult.Failed(
            StorageError(if (lifecycle == Lifecycle.corrupt) StorageError.Kind.corrupt else StorageError.Kind.closed)
        )

    private fun refusedOrFailed(error: Exception): StoreResult<StoreOpen> {
        val failure = storageError(error)
        return if (failure.kind == StorageError.Kind.corrupt)
            StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unreadable))
        else StoreResult.Failed(failure)
    }
}
