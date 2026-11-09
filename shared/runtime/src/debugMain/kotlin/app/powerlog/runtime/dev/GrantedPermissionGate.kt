package app.powerlog.runtime.dev

import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.Reply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class GrantedPermissionGate(ride: CoroutineDispatcher) : PermissionGate {
    private val scope = CoroutineScope(SupervisorJob() + ride)
    private val granted =
        PermissionStates(
            bluetooth = PermissionState.granted,
            location = PermissionState.granted,
            preciseLocation = true,
            locationServicesOn = true,
            notifications = PermissionState.granted,
        )

    override fun status(reply: Reply<PermissionStates>) {
        scope.launch { reply.ok(granted) }
    }

    override fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>) {
        scope.launch { reply.ok(granted) }
    }
}
