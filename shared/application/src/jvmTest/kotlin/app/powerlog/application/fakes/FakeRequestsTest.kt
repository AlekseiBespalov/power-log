package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FakeRequestsTest {
    private val queue = FakePortQueue()

    @Test
    fun aScriptedAnswerArrivesOnlyWhenTheQueueDrains() {
        val requests = FakeRequests<String, Int>(queue::post) { Answer.Ok(it.length) }
        val probe = ReplyProbe<Int>()

        requests.take("four", probe.reply)

        assertFalse(probe.settled)
        assertEquals(1, requests.pending)
        queue.drain()
        assertEquals(listOf(4), probe.values)
        assertEquals(0, requests.pending)
        assertEquals(listOf("four"), requests.received)
    }

    @Test
    fun aHeldRequestStaysPendingUntilCompletedOrFailed() {
        val requests = FakeRequests<Unit, Int>(queue::post) { Answer.Held }
        val first = ReplyProbe<Int>()
        val second = ReplyProbe<Int>()
        requests.take(Unit, first.reply)
        requests.take(Unit, second.reply)
        queue.drain()

        assertEquals(2, requests.held)
        requests.complete(7)
        requests.fail(PortError(PortErrorKind.busy))
        queue.drain()

        assertEquals(listOf(7), first.values)
        assertEquals(listOf(PortErrorKind.busy), second.kinds)
        assertEquals(0, requests.pending)
    }
}
