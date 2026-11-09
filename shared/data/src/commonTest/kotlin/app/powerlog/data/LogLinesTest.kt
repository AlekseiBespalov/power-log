package app.powerlog.data

import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogLinesTest {
    @Test
    fun aDecodedLineIsTheLineEncoded() {
        val line =
            LogLine(
                LogLevel.error,
                1_793_523_600_000_000,
                42_000_000,
                "crash",
                listOf(
                    LogField("exception", LogValue.Text("IllegalStateException")),
                    LogField("frames", LogValue.Texts(listOf("0x1c0", "0x2f8"))),
                    LogField("attempt", LogValue.Integer(3)),
                    LogField("ratio", LogValue.Decimal(0.75)),
                    LogField("background", LogValue.Flag(true)),
                    LogField("platform", LogValue.Fields(listOf(LogField("code", LogValue.Text("-4"))))),
                ),
            )
        assertEquals(line, LogLines.decode(LogLines.encode(line)))
    }

    @Test
    fun aTornForeignOrHeaderlessLineDecodesToNothing() {
        val whole = LogLines.encode(LogLine(LogLevel.info, 1, 2, "launch", emptyList()))
        for (text in
            listOf(
                whole.dropLast(3),
                "not json",
                "[]",
                whole.replace("\"v\":2", "\"v\":1"),
                whole.replace("\"level\":\"info\"", "\"level\":\"loud\""),
                """{"v":2,"level":"info","utcUs":1,"event":"launch"}""",
                whole.dropLast(1) + ",\"frames\":[1]}",
            )) assertNull(LogLines.decode(text), text)
    }
}
