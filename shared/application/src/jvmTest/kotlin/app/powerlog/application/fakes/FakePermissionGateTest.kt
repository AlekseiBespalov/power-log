package app.powerlog.application.fakes

import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FakePermissionGateTest {
    private val queue = FakePortQueue()
    private val gate = FakePermissionGate(queue::post)

    @Test
    fun statusAnswersAfterTheCallReturns() {
        val probe = ReplyProbe<PermissionStates>()

        gate.status(probe.reply)

        assertFalse(probe.settled)
        queue.drain()
        assertEquals(listOf(FakePermissionGate.undetermined), probe.values)
        assertNull(probe.values.single().notifications)
    }

    @Test
    fun aRefusalShowsInTheNextStatus() {
        gate.requests.script = { Answer.Held }
        val request = ReplyProbe<PermissionStates>()
        val refused = FakePermissionGate.undetermined.copy(location = PermissionState.denied)

        gate.request(setOf(PermissionKind.bluetooth, PermissionKind.location), request.reply)
        gate.answer(refused)
        val status = ReplyProbe<PermissionStates>()
        gate.status(status.reply)
        queue.drain()

        assertEquals(listOf(setOf(PermissionKind.bluetooth, PermissionKind.location)), gate.requests.received)
        assertEquals(listOf(refused), request.values)
        assertEquals(listOf(refused), status.values)
    }

    @Test
    fun aRequestWhileAnotherRunsIsBusy() {
        gate.requests.script = { Answer.Held }
        val first = ReplyProbe<PermissionStates>()
        val second = ReplyProbe<PermissionStates>()

        gate.request(setOf(PermissionKind.location), first.reply)
        gate.request(setOf(PermissionKind.notifications), second.reply)
        queue.drain()

        assertFalse(first.settled)
        assertEquals(listOf(PortErrorKind.busy), second.kinds)
    }

    @Test
    fun aRequestWithoutUiIsUiUnavailable() {
        gate.uiAvailable = false
        val probe = ReplyProbe<PermissionStates>()

        gate.request(setOf(PermissionKind.bluetooth), probe.reply)
        queue.drain()

        assertEquals(listOf(PortErrorKind.uiUnavailable), probe.kinds)
    }
}
