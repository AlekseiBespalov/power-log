package app.powerlog.application.api

import kotlinx.serialization.Serializable

public sealed interface Outcome<out T> {
    public data class Ok<out T>(public val value: T) : Outcome<T>

    public data class Failed(public val failure: ApiFailure) : Outcome<Nothing>

    public data object Cancelled : Outcome<Nothing>
}

public interface ApiStream<P, out T> {
    public suspend fun next(params: P, after: Long?, key: String): StreamAnswer<T>
}

public sealed interface StreamAnswer<out T> {
    public data class Value<out T>(public val revision: Long, public val value: T) : StreamAnswer<T>

    public data class Failed(public val failure: ApiFailure) : StreamAnswer<Nothing>

    public data object Cancelled : StreamAnswer<Nothing>
}

@Serializable public data object Empty

@Serializable public data class CancelInput(public val key: String)

@Serializable public data class ApiVersion(public val hash: String)

public object StreamPolicy {
    public const val foundBikesUs: Long = 250_000L
    public const val recordingStateUs: Long = 1_000_000L
    public const val historyRideUs: Long = 1_000_000L
    public const val chartsHeadUs: Long = 750_000L
    public const val diagnosticsStateUs: Long = 1_000_000L
}
