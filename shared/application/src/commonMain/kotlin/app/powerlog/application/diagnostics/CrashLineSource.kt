package app.powerlog.application.diagnostics

import app.powerlog.application.store.LogLine

public interface CrashLineSource {
    public fun take(): List<LogLine>
}
