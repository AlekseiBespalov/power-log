package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.Reply

class FakePortQueue {
    private val waiting = ArrayDeque<() -> Unit>()

    val size: Int
        get() = waiting.size

    fun post(block: () -> Unit) {
        waiting.addLast(block)
    }

    fun drain(): Int {
        var delivered = 0
        while (waiting.isNotEmpty()) {
            waiting.removeFirst()()
            delivered++
        }
        return delivered
    }
}

sealed interface Answer<out T> {
    data class Ok<out T>(val value: T) : Answer<T>

    data class Failed(val error: PortError) : Answer<Nothing>

    data object Held : Answer<Nothing>
}

class FakeRequests<A, T>(private val post: (() -> Unit) -> Unit, var script: (A) -> Answer<T>) {
    private val all = mutableListOf<A>()
    private val waiting = ArrayDeque<Reply<T>>()

    val received: List<A>
        get() = all

    val held: Int
        get() = waiting.size

    var pending: Int = 0
        private set

    fun take(request: A, reply: Reply<T>) {
        all += request
        pending++
        val settling =
            Reply<T>(
                onOk = { value ->
                    pending--
                    reply.ok(value)
                },
                onFailed = { error ->
                    pending--
                    reply.failed(error)
                },
            )
        when (val answer = script(request)) {
            is Answer.Ok -> post { settling.ok(answer.value) }
            is Answer.Failed -> post { settling.failed(answer.error) }
            Answer.Held -> waiting.addLast(settling)
        }
    }

    fun complete(value: T) {
        val reply = waiting.removeFirst()
        post { reply.ok(value) }
    }

    fun fail(error: PortError) {
        val reply = waiting.removeFirst()
        post { reply.failed(error) }
    }
}
