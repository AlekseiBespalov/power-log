package app.powerlog.platform

import expo.modules.kotlin.Promise
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class Rejection(val code: String?, message: String?) : Exception(message)

internal class Reply : Promise {
    private val future = CompletableFuture<Any?>()

    val settled
        get() = future.isDone

    override fun resolve(value: Any?) {
        future.complete(value)
    }

    override fun reject(code: String?, message: String?, cause: Throwable?) {
        future.completeExceptionally(Rejection(code, message))
    }

    fun get(): Any? =
        try {
            future.get(20, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw checkNotNull(error.cause)
        }
}

internal class CountingExecutor : Executor {
    var tasks = 0

    override fun execute(command: Runnable) {
        tasks++
        command.run()
    }
}

internal class QueueExecutor : Executor {
    val queue = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    fun runNext() = queue.removeFirst().run()

    fun drain() {
        while (queue.isNotEmpty()) runNext()
    }
}

internal fun rejection(block: () -> Unit): String? =
    try {
        block()
        null
    } catch (error: Rejection) {
        error.code
    }
