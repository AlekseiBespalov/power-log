package app.powerlog.runtime.web.host

import app.powerlog.application.api.Outcome
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.StoreResult
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.bridge.ScriptedApi
import app.powerlog.runtime.bridge.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSessionTest {
    private object Unlogged : DiagnosticLogRepository {
        override suspend fun append(line: LogLine) {}

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
    }

    @Test
    fun anExportPageArrivesAsAPlainObjectWhoseNumericColumnsAreUint8ArraysOverTheirOwnBytes() {
        val api = ScriptedApi()
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
        val clock = FakeClock(monoUs = 5_000)
        val session = WebSession(BridgeSession(api, WireCodec(clock), clock, Unlogged))
        var answer: dynamic = null

        session.exportPage("""{"sessionId":"s","projection":"telemetry","after":null}""") { answer = it }

        assertEquals(
            """{"ok":true,"value":{"rows":2,"next":null,"done":true,"connections":[],"columns":{"label":["a",null]}},"now":5000}""",
            answer.envelope,
        )
        val bytes: dynamic = answer.numbers.humanPowerW
        assertTrue(js("bytes instanceof Uint8Array").unsafeCast<Boolean>())
        assertEquals(16, bytes.length)
        assertEquals(0, bytes.byteOffset)
        val values: dynamic = js("new Float64Array(bytes.buffer, 0, 2)")
        assertEquals(1.5, values[0])
        assertTrue(js("Number.isNaN(values[1])").unsafeCast<Boolean>())
        assertEquals("""["humanPowerW"]""", JSON.stringify(js("Object.keys")(answer.numbers)))
    }
}
