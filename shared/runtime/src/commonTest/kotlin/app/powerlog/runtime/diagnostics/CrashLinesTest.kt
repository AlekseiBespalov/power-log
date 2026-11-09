package app.powerlog.runtime.diagnostics

import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.data.LogLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrashLinesTest {
    private var file: String? = null

    private val lines = CrashLines(read = { file }, remove = { file = null })

    private fun line(utcUs: Long) = LogLine(LogLevel.error, utcUs, utcUs, "crash", emptyList())

    @Test
    fun takeAnswersTheWholeLinesOldestFirstAndDeletesTheFile() {
        val whole = listOf(line(1), line(2))
        file = whole.joinToString("") { LogLines.encode(it) + "\n" } + LogLines.encode(line(3)).dropLast(4)
        assertEquals(whole, lines.take())
        assertNull(file)
        assertEquals(emptyList(), lines.take())
    }
}
