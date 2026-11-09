package app.powerlog.application.slice

import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LogWhitelistTest {
    @Test
    fun theWhitelistRejectsUnknownEventsFieldsAndValues() {
        fun line(event: String, vararg fields: Pair<String, LogValue>) =
            LogLine(LogLevel.info, 0, 0, event, fields.map { LogField(it.first, it.second) })
        assertEquals(
            emptyList(),
            LogWhitelist.violations(
                listOf(
                    line("reconnectScheduled", "reason" to LogValue.Text("plannedReconnect")),
                    line("identity", "outcome" to LogValue.Text("rejected"), "rejection" to LogValue.Text("identity")),
                    line("rateChanged", "hz" to LogValue.Integer(8)),
                )
            ),
        )
        assertEquals(
            listOf(
                "unknown event telemetry_summary",
                "linkDown.name is not whitelisted",
                "rateChanged.hz = Integer(value=3)",
                "linkDown.platform = Text(value=ios)",
            ),
            LogWhitelist.violations(
                listOf(
                    line("telemetry_summary"),
                    line("linkDown", "name" to LogValue.Text("X6")),
                    line("rateChanged", "hz" to LogValue.Integer(3)),
                    line("linkDown", "platform" to LogValue.Text("ios")),
                )
            ),
        )
    }
}
