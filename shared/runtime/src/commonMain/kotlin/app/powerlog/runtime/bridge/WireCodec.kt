package app.powerlog.runtime.bridge

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportConnection
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.port.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class WireCodec(private val clock: Clock) {
    internal val json: Json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    internal fun <T> decode(serializer: KSerializer<T>, input: JsonElement): T =
        json.decodeFromJsonElement(serializer, input)

    internal fun <T> answer(serializer: KSerializer<T>, outcome: Outcome<T>): String =
        when (outcome) {
            is Outcome.Ok -> ok(json.encodeToString(serializer, outcome.value))
            is Outcome.Failed -> failed(outcome.failure)
            Outcome.Cancelled -> CANCELLED
        }

    internal fun answerUnit(outcome: Outcome<Unit>): String =
        when (outcome) {
            is Outcome.Ok -> ok("null")
            is Outcome.Failed -> failed(outcome.failure)
            Outcome.Cancelled -> CANCELLED
        }

    internal fun <T> next(serializer: KSerializer<T>, answer: StreamAnswer<T>): String =
        when (answer) {
            is StreamAnswer.Value -> {
                val value = json.encodeToString(serializer, answer.value)
                """{"ok":true,"revision":${answer.revision},"value":$value,"now":${clock.monoUs()}}"""
            }
            is StreamAnswer.Failed -> failed(answer.failure)
            StreamAnswer.Cancelled -> CANCELLED
        }

    internal fun page(outcome: Outcome<ExportPage>): BinaryPage =
        when (outcome) {
            is Outcome.Ok -> {
                val page = outcome.value
                val texts = StringBuilder()
                val numbers = LinkedHashMap<String, ByteArray>()
                for ((name, column) in page.columns) when (column) {
                    is ExportColumn.Texts -> {
                        if (texts.isNotEmpty()) texts.append(',')
                        texts
                            .append(json.encodeToString(String.serializer(), name))
                            .append(':')
                            .append(json.encodeToString(textColumn, column.values))
                    }
                    is ExportColumn.Numbers -> numbers[name] = littleEndian(column.values)
                }
                val next = json.encodeToString(nextColumn, page.next)
                val connections = json.encodeToString(connectionList, page.connections)
                val value =
                    """{"rows":${page.rows},"next":$next,"done":${page.done},"connections":$connections,"columns":{$texts}}"""
                BinaryPage(ok(value), numbers)
            }
            is Outcome.Failed -> BinaryPage(failed(outcome.failure), emptyMap())
            Outcome.Cancelled -> BinaryPage(CANCELLED, emptyMap())
        }

    internal fun failed(failure: ApiFailure): String =
        """{"ok":false,"failure":${json.encodeToString(ApiFailure.serializer(), failure)},"now":${clock.monoUs()}}"""

    private fun ok(value: String): String = """{"ok":true,"value":$value,"now":${clock.monoUs()}}"""

    private fun littleEndian(values: DoubleArray): ByteArray {
        val bytes = ByteArray(values.size * 8)
        for (row in values.indices) {
            val bits = values[row].toRawBits()
            for (byte in 0 until 8) bytes[row * 8 + byte] = (bits ushr (byte * 8)).toByte()
        }
        return bytes
    }

    internal companion object {
        const val CANCELLED: String = """{"cancelled":true}"""
        private val textColumn = ListSerializer(String.serializer().nullable)
        private val nextColumn = ListSerializer(Double.serializer()).nullable
        private val connectionList = ListSerializer(ExportConnection.serializer())
    }
}
