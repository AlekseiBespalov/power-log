package app.powerlog.application.port

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyTest {
    private val delivered = mutableListOf<String>()

    private val reply = Reply<Int>(onOk = { delivered += "ok $it" }, onFailed = { delivered += "failed ${it.kind}" })

    @Test
    fun theFirstOkIsDelivered() {
        reply.ok(1)

        assertEquals(listOf("ok 1"), delivered)
    }

    @Test
    fun theFirstFailedIsDelivered() {
        reply.failed(PortError(PortErrorKind.busy))

        assertEquals(listOf("failed busy"), delivered)
    }

    @Test
    fun everyCompletionAfterTheFirstIsIgnored() {
        reply.failed(PortError(PortErrorKind.denied))
        reply.ok(2)
        reply.failed(PortError(PortErrorKind.failed))
        reply.ok(3)

        assertEquals(listOf("failed denied"), delivered)
    }

    @Test
    fun racingThreadsDeliverExactlyOneCompletion() {
        val rounds = 200_000
        val current = AtomicReference<Reply<Int>>()
        val generation = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        val workers =
            listOf(true, false).map { completesOk ->
                thread(isDaemon = true) {
                    var seen = 0
                    while (seen < rounds) {
                        val next = generation.get()
                        if (next == seen) {
                            Thread.onSpinWait()
                            continue
                        }
                        seen = next
                        val racing = current.get()
                        if (completesOk) racing.ok(next) else racing.failed(PortError(PortErrorKind.failed))
                        finished.incrementAndGet()
                    }
                }
            }
        var doubles = 0
        repeat(rounds) { round ->
            val completions = AtomicInteger(0)
            current.set(Reply(onOk = { completions.incrementAndGet() }, onFailed = { completions.incrementAndGet() }))
            finished.set(0)
            generation.set(round + 1)
            while (finished.get() < 2) {
                check(System.nanoTime() < deadline) { "round $round did not finish" }
                Thread.onSpinWait()
            }
            if (completions.get() != 1) doubles++
        }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        assertEquals(0, doubles, "rounds delivering other than one completion")
    }
}
