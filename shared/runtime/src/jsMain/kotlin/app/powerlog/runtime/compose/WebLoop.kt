@file:OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package app.powerlog.runtime.compose

import app.powerlog.application.HostTurns
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.MessageChannel

// Dispatchers.Default with a host turn: kotlinx runs up to 16 queued steps before the browser gets the thread back, so
// a turn waits for a MessageChannel message, a task of its own.
internal object WebLoop : CoroutineDispatcher(), Delay by (Dispatchers.Default as Delay), HostTurns {
    private val loop = Dispatchers.Default
    private val waiting = ArrayDeque<CancellableContinuation<Unit>>()
    private val channel by lazy {
        MessageChannel().apply {
            port1.onmessage = { waiting.removeFirstOrNull()?.resume(Unit) }
            port1.asDynamic().unref?.call(port1)
            port2.asDynamic().unref?.call(port2)
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = loop.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        loop.dispatch(context, block)
    }

    override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher = this

    override suspend fun hostTurn(): Unit = suspendCancellableCoroutine { turn ->
        waiting.addLast(turn)
        channel.port2.postMessage(null)
    }
}
