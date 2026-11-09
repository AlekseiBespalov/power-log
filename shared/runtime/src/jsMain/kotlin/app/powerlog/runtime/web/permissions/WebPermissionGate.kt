package app.powerlog.runtime.web.permissions

import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import kotlin.js.Promise

internal class WebPermissionGate(
    private val bluetooth: Boolean,
    private val geolocation: PositionRequests?,
    private val permissions: Permissions?,
    private val post: (() -> Unit) -> Unit,
) : PermissionGate {
    private var requesting = false
    private var answered: PermissionState? = null

    override fun status(reply: Reply<PermissionStates>) {
        location { state -> reply.ok(states(state)) }
    }

    override fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>) {
        if (requesting) return post { reply.failed(PortError(PortErrorKind.busy)) }
        val geolocation = geolocation
        if (PermissionKind.location !in kinds || geolocation == null) return status(reply)
        requesting = true
        val settle = { state: PermissionState? ->
            requesting = false
            if (state != null) answered = state
            status(reply)
        }
        try {
            geolocation.getCurrentPosition(
                { settle(PermissionState.granted) },
                { error -> settle(if (error.code == 1) PermissionState.denied else null) },
                js("({ enableHighAccuracy: true, maximumAge: 0, timeout: 15000 })"),
            )
        } catch (_: dynamic) {
            post { settle(null) }
        }
    }

    private fun location(done: (PermissionState) -> Unit) {
        if (geolocation == null) return post { done(PermissionState.unavailable) }
        val fallback = { answered ?: PermissionState.notDetermined }
        val query =
            try {
                permissions?.query(js("({ name: 'geolocation' })"))
            } catch (_: dynamic) {
                null
            }
        if (query == null) return post { done(fallback()) }
        query.then(
            onFulfilled = { status ->
                done(
                    when (status.state) {
                        "granted" -> PermissionState.granted
                        "denied" -> PermissionState.denied
                        else -> PermissionState.notDetermined
                    }
                )
            },
            onRejected = { done(fallback()) },
        )
    }

    private fun states(location: PermissionState): PermissionStates =
        PermissionStates(
            bluetooth = if (bluetooth) PermissionState.notDetermined else PermissionState.unavailable,
            location = location,
            preciseLocation = null,
            locationServicesOn = null,
            notifications = null,
        )
}

internal external interface PositionRequests {
    fun getCurrentPosition(success: (dynamic) -> Unit, error: (PositionError) -> Unit, options: dynamic)
}

internal external interface PositionError {
    val code: Int
}

internal external interface Permissions {
    fun query(descriptor: dynamic): Promise<PermissionStatus>
}

internal external interface PermissionStatus {
    val state: String
}
