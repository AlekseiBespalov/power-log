package app.powerlog.application.imports

import app.powerlog.application.Executors
import app.powerlog.application.FactReader
import app.powerlog.application.FactSink
import app.powerlog.application.FactStreams
import app.powerlog.application.StoreFailure
import app.powerlog.application.api.Answer
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.answer
import app.powerlog.application.api.failure
import app.powerlog.application.api.newId
import app.powerlog.application.api.picked
import app.powerlog.application.api.readFailure
import app.powerlog.application.orThrow
import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.domain.core.analysis.statistics.MetricReducer
import app.powerlog.domain.core.analysis.statistics.MetricStatistics
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.imports.ImportCounts
import app.powerlog.domain.imports.ImportCountsReducer
import app.powerlog.domain.imports.ImportPolicy
import app.powerlog.domain.imports.ImportReader
import app.powerlog.domain.imports.ImportResult
import app.powerlog.domain.imports.ImportedRows
import kotlinx.coroutines.withContext

internal class Imports(
    private val executors: Executors,
    private val picker: FilePicker,
    private val memory: MemoryMeasurementRepository,
    private val closed: (RideId) -> Unit,
) {
    private class Unreadable : Exception()

    suspend fun open(): Outcome<ImportedRideView> {
        val file =
            when (val picked = picker.picked()) {
                is Answer.Ok -> picked.value
                is Answer.Failed ->
                    return when (picked.error.kind) {
                        PortErrorKind.cancelled -> Outcome.Cancelled
                        PortErrorKind.uiUnavailable,
                        PortErrorKind.noUserActivation -> Outcome.Failed(failure(FailureCode.uiUnavailable))
                        else -> Outcome.Failed(failure(FailureCode.importUnreadable))
                    }
            }
        return try {
            withContext(executors.scans) { imported(file) }
        } catch (_: Unreadable) {
            Outcome.Failed(failure(FailureCode.importUnreadable))
        } catch (failed: StoreFailure) {
            Outcome.Failed(readFailure(failed.failed.error))
        } finally {
            file.close()
        }
    }

    fun close(importId: RideId): Outcome<Unit> {
        memory.close(importId)
        closed(importId)
        return Outcome.Ok(Unit)
    }

    private suspend fun imported(file: PickedFile): Outcome<ImportedRideView> {
        if (file.sizeBytes > ImportPolicy.maxBytes) throw Unreadable()
        val importId = RideId(newId())
        memory.openImported(importId, ImportPolicy.maxSamples, ImportPolicy.maxElapsedUs)
        var kept = false
        try {
            val reader = ImportReader()
            val counts = ImportCountsReducer()
            val text = Utf8Chunks()
            while (true) {
                val chunk =
                    when (val read = answer<ByteArray?> { file.read(ImportsPolicy.readChunkBytes, it) }) {
                        is Answer.Ok -> read.value ?: break
                        is Answer.Failed -> throw Unreadable()
                    }
                append(importId, reader.feed(text.decode(chunk)), counts)
            }
            append(importId, reader.feed(text.finish()), counts)
            val read = reader.finish() as? ImportResult.Read ?: throw Unreadable()
            appendPage(importId, read.rows, counts)
            memory.finishImported(importId, read.capabilities)
            val view = ImportedRideView(importId, file.name, summary(importId, counts.result()))
            kept = true
            return Outcome.Ok(view)
        } finally {
            if (!kept) memory.close(importId)
        }
    }

    private fun append(importId: RideId, result: ImportResult, counts: ImportCountsReducer) {
        val pages = (result as? ImportResult.Rows)?.pages ?: throw Unreadable()
        for (page in pages) appendPage(importId, page, counts)
    }

    private fun appendPage(importId: RideId, rows: ImportedRows, counts: ImportCountsReducer) {
        if (rows.rows == 0) return
        if (!memory.appendImported(importId, rows)) throw Unreadable()
        counts.append(rows)
    }

    private suspend fun summary(importId: RideId, counts: ImportCounts): ImportedSummaryView {
        val sessions = memory.sessions(importId).orThrow().associateBy { it.sessionNo }
        val range = memory.extent(importId).orThrow()?.telemetry ?: TimeRange(0, 0)
        val sink = SummarySink(range)
        FactReader(memory, importId, sessions).feed(range, FactStreams(true, false, null), sink)
        val power = sink.power()
        val cadence = sink.cadence()
        return ImportedSummaryView(
            samples = sink.samples,
            durationUs = sink.durationUs(),
            gaps = counts.gaps,
            clockAdjustments = counts.clockAdjustments,
            averagePowerW = power?.let { average(MetricId.humanPowerW, it) },
            peakPowerW = power?.maximum?.let { it.valueQ / MetricCatalog.of(MetricId.humanPowerW).quantum.toDouble() },
            averageCadenceRpm = cadence?.let { average(MetricId.cadenceRpm, it) },
            riderWorkWh =
                power
                    ?.integralQUs
                    ?.takeIf { power.coveredUs > 0 }
                    ?.let {
                        it / MetricCatalog.of(MetricId.humanPowerW).quantum.toDouble() / MICROSECONDS_PER_HOUR
                    },
        )
    }

    private fun average(metric: MetricId, statistics: MetricStatistics): Double? {
        val integral = statistics.integralQUs ?: return null
        if (statistics.coveredUs <= 0) return null
        return integral / MetricCatalog.of(metric).quantum.toDouble() / statistics.coveredUs
    }

    private class SummarySink(range: TimeRange) : FactSink {
        private val power = MetricReducer(MetricId.humanPowerW, range)
        private val cadence = MetricReducer(MetricId.cadenceRpm, range)
        private var firstUs: Long? = null
        private var lastUs: Long? = null
        var samples = 0
            private set

        override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
            if (to <= from) return
            power.append(rows, from, to, sessions)
            cadence.append(rows, from, to, sessions)
            if (firstUs == null) firstUs = rows.elapsedUs[from].toLong()
            lastUs = rows.elapsedUs[to - 1].toLong()
            samples += to - from
        }

        override fun append(fact: Fact) = Unit

        fun durationUs(): Long = (lastUs ?: 0L) - (firstUs ?: 0L)

        fun power(): MetricStatistics? = (power.result() as? Bounded.Done)?.value?.takeIf { it.count > 0 }

        fun cadence(): MetricStatistics? = (cadence.result() as? Bounded.Done)?.value?.takeIf { it.count > 0 }
    }

    private class Utf8Chunks {
        private var carry = ByteArray(0)

        fun decode(chunk: ByteArray): String {
            val bytes = if (carry.isEmpty()) chunk else carry + chunk
            val end = completeEnd(bytes)
            carry = bytes.copyOfRange(end, bytes.size)
            return bytes.decodeToString(0, end)
        }

        fun finish(): String = carry.decodeToString().also { carry = ByteArray(0) }

        private fun completeEnd(bytes: ByteArray): Int {
            var index = bytes.size - 1
            while (index >= 0 && index >= bytes.size - 4) {
                val byte = bytes[index].toInt() and 0xFF
                if (byte and 0xC0 != 0x80) {
                    val length =
                        when {
                            byte >= 0xF0 -> 4
                            byte >= 0xE0 -> 3
                            byte >= 0xC0 -> 2
                            else -> 1
                        }
                    return if (index + length > bytes.size) index else bytes.size
                }
                index--
            }
            return bytes.size
        }
    }

    private companion object {
        const val MICROSECONDS_PER_HOUR = 3_600_000_000.0
    }
}
