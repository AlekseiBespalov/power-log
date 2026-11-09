package app.powerlog.application.api

import app.powerlog.application.charts.ChartsApi
import app.powerlog.application.connection.ConnectionApi
import app.powerlog.application.diagnostics.DiagnosticsApi
import app.powerlog.application.exports.ExportsApi
import app.powerlog.application.health.HealthApi
import app.powerlog.application.history.HistoryApi
import app.powerlog.application.imports.ImportsApi
import app.powerlog.application.recording.RecordingApi
import app.powerlog.application.settings.SettingsApi

public interface PowerLogApi {
    public val device: DeviceApi
    public val connection: ConnectionApi
    public val recording: RecordingApi
    public val history: HistoryApi
    public val charts: ChartsApi
    public val exports: ExportsApi
    public val imports: ImportsApi
    public val health: HealthApi
    public val settings: SettingsApi
    public val diagnostics: DiagnosticsApi

    public suspend fun cancel(input: CancelInput): Outcome<Unit>
}
