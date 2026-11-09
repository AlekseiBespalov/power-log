package app.powerlog.runtime.web.permissions

import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.runtime.web.location.FakeGeolocation
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class WebPermissionGateTest {
    private val geolocation = FakeGeolocation()
    private val post: (() -> Unit) -> Unit = { block -> Promise.resolve(Unit).then { block() } }

    @Test
    fun withoutWebBluetoothOrGeolocationBothAreUnavailableAndNotificationsAreNotReported() = runTest {
        val states = status(WebPermissionGate(false, null, null, post))

        assertEquals(PermissionState.unavailable, states.bluetooth)
        assertEquals(PermissionState.unavailable, states.location)
        assertNull(states.preciseLocation)
        assertNull(states.locationServicesOn)
        assertNull(states.notifications)
    }

    @Test
    fun bluetoothIsUndecidedSinceTheChooserGrantsPerDeviceAndLocationComesFromThePermissionsQuery() = runTest {
        val query = permissions("granted")
        val states = status(WebPermissionGate(true, geolocation.unsafeCast<PositionRequests>(), query, post))

        assertEquals(PermissionState.notDetermined, states.bluetooth)
        assertEquals(PermissionState.granted, states.location)
        assertEquals("geolocation", query.asDynamic().asked)
        assertEquals(PermissionState.denied, status(gate(permissions("denied"))).location)
        assertEquals(PermissionState.notDetermined, status(gate(permissions("prompt"))).location)
    }

    @Test
    fun aLocationRequestAsksForOnePositionAndAnswersWithTheNewStatus() = runTest {
        val gate = gate(null)
        var returned = false
        val answer = Promise { resolve, reject ->
            gate.request(setOf(PermissionKind.bluetooth, PermissionKind.location), reply(resolve, reject))
            returned = true
        }
        val (success, _) = geolocation.currentRequests.single()
        success(js("({})"))

        assertEquals(PermissionState.granted, answer.await().location)
        assertTrue(returned)
    }

    @Test
    fun aRefusedPositionIsRememberedAsDeniedWhereThePermissionsQueryIsMissing() = runTest {
        val gate = gate(null)
        val answer = Promise { resolve, reject -> gate.request(setOf(PermissionKind.location), reply(resolve, reject)) }
        val error: dynamic = js("({ code: 1 })")
        geolocation.currentRequests.single().second(error)

        assertEquals(PermissionState.denied, answer.await().location)
        assertEquals(PermissionState.denied, status(gate).location)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsBusy() = runTest {
        val gate = gate(null)
        gate.request(setOf(PermissionKind.location), Reply({}, {}))
        val second = Promise { resolve, _ ->
            gate.request(setOf(PermissionKind.location), Reply({ resolve(null) }, { error -> resolve(error) }))
        }

        assertEquals(PortError(PortErrorKind.busy), second.await())
    }

    @Test
    fun aRequestWithoutLocationPromptsNothing() = runTest {
        val gate = gate(permissions("prompt"))
        val answer = Promise { resolve, reject ->
            gate.request(setOf(PermissionKind.bluetooth, PermissionKind.notifications), reply(resolve, reject))
        }

        assertEquals(PermissionState.notDetermined, answer.await().location)
        assertTrue(geolocation.currentRequests.isEmpty())
    }

    private fun gate(query: Permissions?) =
        WebPermissionGate(true, geolocation.unsafeCast<PositionRequests>(), query, post)

    private fun permissions(state: String): Permissions {
        val permissions: dynamic = js("({})")
        permissions.query = { descriptor: dynamic ->
            permissions.asked = descriptor.name
            val status: dynamic = js("({})")
            status.state = state
            Promise.resolve<dynamic>(status)
        }
        return permissions.unsafeCast<Permissions>()
    }

    private suspend fun status(gate: WebPermissionGate): PermissionStates = Promise { resolve, reject ->
        gate.status(reply(resolve, reject))
    }
        .await()

    private fun reply(resolve: (PermissionStates) -> Unit, reject: (Throwable) -> Unit) =
        Reply<PermissionStates>(resolve) { reject(AssertionError("failed: $it")) }
}
