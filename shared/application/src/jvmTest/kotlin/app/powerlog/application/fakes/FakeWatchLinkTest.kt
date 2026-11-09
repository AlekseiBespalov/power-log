package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Responder
import app.powerlog.application.port.WatchLinkAvailability
import app.powerlog.application.port.WatchLinkEvents
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FakeWatchLinkTest {
    private val queue = FakePortQueue()
    private val clock = FakeClock()
    private val link = FakeWatchLink(clock, queue::post)
    private val availability = mutableListOf<WatchLinkAvailability>()
    private val received = mutableListOf<Triple<ByteArray, Responder, ClockReading>>()

    init {
        link.attach(
            object : WatchLinkEvents {
                override fun availabilityChanged(availability: WatchLinkAvailability) {
                    this@FakeWatchLinkTest.availability += availability
                }

                override fun received(message: ByteArray, respond: Responder, at: ClockReading) {
                    received += Triple(message, respond, at)
                }
            }
        )
    }

    @Test
    fun beforeActivationNothingIsReachableAndASendFailsAtOnce() {
        val probe = ReplyProbe<ByteArray>()

        link.send(byteArrayOf(1), probe.reply)
        queue.drain()

        assertEquals(WatchLinkAvailability(installed = false, reachable = false), link.availability())
        assertEquals(listOf(PortErrorKind.notReachable), probe.kinds)
        assertEquals(0, link.sends.received.size)
    }

    @Test
    fun aReachableWatchAnswersASendOrFailsIt() {
        link.setAvailability(WatchLinkAvailability(installed = true, reachable = true))
        val answered = ReplyProbe<ByteArray>()
        val lost = ReplyProbe<ByteArray>()

        link.send(byteArrayOf(1), answered.reply)
        link.send(byteArrayOf(2), lost.reply)
        link.sends.complete(byteArrayOf(9))
        link.sends.fail(PortError(PortErrorKind.notReachable))
        queue.drain()

        assertEquals(listOf(WatchLinkAvailability(installed = true, reachable = true)), availability)
        assertContentEquals(byteArrayOf(9), answered.values.single())
        assertEquals(listOf(PortErrorKind.notReachable), lost.kinds)
    }

    @Test
    fun aFailedLaunchIsLaunchFailed() {
        link.launches.script = { Answer.Failed(PortError(PortErrorKind.launchFailed)) }
        val probe = ReplyProbe<Unit>()

        link.launchWatchApp(indoor = true, reply = probe.reply)
        queue.drain()

        assertEquals(listOf(true), link.launches.received)
        assertEquals(listOf(PortErrorKind.launchFailed), probe.kinds)
    }

    @Test
    fun aWatchMessageIsStampedAndItsResponderRecordsTheReply() {
        clock.advanceTo(2_000)
        val source = byteArrayOf(1, 2)

        val responder = link.receive(source)
        source[0] = 7
        assertFalse(received.isNotEmpty())
        queue.drain()
        val (message, respond, at) = received.single()
        respond.reply(byteArrayOf(3))

        assertContentEquals(byteArrayOf(1, 2), message)
        assertEquals(2_000, at.monoUs)
        assertContentEquals(byteArrayOf(3), responder.replies.single())
    }
}
