package app.powerlog.platform.host

import android.os.Handler
import android.os.HandlerThread
import app.powerlog.platform.device.AndroidDeviceInfo
import app.powerlog.platform.files.AndroidFilePicker
import app.powerlog.platform.permissions.AndroidPermissionGate
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.clock.AndroidClock
import app.powerlog.runtime.compose.AndroidRuntime
import app.powerlog.runtime.compose.AndroidThreads
import app.powerlog.runtime.compose.Ports
import expo.modules.kotlin.events.BasicEventListener
import expo.modules.kotlin.events.EventName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    instrumentedPackages = ["app.powerlog.platform"],
    shadows = [ModuleAppContext::class],
)
class ModuleSessionTest {
    private val threads =
        AndroidThreads(started("PowerLogRide"), started("PowerLogHealth"), started("PowerLogMaintenance"))

    private fun started(name: String) = HandlerThread(name).apply { start() }

    @After
    fun forgetRuntime() {
        PowerLogAndroid.runtime = null
        for (thread in listOf(threads.ride, threads.health, threads.maintenance)) thread.quitSafely()
    }

    @Test
    fun theModuleOpensItsSessionWhenCreatedAndClosesItWhenDestroyed() {
        val application = RuntimeEnvironment.getApplication()
        val ports =
            Ports(
                permissions = AndroidPermissionGate(application, PowerLogAndroid.ui, Handler(threads.ride.looper)),
                picker = AndroidFilePicker(application, PowerLogAndroid.ui, Handler(threads.ride.looper)),
                device = AndroidDeviceInfo(application),
                ble = null,
                location = null,
                health = null,
                keepAlive = null,
                tabLock = null,
                persistence = null,
                rideCard = null,
                watch = null,
                scheduler = null,
                tones = null,
            )
        PowerLogAndroid.runtime = AndroidRuntime.create(application, threads, AndroidClock(), ports, devTools = null)
        val module = PowerLogModule { RuntimeEnvironment.getApplication() }
        val appContext = org.robolectric.shadow.api.Shadow.newInstanceOf(expo.modules.kotlin.AppContext::class.java)
        expo.modules.kotlin.modules.Module::class
            .java
            .getDeclaredField("_appContextHolder")
            .apply { isAccessible = true }
            .set(module, java.lang.ref.WeakReference(appContext))
        val session = PowerLogModule::class.java.getDeclaredField("session").apply { isAccessible = true }
        val listeners = module.definition().eventListeners
        fun fire(event: EventName) = (listeners.getValue(event) as BasicEventListener).call()

        assertNull(session.get(module))
        fire(EventName.MODULE_CREATE)
        val opened = session.get(module) as BridgeSession
        val answers = mutableListOf<String>()
        opened.call("apiVersion", "{}") { answers += it }
        assertEquals(1, answers.size)

        fire(EventName.MODULE_DESTROY)
        assertNull(session.get(module))
        opened.call("apiVersion", "{}") { answers += it }
        assertEquals(1, answers.size)
    }
}
