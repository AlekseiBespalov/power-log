package app.powerlog.data

import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreFile
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

internal class SqlCache(
    private val file: SqlFile,
    private val executors: StoreExecutors,
    log: DiagnosticLogRepository,
    clock: () -> ClockReading,
    private val rulesVersion: Int = RULES_VERSION,
    private val layout: Int = CACHE_LAYOUT,
) : CacheRepository {
    private class Connections(
        val writer: SqlConnection,
        val readers: Channel<SqlConnection>?,
        val readerCount: Int,
        val release: () -> Unit,
    )

    @Volatile private var connections: Connections? = null
    private val queue = WriterQueue()
    private val failures = CacheFailures(log, clock)

    private val schema: SqlSchema
        get() =
            SqlSchema(
                layout,
                CACHE_DDL + "INSERT INTO cache_meta (k, layout, rules_version) VALUES (1, $layout, $rulesVersion)",
            )

    // null: the cache could not be opened, and every call is a miss until the process restarts
    suspend fun open(): FilePragmas? =
        withContext(executors.cacheWriter) {
            try {
                val opened = current() ?: fresh()
                connections = opened
                opened.writer.pragmas(StoreFile.cache)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures.report(storageError(error))
                null
            }
        }

    suspend fun close() {
        withContext(executors.cacheWriter) {
            queue.exclusive {
                val current = connections
                connections = null
                if (current != null) {
                    current.readers?.let { readers ->
                        repeat(current.readerCount) { readers.receive() }
                        readers.close()
                    }
                    current.release()
                }
            }
        }
    }

    override suspend fun summary(rideId: RideId, inputRevision: Long): RideSummary? {
        val value =
            read { db ->
                db.statement(
                    "SELECT input_revision, value FROM derived WHERE ride = ? AND family = 'summary' " +
                        "AND variant = '' AND part = 0"
                ) {
                    it.bindText(1, rideId.value)
                    if (it.step() && it.getLong(0) == inputRevision) it.getBlob(1) else null
                }
            } ?: return null
        return failures.decoded { CacheCodec.summary(value) }
    }

    override suspend fun putSummary(rideId: RideId, inputRevision: Long, summary: RideSummary) {
        val value = CacheCodec.summary(summary)
        write { db -> db.put(rideId, "summary", "", 0, inputRevision, value) }
    }

    override suspend fun tiles(
        rideId: RideId,
        metric: MetricId,
        level: Int,
        slots: LongRange,
        inputRevision: Long,
    ): Map<Long, PlotAggregate> {
        val values =
            read { db ->
                db.statement(
                    "SELECT part, input_revision, value FROM derived WHERE ride = ? AND family = 'tile' " +
                        "AND variant = ? AND part BETWEEN ? AND ? ORDER BY part"
                ) {
                    it.bindText(1, rideId.value)
                    it.bindText(2, variant(metric, level))
                    it.bindLong(3, slots.first)
                    it.bindLong(4, slots.last)
                    buildMap<Long, ByteArray> {
                        while (it.step()) {
                            val revision = it.getLong(1)
                            if (revision == inputRevision || revision == 0L) put(it.getLong(0), it.getBlob(2))
                        }
                    }
                }
            } ?: return emptyMap()
        val tiles = LinkedHashMap<Long, PlotAggregate>()
        for ((part, value) in values) failures.decoded { CacheCodec.tile(value) }?.let { tiles[part] = it }
        return tiles
    }

    override suspend fun putTiles(
        rideId: RideId,
        metric: MetricId,
        level: Int,
        inputRevision: Long,
        tiles: Map<Long, PlotAggregate>,
    ) {
        if (tiles.isEmpty()) return
        val values = tiles.mapValues { CacheCodec.tile(it.value) }
        val variant = variant(metric, level)
        write { db -> for ((part, value) in values) db.put(rideId, "tile", variant, part, inputRevision, value) }
    }

    override suspend fun dropRide(rideId: RideId) {
        write { db ->
            db.statement("DELETE FROM derived WHERE ride = ?") {
                it.bindText(1, rideId.value)
                it.step()
            }
        }
    }

    suspend fun <T> job(block: suspend (SqlConnection) -> T): StoreResult<T> {
        val current = connections ?: return StoreResult.Failed(StorageError(StorageError.Kind.closed))
        return queue.run {
            withContext(executors.cacheWriter) {
                try {
                    StoreResult.Ok(block(current.writer))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    StoreResult.Failed(storageError(error))
                }
            }
        } ?: StoreResult.Failed(StorageError(StorageError.Kind.busy))
    }

    // null: the cache is off, or the job failed and the failure was reported.
    suspend fun <T> maintenance(block: suspend (SqlConnection) -> T): T? {
        if (connections == null) return null
        return when (val result = job(block)) {
            is StoreResult.Ok -> result.value
            is StoreResult.Failed -> {
                failures.report(result.error)
                null
            }
        }
    }

    private fun current(): Connections? {
        val opening = file.open(schema) as? FileOpening.Opened ?: return null
        val matches =
            try {
                opening.writer.statement("SELECT layout, rules_version FROM cache_meta WHERE k = 1") {
                    it.step() && it.getLong(0) == layout.toLong() && it.getLong(1) == rulesVersion.toLong()
                }
            } catch (_: Exception) {
                false
            }
        if (matches) return connections(opening)
        opening.release()
        return null
    }

    private fun fresh(): Connections {
        file.delete()
        val opening = file.open(schema) as? FileOpening.Opened ?: throw ConstraintViolation("a new cache was refused")
        return connections(opening)
    }

    private fun connections(opening: FileOpening.Opened): Connections {
        val readers =
            if (opening.readers.isEmpty()) null
            else
                Channel<SqlConnection>(opening.readers.size).also { pool ->
                    opening.readers.forEach { pool.trySend(it) }
                }
        return Connections(opening.writer, readers, opening.readers.size, opening.release)
    }

    private suspend fun <T> read(block: (SqlConnection) -> T): T? {
        val current = connections ?: return null
        val readers = current.readers ?: return withContext(executors.cacheWriter) { attempt { block(current.writer) } }
        return withContext(executors.cacheReads) {
            val reader = readers.receiveCatching().getOrNull() ?: return@withContext null
            try {
                attempt { block(reader) }
            } finally {
                readers.trySend(reader)
            }
        }
    }

    private suspend fun write(block: (SqlConnection) -> Unit) {
        if (connections == null) return
        when (val result = job { db -> db.transaction { block(db) } }) {
            is StoreResult.Ok -> Unit
            is StoreResult.Failed -> failures.report(result.error)
        }
    }

    private suspend fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failures.report(storageError(error))
            null
        }

    private fun variant(metric: MetricId, level: Int): String = "${metric.name}/$level"
}

private fun SqlConnection.put(
    ride: RideId,
    family: String,
    variant: String,
    part: Long,
    revision: Long,
    value: ByteArray,
) {
    statement(
        "INSERT OR REPLACE INTO derived (ride, family, variant, part, input_revision, value) VALUES (?, ?, ?, ?, ?, ?)"
    ) {
        it.bindText(1, ride.value)
        it.bindText(2, family)
        it.bindText(3, variant)
        it.bindLong(4, part)
        it.bindLong(5, revision)
        it.bindBlob(6, value)
        it.step()
    }
}
