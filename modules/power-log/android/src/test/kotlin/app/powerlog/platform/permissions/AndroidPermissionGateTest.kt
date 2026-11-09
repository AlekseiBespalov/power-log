package app.powerlog.platform.permissions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.platform.ui.AndroidUiContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidPermissionGateTest {
    private val context = RuntimeEnvironment.getApplication()
    private val ui = FakeUi()
    private val gate = AndroidPermissionGate(context, ui, Handler(Looper.getMainLooper()))
    private val answers = mutableListOf<PermissionStates>()
    private val failures = mutableListOf<PortError>()
    private val reply
        get() = Reply<PermissionStates>({ answers += it }, { failures += it })

    @Test
    fun nothingAskedIsUndecidedAndAStatusAnswersAfterTheCallReturns() {
        gate.status(reply)
        assertTrue(answers.isEmpty())
        idle()

        val states = answers.single()
        assertEquals(PermissionState.notDetermined, states.bluetooth)
        assertEquals(PermissionState.notDetermined, states.location)
        assertNull(states.preciseLocation)
        assertEquals(PermissionState.notDetermined, states.notifications)
    }

    @Test
    fun oneRequestAsksForEveryKindOnceAndARefusalReadsDeniedAfterwards() {
        gate.request(PermissionKind.entries.toSet(), reply)
        idle()
        assertEquals(
            listOf(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            ),
            ui.asked,
        )
        assertTrue(ui.onMain.single())

        ui.answer(granted = emptyList())
        idle()
        val refused = answers.single()
        assertEquals(PermissionState.denied, refused.bluetooth)
        assertEquals(PermissionState.denied, refused.location)
        assertEquals(PermissionState.denied, refused.notifications)

        gate.status(reply)
        idle()
        assertEquals(refused, answers.last())
    }

    @Test
    fun grantedPermissionsReadGrantedAndCoarseLocationIsNotPrecise() {
        shadowOf(context)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        gate.status(reply)
        idle()

        val states = answers.single()
        assertEquals(PermissionState.granted, states.bluetooth)
        assertEquals(PermissionState.granted, states.location)
        assertEquals(false, states.preciseLocation)
        assertEquals(false, states.locationServicesOn)
        assertEquals(PermissionState.granted, states.notifications)
    }

    @Test
    fun onlyTheAskedKindIsMarkedAsAsked() {
        gate.request(setOf(PermissionKind.bluetooth), reply)
        idle()
        ui.answer(granted = emptyList())
        idle()

        val states = answers.single()
        assertEquals(PermissionState.denied, states.bluetooth)
        assertEquals(PermissionState.notDetermined, states.location)
        assertEquals(PermissionState.notDetermined, states.notifications)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsBusy() {
        gate.request(setOf(PermissionKind.location), reply)
        gate.request(setOf(PermissionKind.location), reply)
        idle()

        assertEquals(listOf(PortError(PortErrorKind.busy)), failures)
        assertEquals(1, ui.asked.size)
    }

    @Test
    fun withoutAnActivityARequestIsUiUnavailableAndTheNextOneMayAsk() {
        ui.available = false
        gate.request(setOf(PermissionKind.location), reply)
        idle()
        assertEquals(listOf(PortError(PortErrorKind.uiUnavailable)), failures)

        ui.available = true
        gate.request(setOf(PermissionKind.location), reply)
        idle()
        assertEquals(1, ui.asked.size)
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13NotificationsAreNotReportedAndNotAsked() {
        gate.request(setOf(PermissionKind.notifications), reply)
        idle()

        assertTrue(ui.asked.isEmpty())
        assertNull(answers.single().notifications)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FakeUi : AndroidUiContext {
        var available = true
        val asked = mutableListOf<List<String>>()
        val onMain = mutableListOf<Boolean>()
        private var done: ((Map<String, Boolean>) -> Unit)? = null

        override fun activity(): Activity? = null

        override fun startForResult(intent: Intent, done: (resultCode: Int?, data: Intent?) -> Unit) = done(null, null)

        override fun requestPermissions(permissions: List<String>, done: (Map<String, Boolean>) -> Unit): Boolean {
            if (!available) return false
            onMain += Looper.myLooper() == Looper.getMainLooper()
            asked += permissions
            this.done = done
            return true
        }

        fun answer(granted: List<String>) {
            val permissions = asked.last()
            done?.invoke(permissions.associateWith { it in granted })
        }
    }
}
