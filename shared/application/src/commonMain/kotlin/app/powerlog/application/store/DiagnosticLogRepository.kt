package app.powerlog.application.store

public interface DiagnosticLogRepository {
    // one JSON line; failures are counted, never thrown
    public suspend fun append(line: LogLine): Unit

    // after: the byte offset to continue from, 0 at the start
    public suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk>
}

// next: null at the end
public class LogChunk(public val bytes: ByteArray, public val next: Long?)

public data class LogLine(
    public val level: LogLevel,
    public val utcUs: Long,
    public val monoUs: Long,
    public val event: String,
    public val fields: List<LogField>,
)

public data class LogField(public val key: String, public val value: LogValue)

public sealed interface LogValue {
    public data class Text(public val value: String) : LogValue

    public data class Integer(public val value: Long) : LogValue

    public data class Decimal(public val value: Double) : LogValue

    public data class Flag(public val value: Boolean) : LogValue

    // the frames of a crash or unexpected line
    public data class Texts(public val values: List<String>) : LogValue

    // a nested object: a platform code, the launch line's pragmas per file
    public data class Fields(public val fields: List<LogField>) : LogValue
}

public enum class LogLevel {
    info,
    warning,
    error,
}
