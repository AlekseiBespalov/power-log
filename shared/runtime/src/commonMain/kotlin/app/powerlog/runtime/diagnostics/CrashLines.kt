package app.powerlog.runtime.diagnostics

import app.powerlog.application.diagnostics.CrashLineSource
import app.powerlog.application.store.LogLine
import app.powerlog.data.LogLines

internal const val CRASH_FILE: String = "crash-pending.jsonl"

internal class CrashLines(private val read: () -> String?, private val remove: () -> Unit) : CrashLineSource {
    override fun take(): List<LogLine> {
        val text = read() ?: return emptyList()
        remove()
        return text.lineSequence().mapNotNull(LogLines::decode).toList()
    }
}
