package app.powerlog.application.health

import app.powerlog.application.port.PortError
import app.powerlog.application.port.Reply
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface Called<out T> {
    data class Ok<out T>(val value: T) : Called<T>

    data class Failed(val error: PortError) : Called<Nothing>

    data object Abandoned : Called<Nothing>
}

internal suspend fun <T> called(request: (Reply<T>) -> Unit): Called<T> =
    withTimeoutOrNull(HealthWorkPolicy.portCallBudgetUs / 1_000) {
        suspendCancellableCoroutine<Called<T>> { continuation ->
            request(
                Reply(
                    onOk = { if (continuation.isActive) continuation.resume(Called.Ok(it)) },
                    onFailed = { if (continuation.isActive) continuation.resume(Called.Failed(it)) },
                )
            )
        }
    } ?: Called.Abandoned
