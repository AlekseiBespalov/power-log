package app.powerlog.data

import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.core.analysis.summary.RideSummary
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

internal class IdbCache(
    log: DiagnosticLogRepository,
    clock: () -> ClockReading,
    private val rulesVersion: Int = RULES_VERSION,
    private val layout: Int = CACHE_LAYOUT,
) : CacheRepository {
    private var database: IDBDatabase? = null
    private val queue = WriterQueue()
    private val failures = CacheFailures(log, clock)

    fun meta(): dynamic {
        val meta = record()
        meta.k = IdbArchive.CACHE
        meta.layout = layout
        meta.rulesVersion = rulesVersion
        return meta
    }

    suspend fun open(database: IDBDatabase) {
        val checked =
            IdbJob.run<Unit>(
                database,
                listOf(IdbObjectStore.appState, IdbObjectStore.derived),
                write = true,
                strict = false,
            ) {
                val appState = store(IdbObjectStore.appState)
                request(appState.get(IdbArchive.CACHE)) { meta ->
                    if (meta == null || meta.layout != layout || meta.rulesVersion != rulesVersion) {
                        store(IdbObjectStore.derived).clear()
                        appState.put(meta())
                    }
                    answer(Unit)
                }
            }
        when (checked) {
            is StoreResult.Ok -> this.database = database
            is StoreResult.Failed -> failures.report(checked.error)
        }
    }

    fun close() {
        database = null
    }

    override suspend fun summary(rideId: RideId, inputRevision: Long): RideSummary? {
        val value =
            read<ByteArray?> {
                request(store(IdbObjectStore.derived).get(key(rideId, SUMMARY, "", 0))) { entry ->
                    answer(
                        if (entry != null && long(entry.inputRevision) == inputRevision) bytes(entry.value) else null
                    )
                }
            } ?: return null
        return failures.decoded { CacheCodec.summary(value) }
    }

    override suspend fun putSummary(rideId: RideId, inputRevision: Long, summary: RideSummary) {
        val value = CacheCodec.summary(summary)
        write { derived -> derived.put(entry(rideId, SUMMARY, "", 0, inputRevision, value)) }
    }

    override suspend fun tiles(
        rideId: RideId,
        metric: MetricId,
        level: Int,
        slots: LongRange,
        inputRevision: Long,
    ): Map<Long, PlotAggregate> {
        val variant = variant(metric, level)
        val values =
            read<Map<Long, ByteArray>> {
                val found = LinkedHashMap<Long, ByteArray>()
                val range =
                    IDBKeyRange.bound(
                        key(rideId, TILE, variant, slots.first),
                        key(rideId, TILE, variant, slots.last),
                        false,
                        false,
                    )
                cursor(
                    store(IdbObjectStore.derived).openCursor(range, "next"),
                    each = { entry ->
                        val revision = long(entry.inputRevision)
                        if (revision == inputRevision || revision == 0L) found[long(entry.part)] = bytes(entry.value)
                        true
                    },
                    done = { answer(found) },
                )
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
        write { derived ->
            for ((part, value) in values) derived.put(entry(rideId, TILE, variant, part, inputRevision, value))
        }
    }

    override suspend fun dropRide(rideId: RideId) {
        write { derived -> derived.delete(rideKeys(rideId)) }
    }

    private suspend fun <T> read(start: IdbJob<T>.() -> Unit): T? {
        val database = database ?: return null
        return when (
            val result = IdbJob.run(database, listOf(IdbObjectStore.derived), write = false, strict = false, start)
        ) {
            is StoreResult.Ok -> result.value
            is StoreResult.Failed -> {
                failures.report(result.error)
                null
            }
        }
    }

    private suspend fun write(put: (IDBObjectStore) -> Unit) {
        val database = database ?: return
        val result =
            queue.run {
                IdbJob.run<Unit>(database, listOf(IdbObjectStore.derived), write = true, strict = false) {
                    put(store(IdbObjectStore.derived))
                    answer(Unit)
                }
            } ?: StoreResult.Failed(StorageError(StorageError.Kind.busy))
        if (result is StoreResult.Failed) failures.report(result.error)
    }

    private fun variant(metric: MetricId, level: Int): String = "${metric.name}/$level"

    private fun key(rideId: RideId, family: String, variant: String, part: Long): Array<Any> =
        arrayOf(rideId.value, family, variant, part.js())

    private fun entry(
        rideId: RideId,
        family: String,
        variant: String,
        part: Long,
        revision: Long,
        value: ByteArray,
    ): dynamic {
        val entry = record()
        entry.ride = rideId.value
        entry.family = family
        entry.variant = variant
        entry.part = part.js()
        entry.inputRevision = revision.js()
        val bytes = value.unsafeCast<Int8Array>()
        entry.value = Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length)
        return entry
    }

    private fun bytes(value: dynamic): ByteArray {
        val stored = value.unsafeCast<Uint8Array>()
        return Int8Array(stored.buffer, stored.byteOffset, stored.length).unsafeCast<ByteArray>()
    }

    private companion object {
        const val SUMMARY = "summary"
        const val TILE = "tile"
    }
}
