@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.powerlog.runtime.bridge

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.charts.ChartHeadInput
import app.powerlog.application.charts.ChartSource
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportConnection
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.port.Clock
import app.powerlog.application.recording.RecordingView
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.runtime.contractFixture
import app.powerlog.runtime.wireBytesAreExact
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class ApiFixturesTest {
    private class FixedClock(private val now: Long) : Clock {
        override fun monoUs(): Long = now

        override fun utcUs(): Long = 0L

        override fun read(): ClockReading = ClockReading(now, 0L)
    }

    private object UnreadLog : DiagnosticLogRepository {
        override suspend fun append(line: LogLine) = error("no line is expected")

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
    }

    private fun codec(now: Long = 0L) = WireCodec(FixedClock(now))

    private fun elements(name: String): List<JsonElement> =
        Json.parseToJsonElement(contractFixture("api/$name.json").decodeToString()).jsonArray

    private val JsonElement.now: Long
        get() = jsonObject.getValue("now").jsonPrimitive.long

    private fun JsonElement.field(name: String): JsonElement = jsonObject.getValue(name)

    private fun sameJson(expected: JsonElement, actual: JsonElement): Boolean =
        when {
            expected is JsonObject && actual is JsonObject ->
                expected.keys.toList() == actual.keys.toList() &&
                    expected.keys.all { sameJson(expected.getValue(it), actual.getValue(it)) }
            expected is JsonArray && actual is JsonArray ->
                expected.size == actual.size && expected.indices.all { sameJson(expected[it], actual[it]) }
            expected is JsonNull || actual is JsonNull -> expected == actual
            expected is JsonPrimitive && actual is JsonPrimitive ->
                if (expected.isString || actual.isString) expected == actual
                else
                    expected.content.toDoubleOrNull()?.let { it == actual.content.toDoubleOrNull() }
                        ?: (expected == actual)
            else -> false
        }

    private fun assertWire(expected: JsonElement, actual: String) {
        if (wireBytesAreExact) assertEquals(expected.toString(), actual)
        else assertTrue(sameJson(expected, Json.parseToJsonElement(actual)), "$actual is not $expected")
    }

    private fun <T> assertValuesReencoded(name: String, serializer: KSerializer<T>) {
        val codec = codec()
        for (element in elements(name)) assertWire(
            element,
            codec.json.encodeToString(serializer, codec.decode(serializer, element)),
        )
    }

    @Test
    fun okAnswersAreHistoryPageAnswers() {
        for (element in elements("ok")) {
            val codec = codec(element.now)
            val value = codec.decode(HistoryPage.serializer(), element.field("value"))
            assertWire(element, codec.answer(HistoryPage.serializer(), Outcome.Ok(value)))
        }
    }

    @Test
    fun aUnitAnswerCarriesANullValue() {
        for (element in elements("ok-unit")) assertWire(element, codec(element.now).answerUnit(Outcome.Ok(Unit)))
    }

    @Test
    fun nextAnswersAreRecordingStateValues() {
        for (element in elements("next-ok")) {
            val codec = codec(element.now)
            val value = codec.decode(RecordingView.serializer(), element.field("value"))
            val revision = element.field("revision").jsonPrimitive.long
            assertWire(element, codec.next(RecordingView.serializer(), StreamAnswer.Value(revision, value)))
        }
    }

    @Test
    fun nextRequestsCarryChartHeadParams() {
        val codec = codec()
        for (element in elements("next-request")) {
            val params = codec.decode(ChartHeadInput.serializer(), element.field("params"))
            val after = element.field("after").jsonPrimitive.longOrNull
            val key = element.field("key").jsonPrimitive.content
            val written = buildJsonObject {
                put("params", codec.json.encodeToJsonElement(ChartHeadInput.serializer(), params))
                put("after", JsonPrimitive(after))
                put("key", JsonPrimitive(key))
            }
            assertWire(element, written.toString())
        }
    }

    @Test
    fun nextRequestsReachTheirStreamThroughTheSession() {
        val api = ScriptedApi()
        val codec = codec()
        val session = BridgeSession(api, codec, FixedClock(0L), UnreadLog)
        val requests = elements("next-request")
        for (element in requests) session.next("charts.head", element.toString()) {}
        assertEquals(requests.size, api.nexts.size)
        val prefix = api.nexts.first().key.substringBefore('/')
        for ((element, next) in requests.zip(api.nexts)) {
            assertEquals("charts.head", next.stream)
            assertEquals(codec.decode(ChartHeadInput.serializer(), element.field("params")), next.params)
            assertEquals(element.field("after").jsonPrimitive.longOrNull, next.after)
            assertEquals("$prefix/${element.field("key").jsonPrimitive.content}", next.key)
        }
    }

    @Test
    fun failedAnswersCarryEveryApiFailureField() {
        for (element in elements("failed")) {
            val codec = codec(element.now)
            val failure = codec.decode(ApiFailure.serializer(), element.field("failure"))
            assertWire(element, codec.answer(Empty.serializer(), Outcome.Failed(failure)))
            assertWire(element, codec.next(Empty.serializer(), StreamAnswer.Failed(failure)))
        }
    }

    @Test
    fun cancelledAnswersCarryOnlyTheirFlag() {
        val codec = codec()
        for (element in elements("cancelled")) {
            assertWire(element, codec.answer(Empty.serializer(), Outcome.Cancelled))
            assertWire(element, codec.next(Empty.serializer(), StreamAnswer.Cancelled))
            val page = codec.page(Outcome.Cancelled)
            assertWire(element, page.envelope)
            assertEquals(emptyMap(), page.numbers)
        }
    }

    @Test fun sealedSubclassesLeadWithTheirKind() = assertValuesReencoded("sealed", ChartSource.serializer())

    @Test
    fun enumsKeepTheirFrozenNames() {
        val (distance, rate) = elements("enum")
        val codec = codec()
        assertWire(
            distance,
            codec.json.encodeToString(DistanceSource.serializer(), codec.decode(DistanceSource.serializer(), distance)),
        )
        assertWire(
            rate,
            codec.json.encodeToString(SampleRate.serializer(), codec.decode(SampleRate.serializer(), rate)),
        )
        assertEquals(DistanceSource.phoneGps, codec.decode(DistanceSource.serializer(), distance))
        assertEquals(SampleRate.hz8, codec.decode(SampleRate.serializer(), rate))
    }

    @Test fun nullableFieldsArePresentWhenNull() = assertValuesReencoded("nullable", ApiFailure.serializer())

    @Test fun longsSpanTheSafeIntegerRange() = assertValuesReencoded("long", Long.serializer())

    @Test
    fun exportPagesSendTextColumnsInTheEnvelopeAndNumbersAsLittleEndianBytes() {
        for (element in elements("export-page")) {
            val envelope = Json.parseToJsonElement(element.field("envelope").jsonPrimitive.content)
            val value = envelope.field("value")
            val numbers =
                element.field("numbers").jsonObject.mapValues { Base64.decode(it.value.jsonPrimitive.content) }
            val codec = codec(envelope.now)
            val texts =
                codec.decode(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer().nullable)),
                    value.field("columns"),
                )
            val page =
                ExportPage(
                    rows = value.field("rows").jsonPrimitive.int,
                    next = codec.decode(ListSerializer(Double.serializer()).nullable, value.field("next")),
                    done = value.field("done").jsonPrimitive.boolean,
                    connections =
                        codec.decode(ListSerializer(ExportConnection.serializer()), value.field("connections")),
                    columns =
                        texts.mapValues { ExportColumn.Texts(it.value) } +
                            numbers.mapValues { ExportColumn.Numbers(doubles(it.value)) },
                )
            val packed = codec.page(Outcome.Ok(page))
            assertWire(envelope, packed.envelope)
            assertEquals(numbers.keys, packed.numbers.keys)
            for ((name, bytes) in numbers) assertContentEquals(bytes, packed.numbers.getValue(name), name)
        }
    }

    private fun doubles(bytes: ByteArray): DoubleArray =
        DoubleArray(bytes.size / 8) { row ->
            var bits = 0L
            for (byte in 7 downTo 0) bits = (bits shl 8) or (bytes[row * 8 + byte].toLong() and 0xff)
            Double.fromBits(bits)
        }
}
