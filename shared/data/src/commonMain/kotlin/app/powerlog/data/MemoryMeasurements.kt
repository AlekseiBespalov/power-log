package app.powerlog.data

import app.powerlog.application.store.Extent
import app.powerlog.application.store.HealthKey
import app.powerlog.application.store.HealthSamplePage
import app.powerlog.application.store.HealthSourceFilter
import app.powerlog.application.store.ImportedPage
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.LocationPage
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.NearestTarget
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.application.store.TelemetryPage
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.numeric.FixedPoint
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.imports.ImportedRows
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val CHUNK_ROWS = 4_096

private const val MAX_SAFE_QUANTA = 9_007_199_254_740_991L

@OptIn(ExperimentalAtomicApi::class)
public class MemoryMeasurements : MemoryMeasurementRepository {
    private val logs = AtomicReference<Map<RideId, MemoryLog>>(emptyMap())

    override fun openPreview(rideId: RideId, maxReadings: Int) {
        update { it + (rideId to PreviewLog(maxReadings)) }
    }

    override fun addSession(rideId: RideId, session: ControllerSession) {
        (logs.load()[rideId] as? PreviewLog)?.addSession(session)
    }

    override fun appendPreview(rideId: RideId, samples: List<TelemetrySample>) {
        (logs.load()[rideId] as? PreviewLog)?.append(samples)
    }

    override fun openImported(rideId: RideId, maxReadings: Int, maxElapsedUs: Long) {
        update { it + (rideId to ImportedLog(rideId, maxReadings, maxElapsedUs)) }
    }

    override fun appendImported(rideId: RideId, rows: ImportedRows): Boolean =
        (logs.load()[rideId] as? ImportedLog)?.append(rows) ?: false

    override fun finishImported(rideId: RideId, capabilities: ControllerCapabilities) {
        (logs.load()[rideId] as? ImportedLog)?.finish(capabilities)
    }

    override suspend fun importedRows(rideId: RideId, after: Int?, limit: Int): StoreResult<ImportedPage> =
        StoreResult.Ok(
            (logs.load()[rideId] as? ImportedLog)?.page(after, limit) ?: ImportedPage(NO_IMPORTED_ROWS, null)
        )

    override fun close(rideId: RideId) {
        update { it - rideId }
    }

    override suspend fun extent(rideId: RideId): StoreResult<Extent?> = StoreResult.Ok(rows(rideId)?.extent())

    override suspend fun sessions(rideId: RideId): StoreResult<List<ControllerSession>> =
        StoreResult.Ok(rows(rideId)?.sessions?.sortedBy { it.sessionNo }.orEmpty())

    override suspend fun events(rideId: RideId): StoreResult<List<RideEvent>> = StoreResult.Ok(emptyList())

    override suspend fun telemetry(
        rideId: RideId,
        range: TimeRange,
        after: TelemetryKey?,
        limit: Int,
    ): StoreResult<TelemetryPage> = StoreResult.Ok(rows(rideId)?.page(range, after, limit) ?: EMPTY_PAGE)

    override suspend fun locations(
        rideId: RideId,
        range: TimeRange,
        after: LocationKey?,
        limit: Int,
    ): StoreResult<LocationPage> = StoreResult.Ok(LocationPage(emptyList(), null))

    override suspend fun health(
        rideId: RideId,
        range: TimeRange,
        metric: HealthMetric?,
        after: HealthKey?,
        limit: Int,
        source: HealthSourceFilter?,
    ): StoreResult<HealthSamplePage> = StoreResult.Ok(HealthSamplePage(emptyList(), null))

    override suspend fun nearest(
        rideId: RideId,
        target: NearestTarget,
        atUs: Long,
        before: Boolean,
        withinUs: Long,
    ): StoreResult<Long?> {
        if (target !is NearestTarget.Telemetry || target.metric !in TELEMETRY_METRICS) return StoreResult.Ok(null)
        return StoreResult.Ok(rows(rideId)?.nearest(target.metric, atUs, before, withinUs))
    }

    override suspend fun lastFact(rideId: RideId): StoreResult<RetainedEnd?> = StoreResult.Ok(rows(rideId)?.lastFact())

    private fun rows(rideId: RideId): MemoryRows? = logs.load()[rideId]?.rows()

    private fun update(change: (Map<RideId, MemoryLog>) -> Map<RideId, MemoryLog>) {
        while (true) {
            val current = logs.load()
            if (logs.compareAndSet(current, change(current))) return
        }
    }

    private companion object {
        val EMPTY_PAGE =
            TelemetryPage(
                TelemetryRows(
                    0,
                    DoubleArray(0),
                    IntArray(0),
                    DoubleArray(0),
                    DoubleArray(0),
                    IntArray(0),
                    IntArray(0),
                    emptyMap(),
                ),
                null,
            )

        val NO_IMPORTED_ROWS =
            ImportedRows(
                0,
                DoubleArray(0),
                DoubleArray(0),
                DoubleArray(0),
                IntArray(0),
                IntArray(0),
                IntArray(0),
                ByteArray(0),
                IntArray(1),
                emptyMap(),
            )
    }
}

private interface MemoryLog {
    fun rows(): MemoryRows
}

private open class Chunk {
    val elapsedUs = DoubleArray(CHUNK_ROWS)
    val sessionNo = IntArray(CHUNK_ROWS)
    val seq = DoubleArray(CHUNK_ROWS)
    val utcUs = DoubleArray(CHUNK_ROWS)
    val activeInterval = IntArray(CHUNK_ROWS)
    val run = IntArray(CHUNK_ROWS)

    // Replaced, never changed in place, so a reader always sees a whole map.
    @Volatile var metrics: Map<MetricId, DoubleArray> = emptyMap()

    fun column(metric: MetricId): DoubleArray {
        metrics[metric]?.let {
            return it
        }
        val column = DoubleArray(CHUNK_ROWS)
        column.fill(Double.NaN)
        metrics = metrics + (metric to column)
        return column
    }
}

// An imported row keeps the file's SI values in metrics, its connection in sessionNo and its index in seq.
private class ImportedChunk : Chunk() {
    val elapsedSeconds = DoubleArray(CHUNK_ROWS)
    val timestampEnd = IntArray(CHUNK_ROWS)
}

// Rows [start, start + size) of the chunks, in key order; a writer only fills positions past them.
private class MemoryRows(
    val chunks: List<Chunk>,
    val start: Int,
    val size: Int,
    val sessions: List<ControllerSession>,
    val revision: Long,
    private val imported: Boolean = false,
) {
    private fun chunk(row: Int): Chunk = chunks[(start + row) / CHUNK_ROWS]

    private fun index(row: Int): Int = (start + row) % CHUNK_ROWS

    fun elapsed(row: Int): Long = chunk(row).elapsedUs[index(row)].toLong()

    fun lastFact(): RetainedEnd? =
        if (size == 0) null else RetainedEnd(chunk(size - 1).utcUs[index(size - 1)].toLong(), elapsed(size - 1))

    fun extent(): Extent =
        Extent(
            revision = revision,
            telemetry = if (size == 0) null else TimeRange(elapsed(0), elapsed(size - 1) + 1),
            locations = null,
            health = null,
            healthMetrics = emptySet(),
            healthProducers = emptySet(),
        )

    fun page(range: TimeRange, after: TelemetryKey?, limit: Int): TelemetryPage {
        var first = firstRow { elapsed(it) >= range.startUs }
        if (after != null) {
            val next =
                if (imported) (after.seq + 1).coerceIn(0, size.toLong()).toInt()
                else firstRow { compare(it, after) > 0 }
            first = maxOf(first, next)
        }
        var last = first
        while (last < size && last - first < limit + 1 && elapsed(last) < range.endUs) last++
        val count = minOf(last - first, limit)
        val rows = copy(first, count)
        val more = last - first > limit && count > 0
        val next =
            if (more) TelemetryKey(elapsed(first + count - 1), rows.sessionNo[count - 1], rows.seq[count - 1].toLong())
            else null
        return TelemetryPage(rows, next)
    }

    fun nearest(metric: MetricId, atUs: Long, before: Boolean, withinUs: Long): Long? {
        if (before) {
            val bound = saturatingMinus(atUs, withinUs)
            var row = firstRow { elapsed(it) >= atUs } - 1
            while (row >= 0 && elapsed(row) >= bound) {
                if (reports(row, metric)) return elapsed(row)
                row--
            }
        } else {
            val bound = saturatingPlus(atUs, withinUs)
            var row = firstRow { elapsed(it) >= atUs }
            while (row < size && elapsed(row) <= bound) {
                if (reports(row, metric)) return elapsed(row)
                row++
            }
        }
        return null
    }

    private fun reports(row: Int, metric: MetricId): Boolean =
        !quanta(metric, value(chunk(row), index(row), metric)).isNaN()

    // A file from before 0.6 holds controllerSpeedMps without speedRaw, whose raw value is the speed in km/h.
    private fun value(chunk: Chunk, index: Int, metric: MetricId): Double {
        val stored = chunk.metrics[metric]?.get(index) ?: Double.NaN
        if (!imported || metric != MetricId.speedRaw || !stored.isNaN()) return stored
        val speed = chunk.metrics[MetricId.controllerSpeedMps]?.get(index) ?: return Double.NaN
        return speed * 3.6
    }

    // An imported value is the file's SI value; one too large for its metric's quanta reads as absent.
    private fun quanta(metric: MetricId, value: Double): Double {
        if (!imported || value.isNaN()) return value
        val quanta =
            try {
                FixedPoint.quantize(value, MetricCatalog.of(metric).quantum)
            } catch (_: ArithmeticException) {
                return Double.NaN
            }
        return if (quanta > MAX_SAFE_QUANTA || quanta < -MAX_SAFE_QUANTA) Double.NaN else quanta.toDouble()
    }

    private fun columns(chunk: Chunk): Set<MetricId> {
        if (!imported) return chunk.metrics.keys - MetricId.controllerSpeedMps
        return if (MetricId.controllerSpeedMps in chunk.metrics) chunk.metrics.keys + MetricId.speedRaw
        else chunk.metrics.keys
    }

    private fun compare(row: Int, key: TelemetryKey): Int {
        val chunk = chunk(row)
        val index = index(row)
        val elapsed = chunk.elapsedUs[index].toLong().compareTo(key.elapsedUs)
        if (elapsed != 0) return elapsed
        val session = chunk.sessionNo[index].compareTo(key.sessionNo)
        if (session != 0) return session
        return chunk.seq[index].toLong().compareTo(key.seq)
    }

    // The first row for which the condition holds; it holds for every row after it.
    private fun firstRow(condition: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (condition(middle)) high = middle else low = middle + 1
        }
        return low
    }

    private fun copy(first: Int, count: Int): TelemetryRows {
        val metrics = LinkedHashMap<MetricId, DoubleArray>()
        val elapsedUs = DoubleArray(count)
        val sessionNo = IntArray(count)
        val seq = DoubleArray(count)
        val utcUs = DoubleArray(count)
        val activeInterval = IntArray(count)
        val run = IntArray(count)
        var columnsOf: Chunk? = null
        var kept = emptySet<MetricId>()
        for (row in 0 until count) {
            val chunk = chunk(first + row)
            val index = index(first + row)
            elapsedUs[row] = chunk.elapsedUs[index]
            sessionNo[row] = chunk.sessionNo[index]
            seq[row] = chunk.seq[index]
            utcUs[row] = chunk.utcUs[index]
            activeInterval[row] = chunk.activeInterval[index]
            run[row] = chunk.run[index]
            if (chunk !== columnsOf) {
                columnsOf = chunk
                kept = columns(chunk)
            }
            for (metric in kept) {
                val value = quanta(metric, value(chunk, index, metric))
                if (!value.isNaN() || metric == MetricId.controllerSpeedMps)
                    metrics.getOrPut(metric) { DoubleArray(count) { Double.NaN } }[row] = value
            }
        }
        return TelemetryRows(count, elapsedUs, sessionNo, seq, utcUs, activeInterval, run, metrics)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class PreviewLog(private val maxReadings: Int) : MemoryLog {
    private val current = AtomicReference(MemoryRows(emptyList(), 0, 0, emptyList(), 0))

    override fun rows(): MemoryRows = current.load()

    fun addSession(session: ControllerSession) {
        val rows = current.load()
        current.store(MemoryRows(rows.chunks, rows.start, rows.size, rows.sessions + session, rows.revision + 1))
    }

    fun append(samples: List<TelemetrySample>) {
        if (samples.isEmpty()) return
        val rows = current.load()
        var chunks = rows.chunks
        var start = rows.start
        var size = rows.size
        for (sample in samples) {
            val position = start + size
            if (position == chunks.size * CHUNK_ROWS) chunks = chunks + Chunk()
            val chunk = chunks[position / CHUNK_ROWS]
            val index = position % CHUNK_ROWS
            chunk.elapsedUs[index] = sample.elapsedUs.toDouble()
            chunk.sessionNo[index] = sample.sessionNo
            chunk.seq[index] = sample.seq.toDouble()
            chunk.utcUs[index] = sample.utcUs.toDouble()
            chunk.activeInterval[index] = sample.activeInterval ?: 0
            chunk.run[index] = sample.run
            for ((metric, quanta) in sample.quanta) chunk.column(metric)[index] = quanta.toDouble()
            size++
            if (size > maxReadings) {
                start++
                size--
                if (start == CHUNK_ROWS) {
                    chunks = chunks.drop(1)
                    start = 0
                }
            }
        }
        current.store(MemoryRows(chunks, start, size, rows.sessions, rows.revision + 1))
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class ImportedLog(private val rideId: RideId, private val maxReadings: Int, private val maxElapsedUs: Long) :
    MemoryLog {
    // The timestamp texts of rows [0, size) are timestamps[0, timestampEnd of the last row).
    private class State(
        val chunks: List<ImportedChunk>,
        val size: Int,
        val timestamps: ByteArray,
        val connections: Set<Int>,
        val capabilities: ControllerCapabilities?,
        val revision: Long,
    )

    private val current = AtomicReference(State(emptyList(), 0, ByteArray(0), emptySet(), null, 0))

    override fun rows(): MemoryRows {
        val state = current.load()
        val capabilities = state.capabilities
        val sessions =
            if (capabilities == null) emptyList()
            else state.connections.map { ControllerSession(rideId, it, null, capabilities) }
        return MemoryRows(state.chunks, 0, state.size, sessions, state.revision, imported = true)
    }

    fun append(rows: ImportedRows): Boolean {
        val state = current.load()
        if (rows.rows > maxReadings - state.size) return false
        if (rows.rows == 0) return true
        for (row in 0 until rows.rows) if (rows.elapsedUs[row] > maxElapsedUs) return false
        var chunks = state.chunks
        var timestamps = state.timestamps
        var used = end(state)
        val bytes = rows.timestampOffsets[rows.rows] - rows.timestampOffsets[0]
        if (used + bytes > timestamps.size) timestamps = timestamps.copyOf(maxOf(used + bytes, timestamps.size * 2))
        rows.timestamps.copyInto(timestamps, used, rows.timestampOffsets[0], rows.timestampOffsets[rows.rows])
        val connections = LinkedHashSet(state.connections)
        for (row in 0 until rows.rows) {
            val position = state.size + row
            if (position == chunks.size * CHUNK_ROWS) chunks = chunks + ImportedChunk()
            val chunk = chunks[position / CHUNK_ROWS]
            val index = position % CHUNK_ROWS
            chunk.elapsedUs[index] = rows.elapsedUs[row]
            chunk.sessionNo[index] = rows.connection[row]
            chunk.seq[index] = position.toDouble()
            chunk.utcUs[index] = rows.utcUs[row]
            chunk.activeInterval[index] = rows.activeInterval[row]
            chunk.run[index] = rows.run[row]
            chunk.elapsedSeconds[index] = rows.elapsedSeconds[row]
            used += rows.timestampOffsets[row + 1] - rows.timestampOffsets[row]
            chunk.timestampEnd[index] = used
            for ((metric, values) in rows.values) {
                val value = values[row]
                if (!value.isNaN()) chunk.column(metric)[index] = value
            }
            connections += rows.connection[row]
        }
        current.store(
            State(
                chunks,
                state.size + rows.rows,
                timestamps,
                connections.sorted().toSet(),
                state.capabilities,
                state.revision + 1,
            )
        )
        return true
    }

    fun finish(capabilities: ControllerCapabilities) {
        val state = current.load()
        current.store(
            State(state.chunks, state.size, state.timestamps, state.connections, capabilities, state.revision)
        )
    }

    fun page(after: Int?, limit: Int): ImportedPage {
        val state = current.load()
        val first = ((after ?: -1) + 1).coerceIn(0, state.size)
        val count = minOf(maxOf(1, limit), state.size - first)
        fun chunk(row: Int) = state.chunks[row / CHUNK_ROWS]
        val from = if (first == 0) 0 else chunk(first - 1).timestampEnd[(first - 1) % CHUNK_ROWS]
        val metrics = LinkedHashMap<MetricId, DoubleArray>()
        val utcUs = DoubleArray(count)
        val elapsedUs = DoubleArray(count)
        val elapsedSeconds = DoubleArray(count)
        val activeInterval = IntArray(count)
        val run = IntArray(count)
        val connection = IntArray(count)
        val offsets = IntArray(count + 1)
        for (row in 0 until count) {
            val chunk = chunk(first + row)
            val index = (first + row) % CHUNK_ROWS
            utcUs[row] = chunk.utcUs[index]
            elapsedUs[row] = chunk.elapsedUs[index]
            elapsedSeconds[row] = chunk.elapsedSeconds[index]
            activeInterval[row] = chunk.activeInterval[index]
            run[row] = chunk.run[index]
            connection[row] = chunk.sessionNo[index]
            offsets[row + 1] = chunk.timestampEnd[index] - from
            for ((metric, values) in chunk.metrics) {
                val value = values[index]
                if (!value.isNaN()) metrics.getOrPut(metric) { DoubleArray(count) { Double.NaN } }[row] = value
            }
        }
        val rows =
            ImportedRows(
                count,
                utcUs,
                elapsedUs,
                elapsedSeconds,
                activeInterval,
                run,
                connection,
                state.timestamps.copyOfRange(from, from + offsets[count]),
                offsets,
                metrics,
            )
        return ImportedPage(rows, if (first + count < state.size) first + count - 1 else null)
    }

    private fun end(state: State): Int =
        if (state.size == 0) 0
        else state.chunks[(state.size - 1) / CHUNK_ROWS].timestampEnd[(state.size - 1) % CHUNK_ROWS]
}

internal fun saturatingMinus(value: Long, amount: Long): Long =
    if (amount > 0 && value < Long.MIN_VALUE + amount) Long.MIN_VALUE else value - amount

internal fun saturatingPlus(value: Long, amount: Long): Long =
    if (amount > 0 && value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount
