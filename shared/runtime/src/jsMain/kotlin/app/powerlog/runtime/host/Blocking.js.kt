package app.powerlog.runtime.host

import kotlinx.coroutines.Job

internal actual fun awaitBlocking(work: Job, timeoutUs: Long): Boolean = work.isCompleted
