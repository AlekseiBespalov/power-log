@file:OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package app.powerlog.runtime.compose

import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import platform.darwin.DISPATCH_SOURCE_TYPE_TIMER
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_resume
import platform.darwin.dispatch_source_cancel
import platform.darwin.dispatch_source_create
import platform.darwin.dispatch_source_set_event_handler
import platform.darwin.dispatch_source_set_timer
import platform.darwin.dispatch_time

// Delay as kotlinx.coroutines' Darwin main dispatcher implements it, on this queue instead of the main run loop, so
// delay and withTimeout fire on the executor's own queue and the DefaultExecutor worker never gets a timer.
// Implementing Delay needs InternalCoroutinesApi: re-check this class at every kotlinx.coroutines upgrade.
internal class QueueDispatcher(private val queue: dispatch_queue_t) : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(queue) { block.run() }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        dispatch_after(at(timeMillis), queue) { with(continuation) { resumeUndispatched(Unit) } }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0u, 0u, queue)
        dispatch_source_set_timer(timer, at(timeMillis), DISPATCH_TIME_FOREVER, 0u)
        dispatch_source_set_event_handler(timer) { block.run() }
        dispatch_resume(timer)
        return DisposableHandle { dispatch_source_cancel(timer) }
    }

    private fun at(timeMillis: Long) =
        dispatch_time(DISPATCH_TIME_NOW, timeMillis.coerceIn(0L, Long.MAX_VALUE / 1_000_000L) * 1_000_000L)
}
