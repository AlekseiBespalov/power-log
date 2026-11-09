package app.powerlog.runtime.host

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun awaitBlocking(work: Job, timeoutUs: Long): Boolean = runBlocking {
    withTimeoutOrNull((timeoutUs + 999) / 1_000) { work.join() } != null
}
