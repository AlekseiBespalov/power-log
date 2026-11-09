package app.powerlog.runtime.bridge

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.exports.ConsumptionAvailability
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportKind
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.exports.ExportProducers
import app.powerlog.application.exports.ExportSessionView
import app.powerlog.application.exports.ExportSource
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.history.HistoryPage
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.ImportedSummaryView
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import app.powerlog.runtime.API_HASH
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BridgeSessionTest {
    private class RecordingLog : DiagnosticLogRepository {
        val lines = mutableListOf<LogLine>()

        override suspend fun append(line: LogLine) {
            lines += line
        }

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
    }

    private val clock = FakeClock(monoUs = 5_000)
    private val api = ScriptedApi()
    private val log = RecordingLog()

    private val wire = WireCodec(clock).json

    private fun session(): BridgeSession = BridgeSession(api, WireCodec(clock), clock, log)

    private fun envelope(answer: String): JsonObject = Json.parseToJsonElement(answer).jsonObject

    private fun field(line: LogLine, key: String): LogValue? = line.fields.firstOrNull { it.key == key }?.value

    private fun assertUnknown(answer: String) {
        val envelope = envelope(answer)
        assertEquals(false, envelope.getValue("ok").jsonPrimitive.boolean, answer)
        val failure = envelope.getValue("failure").jsonObject
        assertEquals("unknown", failure.getValue("code").jsonPrimitive.content)
        assertEquals(false, failure.getValue("retryable").jsonPrimitive.boolean)
    }

    @Test
    fun undecodableRequestsAndUnknownNamesAnswerUnknownWithOneUnexpectedLine() {
        val requests =
            listOf(
                "history.page" to "[]",
                "history.page" to "not json",
                "history.page" to """{"after":null}""",
                "history.nothing" to "{}",
                "exports.page" to """{"sessionId":"s","projection":"gps","after":null}""",
            )
        for ((op, request) in requests) {
            val answers = mutableListOf<String>()
            log.lines.clear()
            session().call(op, request) { answers += it }
            assertUnknown(answers.single())
            val line = log.lines.single()
            assertEquals("unexpected", line.event)
            assertEquals(LogValue.Text("bridge"), field(line, "component"))
            assertTrue(field(line, "exception") is LogValue.Text)
            assertTrue((field(line, "frames") as LogValue.Texts).values.size <= 64)
        }
        val answers = mutableListOf<String>()
        log.lines.clear()
        session().next("history.nothing", """{"params":{},"after":null,"key":"k"}""") { answers += it }
        assertUnknown(answers.single())
        assertEquals("unexpected", log.lines.single().event)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aCancelReachesOnlyTheSessionsOwnRequest() {
        val first = session()
        val second = session()
        val firstAnswers = mutableListOf<String>()
        val secondAnswers = mutableListOf<String>()
        first.next("connection.state", """{"params":{},"after":null,"key":"k"}""") { firstAnswers += it }
        second.next("connection.state", """{"params":{},"after":null,"key":"k"}""") { secondAnswers += it }
        val (firstKey, secondKey) = api.nexts.map { it.key }
        assertTrue(firstKey.endsWith("/k") && secondKey.endsWith("/k"))
        assertNotEquals(firstKey, secondKey)
        first.call("cancel", """{"key":"k"}""") {}
        assertEquals(listOf(firstKey), api.cancelled)
        assertEquals(listOf("""{"cancelled":true}"""), firstAnswers)
        assertEquals(emptyList(), secondAnswers)
    }

    @Test
    fun closeCancelsQueriesClosesExportsAndImportsAndDropsLaterReplies() {
        val session = session()
        val view =
            ExportSessionView(
                "e1",
                Platform.ios,
                null,
                0.0,
                0.0,
                ExportProducers(emptyList(), emptyList()),
                ConsumptionAvailability.Available,
            )
        val imported =
            ImportedRideView(RideId("i1"), "ride.csv", ImportedSummaryView(0, 0, 0, 0, null, null, null, null))
        api.immediate = { op, _ ->
            when (op) {
                "exports.open" -> Outcome.Ok(view)
                "imports.open" -> Outcome.Ok(imported)
                "exports.close",
                "imports.close" -> Outcome.Ok(Unit)
                else -> null
            }
        }
        val answers = mutableListOf<String>()
        val open = ExportOpenInput(ExportSource.Ride(RideId("r1")), ExportKind.zip, FitPowerSource.rider)
        session.call("exports.open", wire.encodeToString(ExportOpenInput.serializer(), open)) { answers += it }
        session.call("imports.open", """{"key":"i"}""") { answers += it }
        session.call("history.page", """{"after":null,"key":"q"}""") { answers += it }
        session.call("connection.disconnect", "{}") { answers += it }
        assertEquals(2, answers.size)
        val query = api.pending("history.page")
        val command = api.pending("connection.disconnect")

        session.close()

        assertEquals(listOf(query.key), api.cancelled)
        assertEquals(ExportCloseInput("e1", null), api.calls.single { it.op == "exports.close" }.input)
        assertEquals(ImportInput(RideId("i1")), api.calls.single { it.op == "imports.close" }.input)
        assertTrue(query.answer.isCompleted)
        command.answer.complete(Outcome.Ok(Unit))
        session.call("connection.disconnect", "{}") { answers += it }
        assertEquals(2, answers.size)
        assertEquals(1, api.calls.count { it.op == "connection.disconnect" })
    }

    @Test
    fun anExportClosedByTheClientIsNotClosedAgainAtClose() {
        val session = session()
        val view =
            ExportSessionView(
                "e2",
                Platform.android,
                null,
                0.0,
                0.0,
                ExportProducers(emptyList(), emptyList()),
                ConsumptionAvailability.Available,
            )
        api.immediate = { op, _ ->
            when (op) {
                "exports.open" -> Outcome.Ok(view)
                "exports.close" -> Outcome.Ok(Unit)
                else -> null
            }
        }
        val open = ExportOpenInput(ExportSource.Ride(RideId("r1")), ExportKind.fit, FitPowerSource.rider)
        session.call("exports.open", wire.encodeToString(ExportOpenInput.serializer(), open)) {}
        session.call("exports.close", """{"sessionId":"e2","failure":null}""") {}
        session.close()
        assertEquals(1, api.calls.count { it.op == "exports.close" })
    }

    @Test
    fun anOpenWhoseAnswerCannotBeEncodedClosesWhatItOpenedAndAnswersUnknown() {
        val session = session()
        val view =
            ExportSessionView(
                "e3",
                Platform.ios,
                null,
                Double.NaN,
                0.0,
                ExportProducers(emptyList(), emptyList()),
                ConsumptionAvailability.Available,
            )
        val imported =
            ImportedRideView(RideId("i3"), "ride.csv", ImportedSummaryView(0, 0, 0, 0, Double.NaN, null, null, null))
        api.immediate = { op, _ ->
            when (op) {
                "exports.open" -> Outcome.Ok(view)
                "imports.open" -> Outcome.Ok(imported)
                "exports.close",
                "imports.close" -> Outcome.Ok(Unit)
                else -> null
            }
        }
        val answers = mutableListOf<String>()
        val open = ExportOpenInput(ExportSource.Ride(RideId("r1")), ExportKind.fit, FitPowerSource.rider)
        session.call("exports.open", wire.encodeToString(ExportOpenInput.serializer(), open)) { answers += it }
        session.call("imports.open", """{"key":"i"}""") { answers += it }

        assertEquals(2, answers.size)
        answers.forEach(::assertUnknown)
        assertEquals(listOf("unexpected", "unexpected"), log.lines.map { it.event })
        assertEquals(ExportCloseInput("e3", null), api.calls.single { it.op == "exports.close" }.input)
        assertEquals(ImportInput(RideId("i3")), api.calls.single { it.op == "imports.close" }.input)
        session.close()
        assertEquals(1, api.calls.count { it.op == "exports.close" })
        assertEquals(1, api.calls.count { it.op == "imports.close" })
    }

    @Test
    fun aSectionThatClaimsACoreNameIsRefusedWhenTheSessionIsBuilt() {
        val clashing =
            object : ApiSection {
                override val names = setOf("history")

                override suspend fun call(op: String, input: JsonObject): String? = null

                override suspend fun next(stream: String, params: JsonObject, after: Long?, key: String): String? = null
            }
        assertFailsWith<IllegalArgumentException> { BridgeSession(api, WireCodec(clock), clock, log, listOf(clashing)) }
    }

    @Test
    fun anOperationOrStreamGoesToTheSectionItsFirstNameRegisters() {
        val reached = mutableListOf<String>()
        val sample =
            object : ApiSection {
                override val names = setOf("sample")

                override suspend fun call(op: String, input: JsonObject): String? =
                    if (op == "sample.touch") "touched ${input["key"]}".also { reached += it } else null

                override suspend fun next(stream: String, params: JsonObject, after: Long?, key: String): String? =
                    "fed $stream $after $key".also { reached += it }
            }
        val session = BridgeSession(api, WireCodec(clock), clock, log, listOf(sample))
        val answers = mutableListOf<String>()
        session.call("sample.touch", """{"key":"t"}""") { answers += it }
        session.next("sample.feed", """{"params":{},"after":2,"key":"f"}""") { answers += it }
        session.call("history.page", """{"after":null,"key":"q"}""") {}

        assertEquals(2, answers.size)
        assertTrue(answers[0].matches(Regex("touched \"[0-9a-f-]{36}/t\"")), answers[0])
        assertTrue(answers[1].matches(Regex("""fed sample.feed 2 [0-9a-f-]{36}/f""")), answers[1])
        assertEquals(answers, reached)
        assertEquals(listOf("history.page"), api.calls.map { it.op })
        for (op in listOf("sample.missing", "other.touch")) {
            val unknown = mutableListOf<String>()
            session.call(op, "{}") { unknown += it }
            assertUnknown(unknown.single())
        }
    }

    @Test
    fun anExportPageSendsTextsInTheEnvelopeAndNumbersAsLittleEndianBytesWithNanKept() {
        val page =
            ExportPage(
                rows = 2,
                next = null,
                done = true,
                connections = emptyList(),
                columns =
                    mapOf(
                        "label" to ExportColumn.Texts(listOf("a", null)),
                        "humanPowerW" to ExportColumn.Numbers(doubleArrayOf(1.5, Double.NaN)),
                    ),
            )
        api.immediate = { op, _ -> if (op == "exports.page") Outcome.Ok(page) else null }
        val pages = mutableListOf<BinaryPage>()
        session().exportPage("""{"sessionId":"s","projection":"telemetry","after":null}""") { pages += it }
        val answer = pages.single()
        assertEquals(
            """{"ok":true,"value":{"rows":2,"next":null,"done":true,"connections":[],"columns":{"label":["a",null]}},"now":5000}""",
            answer.envelope,
        )
        assertEquals(setOf("humanPowerW"), answer.numbers.keys)
        val nan = Double.NaN.toRawBits()
        val expected =
            byteArrayOf(0, 0, 0, 0, 0, 0, 0xf8.toByte(), 0x3f) + ByteArray(8) { (nan ushr (it * 8)).toByte() }
        assertContentEquals(expected, answer.numbers.getValue("humanPowerW"))
    }

    @Test
    fun aFailedOrUndecodablePageCarriesNoNumbers() {
        val pages = mutableListOf<BinaryPage>()
        session().exportPage("[]") { pages += it }
        assertUnknown(pages.single().envelope)
        assertEquals(emptyMap(), pages.single().numbers)
        assertEquals("unexpected", log.lines.single().event)
    }

    @Test
    fun apiVersionAnswersTheHashAtOnceWhileEveryOperationWaits() {
        val session = session()
        session.call("history.page", """{"after":null,"key":"q"}""") {}
        val answers = mutableListOf<String>()
        session.call("apiVersion", "{}") { answers += it }
        assertEquals("""{"ok":true,"value":{"hash":"$API_HASH"},"now":5000}""", answers.single())
    }

    @Test
    fun requestsReachTheApiInSendOrderWithSessionKeys() {
        val session = session()
        session.call("history.page", """{"after":null,"key":"q"}""") {}
        session.call("connection.startSearch", "{}") {}
        session.next("connection.state", """{"params":{},"after":3,"key":"s"}""") {}
        assertEquals(listOf("history.page", "connection.startSearch"), api.calls.map { it.op })
        val prefix = api.calls.first().key!!.substringBefore('/')
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(prefix), prefix)
        assertEquals("$prefix/s", api.nexts.single().key)
        assertEquals(3L, api.nexts.single().after)
    }

    @Test
    fun answersAreEncodedWhereTheApiMemberReturns() {
        val answers = mutableListOf<String>()
        session().call("history.page", """{"after":null,"key":"q"}""") { answers += it }
        clock.advance(7)
        api.pending("history.page").answer.complete(Outcome.Ok(HistoryPage(emptyList(), null)))
        assertEquals("""{"ok":true,"value":{"rows":[],"next":null},"now":5007}""", answers.single())
        val streamed = mutableListOf<String>()
        session().next("connection.state", """{"params":{},"after":null,"key":"s"}""") { streamed += it }
        api.nexts.single().answer.complete(StreamAnswer.Failed(failure))
        assertEquals(
            """{"ok":false,"failure":{"code":"storageBusy","retryable":true,"occurrence":null,"detail":null},"now":5007}""",
            streamed.single(),
        )
    }

    private val failure = ApiFailure(FailureCode.storageBusy, retryable = true, occurrence = null, detail = null)
}
