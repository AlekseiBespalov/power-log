package app.powerlog.domain.imports

import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.time.Micros
import app.powerlog.domain.core.time.Utc
import kotlin.math.abs
import kotlin.math.floor

public class ImportedRows(
    public val rows: Int,
    public val utcUs: DoubleArray,
    public val elapsedUs: DoubleArray,
    public val elapsedSeconds: DoubleArray,
    // 0: none
    public val activeInterval: IntArray,
    public val run: IntArray,
    // 0: empty, which is the imported ride's controller session 0
    public val connection: IntArray,
    // UTF-8; row i is timestamps[timestampOffsets[i], timestampOffsets[i + 1])
    public val timestamps: ByteArray,
    public val timestampOffsets: IntArray,
    // NaN: absent
    public val values: Map<MetricId, DoubleArray>,
)

public sealed interface ImportResult {
    public data class Rows(public val pages: List<ImportedRows>) : ImportResult

    public data class Read(public val rows: ImportedRows, public val capabilities: ControllerCapabilities) :
        ImportResult

    public data class Refused(public val code: FailureCode) : ImportResult
}

public class ImportReader {
    private val refusal = ImportResult.Refused(FailureCode.importUnreadable)
    private var closed = false
    private var characters = 0L
    private var started = false
    private var columns: Columns? = null
    private val row = mutableListOf<String>()
    private val cell = StringBuilder()
    private var quoted = false
    private var closedQuote = false
    private var skipLineFeed = false
    private var samples = 0
    private var previousElapsedSeconds = 0.0
    private var previousRun = 0
    private var controllerSpeed = false
    private var page: PageBuilder? = null
    private val completed = mutableListOf<ImportedRows>()

    public fun feed(chunk: String): ImportResult {
        if (closed) return refusal
        if (!read(chunk)) return refuse()
        val columns = columns
        val partial = page
        if (columns != null && partial != null && partial.rows > 0) {
            completed += partial.build()
            page = PageBuilder(columns)
        }
        return ImportResult.Rows(completed.toList()).also { completed.clear() }
    }

    public fun finish(): ImportResult {
        if (closed) return refusal
        closed = true
        if (quoted) return refusal
        if ((cell.isNotEmpty() || row.isNotEmpty() || closedQuote) && !endRow()) return refusal
        val columns = columns ?: return refusal
        val rest = page ?: PageBuilder(columns)
        return ImportResult.Read(
            rest.build(),
            ControllerCapabilities(
                if (controllerSpeed) columns.metrics.keys else columns.metrics.keys - MetricId.controllerSpeedMps,
                controllerSpeed,
            ),
        )
    }

    private fun refuse(): ImportResult {
        closed = true
        completed.clear()
        return refusal
    }

    private fun read(chunk: String): Boolean {
        var text = chunk
        if (!started && text.isNotEmpty()) {
            started = true
            if (text[0] == BYTE_ORDER_MARK) {
                characters += BYTE_ORDER_MARK_EXTRA
                text = text.substring(1)
            }
        }
        characters += text.length
        if (characters > ImportPolicy.maxBytes) return false
        for (character in text) {
            if (character != '\t' && character != '\n' && character != '\r' && character !in ' '..'~') return false
            if (skipLineFeed) {
                skipLineFeed = false
                if (character == '\n') continue
            }
            if (quoted) {
                if (character == '"') {
                    quoted = false
                    closedQuote = true
                } else cell.append(character)
            } else if (closedQuote && character == '"') {
                cell.append('"')
                quoted = true
                closedQuote = false
            } else if (character == ',') endCell()
            else if (character == '\n' || character == '\r') {
                if (!endRow()) return false
                skipLineFeed = character == '\r'
            } else if (character == '"' && cell.isEmpty() && !closedQuote) quoted = true
            else {
                if (closedQuote || character == '"') return false
                cell.append(character)
            }
            if (cell.length > ImportPolicy.maxCellChars) return false
            if (row.size > (columns?.count ?: HEADER_COLUMNS)) return false
        }
        return true
    }

    private fun endCell() {
        row += cell.toString()
        cell.clear()
        closedQuote = false
    }

    private fun endRow(): Boolean {
        endCell()
        val ok = columns?.let(::sample) ?: header()
        row.clear()
        return ok
    }

    private fun header(): Boolean {
        if (row.toSet().size != row.size) return false
        val positions = row.withIndex().associate { (index, name) -> name to index }
        val envelope = Envelope.entries.associateWith { positions[it.column] ?: -1 }
        if (envelope.getValue(Envelope.timestamp) < 0) return false
        if (envelope.getValue(Envelope.elapsedSeconds) < 0 || envelope.getValue(Envelope.run) < 0) return false
        val metrics = CHANNELS.mapNotNull { channel -> positions[channel.column]?.let { channel.metric to it } }.toMap()
        val columns = Columns(row.size, envelope, metrics)
        this.columns = columns
        page = PageBuilder(columns)
        return true
    }

    private fun sample(columns: Columns): Boolean {
        if (samples >= ImportPolicy.maxSamples || row.size != columns.count) return false
        fun cell(envelope: Envelope) = columns.envelope.getValue(envelope).let { if (it < 0) "" else row[it] }
        val timestamp = cell(Envelope.timestamp)
        val utcUs = timestampUs(timestamp) ?: return false
        val elapsedSeconds = number(cell(Envelope.elapsedSeconds)) ?: return false
        if (elapsedSeconds < 0 || elapsedSeconds > ImportPolicy.maxElapsedUs / 1_000_000.0) return false
        val run = count(cell(Envelope.run)) ?: return false
        if (run == 0 || samples > 0 && (elapsedSeconds < previousElapsedSeconds || run < previousRun)) return false
        val activeInterval = count(cell(Envelope.activeInterval)) ?: return false
        val connection = count(cell(Envelope.connection)) ?: return false
        val values = DoubleArray(CHANNELS.size) { Double.NaN }
        for ((index, channel) in CHANNELS.withIndex()) {
            val position = columns.metrics[channel.metric] ?: continue
            val text = row[position]
            if (text.isEmpty()) continue
            val value = number(text) ?: return false
            if (channel.integer && !safeInteger(value)) return false
            values[index] = value
            if (channel.metric == MetricId.controllerSpeedMps) controllerSpeed = true
        }
        val builder = page ?: PageBuilder(columns).also { page = it }
        builder.add(
            timestamp,
            utcUs,
            Micros.of(elapsedSeconds),
            elapsedSeconds,
            activeInterval,
            run,
            connection,
            values,
        )
        if (builder.rows == ImportPolicy.pageRows) {
            completed += builder.build()
            page = PageBuilder(columns)
        }
        samples++
        previousElapsedSeconds = elapsedSeconds
        previousRun = run
        return true
    }

    private enum class Envelope(val column: String) {
        timestamp("timestamp"),
        elapsedSeconds("elapsedSeconds"),
        activeInterval("activeInterval"),
        run("run"),
        connection("connection"),
    }

    private class Channel(val column: String, val metric: MetricId, val integer: Boolean = false)

    private class Columns(val count: Int, val envelope: Map<Envelope, Int>, val metrics: Map<MetricId, Int>)

    private class PageBuilder(private val columns: Columns) {
        var rows = 0
            private set

        private val utcUs = DoubleArray(ImportPolicy.pageRows)
        private val elapsedUs = DoubleArray(ImportPolicy.pageRows)
        private val elapsedSeconds = DoubleArray(ImportPolicy.pageRows)
        private val activeInterval = IntArray(ImportPolicy.pageRows)
        private val run = IntArray(ImportPolicy.pageRows)
        private val connection = IntArray(ImportPolicy.pageRows)
        private val timestamps = StringBuilder()
        private val offsets = IntArray(ImportPolicy.pageRows + 1)
        private val values = columns.metrics.keys.associateWith { DoubleArray(ImportPolicy.pageRows) }

        fun add(
            timestamp: String,
            utc: Long,
            elapsed: Long,
            seconds: Double,
            interval: Int,
            runNumber: Int,
            connectionNumber: Int,
            channelValues: DoubleArray,
        ) {
            utcUs[rows] = utc.toDouble()
            elapsedUs[rows] = elapsed.toDouble()
            elapsedSeconds[rows] = seconds
            activeInterval[rows] = interval
            run[rows] = runNumber
            connection[rows] = connectionNumber
            timestamps.append(timestamp)
            offsets[rows + 1] = timestamps.length
            for ((index, channel) in CHANNELS.withIndex()) values[channel.metric]?.set(rows, channelValues[index])
            rows++
        }

        fun build(): ImportedRows =
            ImportedRows(
                rows,
                utcUs.copyOf(rows),
                elapsedUs.copyOf(rows),
                elapsedSeconds.copyOf(rows),
                activeInterval.copyOf(rows),
                run.copyOf(rows),
                connection.copyOf(rows),
                timestamps.toString().encodeToByteArray(),
                offsets.copyOf(rows + 1),
                values.mapValues { it.value.copyOf(rows) },
            )
    }

    private companion object {
        const val BYTE_ORDER_MARK = '﻿'
        const val BYTE_ORDER_MARK_EXTRA = 3L
        const val HEADER_COLUMNS = 64
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
        val numeric = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        val positive = Regex("[1-9][0-9]{0,9}")
        val instant = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]{1,9})?Z")
        val CHANNELS =
            listOf(
                Channel("humanPowerW", MetricId.humanPowerW),
                Channel("cadenceRpm", MetricId.cadenceRpm),
                Channel("motorInputPowerW", MetricId.motorInputPowerW),
                Channel("batteryVoltageV", MetricId.batteryVoltageV),
                Channel("batteryCurrentA", MetricId.batteryCurrentA),
                Channel("motorCurrentA", MetricId.motorCurrentA),
                Channel("motorRpm", MetricId.motorRpm),
                Channel("pedalTorqueNm", MetricId.pedalTorqueNm),
                Channel("controllerTempC", MetricId.controllerTempC),
                Channel("motorTempC", MetricId.motorTempC),
                Channel("consumedAh", MetricId.consumedAh),
                Channel("consumedWh", MetricId.consumedWh),
                Channel("throttleVoltageV", MetricId.throttleVoltageV),
                Channel("faultCode", MetricId.faultCode, integer = true),
                Channel("assistLevel", MetricId.assistLevel, integer = true),
                Channel("controllerSpeedMps", MetricId.controllerSpeedMps),
                Channel("controller.raceMode", MetricId.raceMode, integer = true),
                Channel("controller.speedRaw", MetricId.speedRaw),
            )

        fun number(text: String): Double? {
            val trimmed = text.trim()
            if (!numeric.matches(trimmed)) return null
            return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        fun count(text: String): Int? =
            when {
                text.isEmpty() -> 0
                !positive.matches(text) -> null
                else -> text.toLong().takeIf { it <= Int.MAX_VALUE }?.toInt()
            }

        fun safeInteger(value: Double): Boolean = abs(value) <= MAX_SAFE_INTEGER && floor(value) == value

        fun timestampUs(text: String): Long? {
            val fields = instant.matchEntire(text)?.groupValues ?: return null
            val year = fields[1].toInt()
            val month = fields[2].toInt()
            val day = fields[3].toInt()
            if (month !in 1..12 || day !in 1..daysIn(year, month)) return null
            if (fields[4].toInt() > 23 || fields[5].toInt() > 59 || fields[6].toInt() > 59) return null
            return Utc.parseUs(text)
        }

        fun daysIn(year: Int, month: Int): Int =
            when (month) {
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                4,
                6,
                9,
                11 -> 30
                else -> 31
            }
    }
}

public object ImportPolicy {
    public const val maxBytes: Long = 268_435_456L
    public const val maxSamples: Int = 700_000
    public const val maxElapsedUs: Long = 604_800_000_000L
    public const val pageRows: Int = 4_096
    public const val gapUs: Long = 2_500_000L
    public const val clockAdjustmentUs: Long = 2_500_000L
    public const val maxCellChars: Int = 256
}

public data class ImportCounts(public val gaps: Int, public val clockAdjustments: Int)

public class ImportCountsReducer {
    private var gaps = 0
    private var clockAdjustments = 0
    private var any = false
    private var lastUtcUs = 0.0
    private var lastElapsedUs = 0.0
    private var lastInterval = 0
    private var lastRun = 0

    public fun append(rows: ImportedRows) {
        for (row in 0 until rows.rows) {
            val utcUs = rows.utcUs[row]
            val elapsedUs = rows.elapsedUs[row]
            val interval = rows.activeInterval[row]
            val run = rows.run[row]
            if (any) {
                val stepUs = elapsedUs - lastElapsedUs
                val utcStepUs = utcUs - lastUtcUs
                if (utcStepUs <= 0 || abs(utcStepUs - stepUs) > ImportPolicy.clockAdjustmentUs) clockAdjustments++
                if (
                    interval != 0 &&
                        interval == lastInterval &&
                        stepUs > 0 &&
                        (stepUs > ImportPolicy.gapUs || run != lastRun)
                )
                    gaps++
            }
            any = true
            lastUtcUs = utcUs
            lastElapsedUs = elapsedUs
            lastInterval = interval
            lastRun = run
        }
    }

    public fun result(): ImportCounts = ImportCounts(gaps, clockAdjustments)
}
