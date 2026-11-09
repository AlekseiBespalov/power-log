package app.powerlog.runtime.diagnostics

import app.powerlog.application.store.LogValue
import app.powerlog.data.LogLines
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashLineTest {
    @Test
    fun aCrashLineKeepsItsFirstFramesWithinTheLogsLineLimit() {
        val error = IllegalStateException("not logged")
        error.stackTrace =
            Array(200) { StackTraceElement("app.powerlog.${"Deep".repeat(20)}", "call$it", "Deep.kt", it) }
        val line = Unexpected.crash(error, ClockReading(1_793_523_600_000_000, 42), "ride")

        assertTrue(LogLines.encode(line).encodeToByteArray().size + 1 <= 4_096)
        val fields = line.fields.associate { it.key to it.value }
        assertEquals(listOf("exception", "frames", "thread"), line.fields.map { it.key })
        assertEquals(LogValue.Text("IllegalStateException"), fields["exception"])
        assertEquals(LogValue.Text("ride"), fields["thread"])
        val frames = (fields.getValue("frames") as LogValue.Texts).values
        assertTrue(frames.size in 1..64, "${frames.size}")
        assertEquals(
            error.stackTrace.take(frames.size).map { "${it.className}.${it.methodName}(Deep.kt:${it.lineNumber})" },
            frames,
        )
    }
}
