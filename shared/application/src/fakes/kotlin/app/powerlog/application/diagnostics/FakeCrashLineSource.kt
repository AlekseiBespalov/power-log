package app.powerlog.application.diagnostics

import app.powerlog.application.store.LogLine

class FakeCrashLineSource(vararg lines: LogLine) : CrashLineSource {
    private val pending = lines.toMutableList()

    var takes: Int = 0
        private set

    fun crash(line: LogLine) {
        pending += line
    }

    override fun take(): List<LogLine> {
        takes++
        return pending.toList().also { pending.clear() }
    }
}
