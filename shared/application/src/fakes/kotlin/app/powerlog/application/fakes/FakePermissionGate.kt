package app.powerlog.application.fakes

import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply

class FakePermissionGate(
    private val post: (() -> Unit) -> Unit,
    var states: PermissionStates = undetermined,
) : PermissionGate {
    var uiAvailable: Boolean = true

    val requests: FakeRequests<Set<PermissionKind>, PermissionStates> = FakeRequests(post) { Answer.Ok(states) }

    override fun status(reply: Reply<PermissionStates>) {
        val now = states
        post { reply.ok(now) }
    }

    override fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>) {
        when {
            !uiAvailable -> post { reply.failed(PortError(PortErrorKind.uiUnavailable)) }
            requests.pending > 0 -> post { reply.failed(PortError(PortErrorKind.busy)) }
            else -> requests.take(kinds, reply)
        }
    }

    fun answer(next: PermissionStates) {
        states = next
        requests.complete(next)
    }

    companion object {
        val undetermined: PermissionStates =
            PermissionStates(
                bluetooth = PermissionState.notDetermined,
                location = PermissionState.notDetermined,
                preciseLocation = null,
                locationServicesOn = true,
                notifications = null,
            )
    }
}
