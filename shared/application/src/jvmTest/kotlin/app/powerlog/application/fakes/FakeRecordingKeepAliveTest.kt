package app.powerlog.application.fakes

import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.KeepAliveEvents
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeRecordingKeepAliveTest {
    private val queue = FakePortQueue()
    private val keepAlive = FakeRecordingKeepAlive(queue::post)
    private val expiring = mutableListOf<HoldKind>()

    init {
        keepAlive.attach(
            object : KeepAliveEvents {
                override fun expiring(kind: HoldKind) {
                    expiring += kind
                }
            }
        )
    }

    @Test
    fun holdsAnswerOkAfterTheCall() {
        val ride = ReplyProbe<Unit>()
        val reconnect = ReplyProbe<Unit>()

        keepAlive.hold(HoldKind.ride, location = true, reply = ride.reply)
        keepAlive.hold(HoldKind.reconnect, location = false, reply = reconnect.reply)
        assertTrue(!ride.settled && !reconnect.settled)
        queue.drain()

        assertEquals(listOf(Unit), ride.values)
        assertEquals(listOf(Unit), reconnect.values)
        assertEquals(setOf(HoldKind.ride, HoldKind.reconnect), keepAlive.held)
        assertEquals(FakeRecordingKeepAlive.Hold(HoldKind.ride, location = true), keepAlive.holds.received.first())
    }

    @Test
    fun refusalsArriveAsTheirKinds() {
        val kinds = listOf(PortErrorKind.notAllowedInBackground, PortErrorKind.denied, PortErrorKind.failed)
        val probes = kinds.map { kind ->
            keepAlive.holds.script = { Answer.Failed(PortError(kind)) }
            ReplyProbe<Unit>().also { keepAlive.hold(HoldKind.ride, location = false, reply = it.reply) }
        }
        queue.drain()

        assertEquals(kinds, probes.flatMap { it.kinds })
    }

    @Test
    fun aRefusedHoldIsNoLongerHeld() {
        keepAlive.holds.script = { Answer.Failed(PortError(PortErrorKind.notAllowedInBackground)) }
        val probe = ReplyProbe<Unit>()

        keepAlive.hold(HoldKind.ride, location = true, reply = probe.reply)
        queue.drain()

        assertEquals(listOf(PortErrorKind.notAllowedInBackground), probe.kinds)
        assertTrue(keepAlive.held.isEmpty())
    }

    @Test
    fun aPendingHoldStillCompletesOnceAfterRelease() {
        keepAlive.holds.script = { Answer.Held }
        val probe = ReplyProbe<Unit>()

        keepAlive.hold(HoldKind.ride, location = true, reply = probe.reply)
        keepAlive.release(HoldKind.ride)
        keepAlive.holds.complete(Unit)
        queue.drain()

        assertEquals(listOf(Unit), probe.values)
        assertEquals(listOf(HoldKind.ride), keepAlive.released)
        assertTrue(keepAlive.held.isEmpty())
    }

    @Test
    fun anExpiringHoldIsReportedAndEnded() {
        keepAlive.hold(HoldKind.reconnect, location = false, reply = ReplyProbe<Unit>().reply)

        keepAlive.expire(HoldKind.reconnect)
        queue.drain()

        assertEquals(listOf(HoldKind.reconnect), expiring)
        assertTrue(keepAlive.held.isEmpty())
    }
}
