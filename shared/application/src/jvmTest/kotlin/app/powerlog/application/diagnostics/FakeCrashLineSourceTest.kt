package app.powerlog.application.diagnostics

import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeCrashLineSourceTest {
    private fun line(monoUs: Long) =
        LogLine(
            LogLevel.error,
            1_793_520_000_000_000L + monoUs,
            monoUs,
            "crash",
            listOf(LogField("frames", LogValue.Texts(listOf("main")))),
        )

    @Test
    fun takeReturnsThePendingLinesOldestFirstOnce() {
        val source = FakeCrashLineSource(line(1), line(2))
        source.crash(line(3))
        assertEquals(listOf(line(1), line(2), line(3)), source.take())
        assertEquals(emptyList(), source.take())
        assertEquals(2, source.takes)
    }

    @Test
    fun linesAddedAfterATakeComeWithTheNext() {
        val source = FakeCrashLineSource()
        assertEquals(emptyList(), source.take())
        source.crash(line(4))
        assertEquals(listOf(line(4)), source.take())
    }
}
