package app.powerlog.application.recording

import app.powerlog.application.Logs
import app.powerlog.application.api.Answer
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.Published
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.api.answer
import app.powerlog.application.api.failure
import app.powerlog.application.api.occurrence
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HealthScope
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortErrorKind
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideSettings
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class Permissions(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val gate: PermissionGate,
    private val health: HealthProvider?,
    private val gpsAvailable: () -> Boolean,
    private val device: DeviceInfo,
    private val settings: () -> RideSettings,
    clock: Clock,
    private val logs: Logs,
) {
    var states: PermissionStates? = null
        private set

    private var grants: HealthGrants? = null

    private var failure: ApiFailure? = null
    private val read = CompletableDeferred<Unit>()
    private val stream = Published(view(), 0, clock)

    suspend fun next(after: Long?): StreamAnswer<PermissionsView> {
        read.await()
        return stream.next(after)
    }

    fun reread() {
        scope.launch(ride) { refresh() }
    }

    suspend fun refresh() {
        when (val status = answer<PermissionStates> { gate.status(it) }) {
            is Answer.Ok -> {
                states = status.value
                failure = null
            }
            is Answer.Failed -> {
                val code =
                    if (status.error.kind == PortErrorKind.uiUnavailable) FailureCode.uiUnavailable
                    else FailureCode.unknown
                failure = occurrence(code, failure)
            }
        }
        val provider = health
        grants =
            if (provider == null || provider.access.availability() != HealthAvailability.available) null
            else (answer<HealthGrants> { provider.access.grants(it) } as? Answer.Ok)?.value
        read.complete(Unit)
        publish()
    }

    suspend fun request(): Outcome<Unit> {
        val ride = settings()
        val platform = device.snapshot().platform
        val gps = gpsAvailable() && (ride.recordGps ?: !ride.indoor)
        val provider = health
        if (provider != null && (ride.saveToHealth || ride.useWatch)) {
            val writes = if (!ride.useWatch && ride.saveToHealth) HealthComponent.entries.toSet() else emptySet()
            val grants = (answer<HealthGrants> { provider.access.grants(it) } as? Answer.Ok)?.value
            val scope =
                HealthScope(
                    write = writes,
                    read = HealthMetrics.readback(provider.kind).map { it.wire }.toSet(),
                    route = gps,
                    backgroundRead = grants?.backgroundRead != null,
                )
            if (grants == null || !granted(scope, grants, provider.kind)) {
                val requested = answer<Unit> { provider.access.request(scope, it) }
                if (requested is Answer.Failed) {
                    logs.failure("health", requested.error)
                    if (requested.error.kind == PortErrorKind.uiUnavailable) {
                        refresh()
                        return Outcome.Failed(failure(FailureCode.uiUnavailable))
                    }
                    if (platform == Platform.ios) {
                        refresh()
                        return Outcome.Ok(Unit)
                    }
                }
            }
        }
        val current = (answer<PermissionStates> { gate.status(it) } as? Answer.Ok)?.value
        val kinds =
            setOfNotNull(
                    PermissionKind.bluetooth.takeIf { platform != Platform.web },
                    PermissionKind.location.takeIf { gps },
                    PermissionKind.notifications.takeIf {
                        platform == Platform.android && (device.snapshot().sdkLevel ?: 0) >= NOTIFICATIONS_SDK
                    },
                )
                .filterNot { current != null && state(current, it) == PermissionState.granted }
                .toSet()
        if (kinds.isNotEmpty())
            when (val answered = answer<PermissionStates> { gate.request(kinds, it) }) {
                is Answer.Ok -> answered(answered.value)
                is Answer.Failed -> {
                    logs.failure("permissions", answered.error)
                    if (answered.error.kind == PortErrorKind.uiUnavailable) {
                        refresh()
                        return Outcome.Failed(failure(FailureCode.uiUnavailable))
                    }
                }
            }
        refresh()
        return Outcome.Ok(Unit)
    }

    private fun state(states: PermissionStates, kind: PermissionKind): PermissionState? =
        when (kind) {
            PermissionKind.bluetooth -> states.bluetooth
            PermissionKind.location -> states.location
            PermissionKind.notifications -> states.notifications
        }

    private fun granted(scope: HealthScope, grants: HealthGrants, kind: HealthProviderKind): Boolean =
        scope.write.all { grants.write[it] == PermissionState.granted } &&
            (!scope.route || grants.route == PermissionState.granted) &&
            (kind != HealthProviderKind.healthConnect || scope.read.all { grants.read[it] == PermissionState.granted })

    fun answered(next: PermissionStates) {
        states = next
        publish()
    }

    fun publish() {
        stream.publish(view(), urgent = true)
    }

    private companion object {
        const val NOTIFICATIONS_SDK = 33
    }

    private fun view(): PermissionsView {
        val action = action()
        return PermissionsView(action?.first, action?.second, failure)
    }

    private fun action(): Pair<PermissionAction, PermissionNeed>? {
        val ride = settings()
        val provider = health
        val savesToHealth = provider != null && !ride.useWatch && ride.saveToHealth
        val gps = gpsAvailable() && (ride.recordGps ?: !ride.indoor)
        if (!savesToHealth && !gps) return null
        if (savesToHealth && provider.access.availability() != HealthAvailability.available)
            return PermissionAction.unavailable to PermissionNeed.health
        val writes = if (savesToHealth) writes(provider.kind, gps) else emptyList()
        val connect = provider?.kind == HealthProviderKind.healthConnect
        val states = states
        val location = states?.location.takeIf { gps }
        val settingsAction =
            if (device.snapshot().platform == Platform.web) PermissionAction.check else PermissionAction.openAppSettings
        return when {
            writes.any { it == PermissionState.notDetermined || connect && it != PermissionState.granted } ->
                PermissionAction.request to PermissionNeed.health
            location == PermissionState.notDetermined -> PermissionAction.request to PermissionNeed.location
            writes.any { it == PermissionState.denied } -> PermissionAction.healthHelp to PermissionNeed.health
            !gps -> null
            states?.locationServicesOn == false -> settingsAction to PermissionNeed.locationServices
            location == PermissionState.restricted -> settingsAction to PermissionNeed.locationRestricted
            location == PermissionState.denied -> settingsAction to PermissionNeed.location
            states?.preciseLocation == false -> PermissionAction.openAppSettings to PermissionNeed.preciseLocation
            location != PermissionState.granted -> PermissionAction.check to PermissionNeed.location
            else -> null
        }.let { found ->
            found
                ?: (PermissionAction.check to PermissionNeed.health).takeIf {
                    writes.any { it != PermissionState.granted }
                }
        }
    }

    private fun writes(kind: HealthProviderKind, gps: Boolean): List<PermissionState?> {
        val required =
            when (kind) {
                HealthProviderKind.appleHealth ->
                    listOf(HealthComponent.workout, HealthComponent.riderPower, HealthComponent.cyclingCadence) +
                        if (gps)
                            listOf(HealthComponent.route, HealthComponent.cyclingSpeed, HealthComponent.cyclingDistance)
                        else emptyList()
                HealthProviderKind.healthConnect ->
                    listOf(HealthComponent.workout, HealthComponent.riderPower, HealthComponent.cyclingDistance) +
                        if (gps) listOf(HealthComponent.cyclingSpeed, HealthComponent.route) else emptyList()
            }
        val grants = grants ?: return required.map { null }
        return required.map { grants.write[it] ?: PermissionState.notDetermined }
    }
}
