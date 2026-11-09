package app.powerlog.runtime.host

import kotlinx.coroutines.Job

internal expect fun awaitBlocking(work: Job, timeoutUs: Long): Boolean
