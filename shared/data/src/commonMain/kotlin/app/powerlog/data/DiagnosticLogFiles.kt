package app.powerlog.data

import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal interface LogFiles {
    // 0 for a missing file
    fun size(name: String): Long

    fun append(name: String, bytes: ByteArray)

    fun read(name: String, offset: Long, length: Int): ByteArray

    fun truncate(name: String, length: Long)

    // moves source over target, replacing it
    fun replace(source: String, target: String)
}

public class DiagnosticLogFiles
internal constructor(
    private val files: LogFiles,
    private val executor: CoroutineDispatcher,
    private val rotateBytes: Int = StorePolicy.logRotateBytes,
) : DiagnosticLogRepository {
    private var currentBytes: Long? = null
    internal var failures: Int = 0
        private set

    // Every call waits for it on the log executor, so none meets a torn last line.
    private val recovery: Job =
        CoroutineScope(executor).launch {
            currentBytes =
                try {
                    recover()
                } catch (_: Exception) {
                    null
                }
        }

    override suspend fun append(line: LogLine) {
        recovered {
            val bytes = LogLines.encode(line).encodeToByteArray() + NEWLINE
            if (bytes.size > StorePolicy.logLineBytes) {
                failures++
                return@recovered
            }
            try {
                var size = currentBytes ?: recover()
                if (size + bytes.size > rotateBytes) {
                    files.replace(CURRENT, PREVIOUS)
                    size = 0
                }
                files.append(CURRENT, bytes)
                currentBytes = size + bytes.size
            } catch (_: Exception) {
                currentBytes = null
                failures++
            }
        }
    }

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = recovered {
        try {
            if (currentBytes == null) currentBytes = recover()
            val previous = files.size(PREVIOUS)
            val total = previous + files.size(CURRENT)
            val from = after.coerceIn(0, total)
            val length = minOf(maxOf(1, maxBytes).toLong(), total - from).toInt()
            val bytes = stream(previous, from, length)
            val newline = bytes.lastIndexOf(NEWLINE)
            val chunk = if (newline >= 0 && from + length < total) bytes.copyOf(newline + 1) else bytes
            val next = from + chunk.size
            StoreResult.Ok(LogChunk(chunk, if (next < total) next else null))
        } catch (_: Exception) {
            StoreResult.Failed(StorageError(StorageError.Kind.io))
        }
    }

    private fun stream(previous: Long, from: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val head =
            if (from < previous) files.read(PREVIOUS, from, minOf(length.toLong(), previous - from).toInt())
            else ByteArray(0)
        val rest = length - head.size
        return if (rest > 0) head + files.read(CURRENT, maxOf(0L, from - previous), rest) else head
    }

    private suspend fun <T> recovered(block: () -> T): T =
        withContext(executor) {
            recovery.join()
            block()
        }

    private fun recover(): Long {
        val size = files.size(CURRENT)
        var end = size
        while (end > 0) {
            val from = maxOf(0L, end - RECOVERY_STEP)
            val bytes = files.read(CURRENT, from, (end - from).toInt())
            val newline = bytes.lastIndexOf(NEWLINE)
            if (newline >= 0) {
                end = from + newline + 1
                break
            }
            end = from
        }
        if (end < size) files.truncate(CURRENT, end)
        return end
    }

    internal companion object {
        const val CURRENT = "events.jsonl"
        const val PREVIOUS = "events.previous.jsonl"
        private const val NEWLINE = '\n'.code.toByte()
        private const val RECOVERY_STEP = 4_096L
    }
}

public object LogLines {
    private val header = setOf("v", "level", "utcUs", "monoUs", "event")

    public fun encode(line: LogLine): String = buildJsonObject {
        put("v", 2)
        put("level", line.level.name)
        put("utcUs", line.utcUs)
        put("monoUs", line.monoUs)
        put("event", line.event)
        for (field in line.fields) value(field.value)?.let { put(field.key, it) }
    }
        .toString()

    // null: a non-finite decimal, whose field is omitted
    private fun value(value: LogValue): JsonElement? =
        when (value) {
            is LogValue.Text -> JsonPrimitive(value.value)
            is LogValue.Integer -> JsonPrimitive(value.value)
            is LogValue.Decimal -> if (value.value.isFinite()) JsonPrimitive(value.value) else null
            is LogValue.Flag -> JsonPrimitive(value.value)
            is LogValue.Texts -> JsonArray(value.values.map(::JsonPrimitive))
            is LogValue.Fields ->
                JsonObject(value.fields.mapNotNull { field -> value(field.value)?.let { field.key to it } }.toMap())
        }

    // null: not a whole version 2 line
    public fun decode(text: String): LogLine? {
        val line =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
        fun number(key: String) = (line[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        fun text(key: String) = (line[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (number("v") != 2L) return null
        val level = LogLevel.entries.find { it.name == text("level") } ?: return null
        return LogLine(
            level = level,
            utcUs = number("utcUs") ?: return null,
            monoUs = number("monoUs") ?: return null,
            event = text("event") ?: return null,
            fields = fields(line.filterKeys { it !in header }) ?: return null,
        )
    }

    private fun fields(entries: Map<String, JsonElement>): List<LogField>? = entries.map { (key, element) ->
        LogField(key, decoded(element) ?: return null)
    }

    private fun decoded(element: JsonElement): LogValue? =
        when (element) {
            is JsonObject -> fields(element)?.let(LogValue::Fields)
            is JsonArray ->
                LogValue.Texts(element.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null })
            is JsonPrimitive ->
                when {
                    element.isString -> LogValue.Text(element.content)
                    element.booleanOrNull != null -> LogValue.Flag(element.boolean)
                    element.longOrNull != null -> LogValue.Integer(element.long)
                    else -> element.doubleOrNull?.let(LogValue::Decimal)
                }
        }
}
