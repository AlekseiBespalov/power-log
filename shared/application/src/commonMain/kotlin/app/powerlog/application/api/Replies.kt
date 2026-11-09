package app.powerlog.application.api

import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface Answer<out T> {
    data class Ok<out T>(val value: T) : Answer<T>

    data class Failed(val error: PortError) : Answer<Nothing>
}

internal suspend fun <T> answer(request: (Reply<T>) -> Unit): Answer<T> = suspendCancellableCoroutine { continuation ->
    request(
        Reply(
            onOk = { if (continuation.isActive) continuation.resume(Answer.Ok(it)) },
            onFailed = { if (continuation.isActive) continuation.resume(Answer.Failed(it)) },
        )
    )
}

internal suspend fun TabLock.lease(name: String, waitIfHeld: Boolean): TabLease? =
    suspendCancellableCoroutine { continuation ->
        acquire(
            name,
            waitIfHeld,
            Reply(
                onOk = { lease -> continuation.resume(lease) { _, late, _ -> late?.release() } },
                onFailed = { if (continuation.isActive) continuation.resume(null) },
            ),
        )
    }

internal suspend fun FilePicker.picked(): Answer<PickedFile> = suspendCancellableCoroutine { continuation ->
    pickCsv(
        Reply(
            onOk = { file ->
                continuation.resume(Answer.Ok(file)) { _, late, _ -> (late as? Answer.Ok)?.value?.close() }
            },
            onFailed = { if (continuation.isActive) continuation.resume(Answer.Failed(it)) },
        )
    )
}
