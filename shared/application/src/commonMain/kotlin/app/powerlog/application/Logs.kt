package app.powerlog.application

import app.powerlog.application.port.Clock
import app.powerlog.application.port.ExitReport
import app.powerlog.application.port.PortError
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.core.time.ClockReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class Logs(
    private val scope: CoroutineScope,
    private val maintenance: CoroutineDispatcher,
    private val log: DiagnosticLogRepository?,
    private val clock: Clock,
    private val unexpectedLine: (String, Throwable, ClockReading) -> LogLine,
) {
    fun append(line: LogLine) {
        val store = log ?: return
        scope.launch(maintenance) { store.append(line) }
    }

    fun unexpected(component: String, error: Throwable) {
        append(unexpectedLine(component, error, clock.read()))
    }

    fun storageFailed(error: StorageError, stage: String) {
        if (error.kind == StorageError.Kind.busy) return
        append(
            line(
                LogLevel.error,
                "storageFailed",
                listOfNotNull(
                    LogField("kind", LogValue.Text(error.kind.name)),
                    LogField("stage", LogValue.Text(stage)),
                    error.platform?.let { LogField("platform", platform(it)) },
                ),
            )
        )
    }

    fun event(level: LogLevel, event: String, vararg fields: Pair<String, Any?>) {
        append(line(level, event, fields.mapNotNull { (key, value) -> value(value)?.let { LogField(key, it) } }))
    }

    fun failure(source: String, error: PortError) {
        event(LogLevel.warning, "failure", "source" to source, "error" to error.kind, "platform" to error.platform)
    }

    fun line(level: LogLevel, event: String, fields: List<LogField>): LogLine {
        val at = clock.read()
        return LogLine(level, at.utcUs, at.monoUs, event, fields)
    }

    companion object {
        fun value(value: Any?): LogValue? =
            when (value) {
                null -> null
                is LogValue -> value
                is String -> LogValue.Text(value)
                is Int -> LogValue.Integer(value.toLong())
                is Long -> LogValue.Integer(value)
                is Double -> LogValue.Decimal(value)
                is Boolean -> LogValue.Flag(value)
                is Enum<*> -> LogValue.Text(value.name)
                is PlatformCode -> platform(value)
                else -> error("no log value for ${value::class.simpleName}")
            }

        fun platform(code: PlatformCode): LogValue =
            LogValue.Fields(
                listOf(LogField("domain", LogValue.Text(code.domain)), LogField("code", LogValue.Text(code.code)))
            )

        fun pragmas(files: List<FilePragmas>): LogValue =
            LogValue.Fields(
                files.map {
                    LogField(
                        it.file.name,
                        LogValue.Fields(
                            listOf(
                                LogField("synchronous", LogValue.Integer(it.synchronous.toLong())),
                                LogField("journalMode", LogValue.Text(it.journalMode)),
                                LogField("autoVacuum", LogValue.Integer(it.autoVacuum.toLong())),
                                LogField("walAutocheckpoint", LogValue.Integer(it.walAutocheckpoint.toLong())),
                            )
                        ),
                    )
                }
            )

        fun healthy(files: List<FilePragmas>): Boolean = files.all {
            it.synchronous == 2 && it.journalMode == "wal" && it.autoVacuum == 2 && it.walAutocheckpoint == 1000
        }

        fun exit(report: ExitReport): List<LogField> =
            listOf(
                LogField("reason", LogValue.Text(report.reason)),
                LogField("status", LogValue.Integer(report.status.toLong())),
                LogField("importance", LogValue.Integer(report.importance.toLong())),
                LogField("atUtcUs", LogValue.Integer(report.atUtcUs)),
            )
    }
}
