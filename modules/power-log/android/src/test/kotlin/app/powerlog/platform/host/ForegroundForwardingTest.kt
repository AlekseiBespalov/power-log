package app.powerlog.platform.host

import android.content.Intent
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.recording.RideCardState
import app.powerlog.kit.RideCardContent
import app.powerlog.platform.ridecard.RideCardIntents
import app.powerlog.runtime.host.HostFacade
import expo.modules.kotlin.events.BasicEventListener
import expo.modules.kotlin.events.EventListenerWithPayload
import expo.modules.kotlin.events.EventName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = expo.modules.kotlin.AppContext::class, isInAndroidSdk = false)
class ModuleAppContext {
    @Implementation fun getCurrentActivity(): android.app.Activity? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    instrumentedPackages = ["app.powerlog.platform"],
    shadows = [ModuleAppContext::class],
)
class ForegroundForwardingTest {
    private class RecordingHost : HostFacade {
        val calls = mutableListOf<String>()

        override fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit) {
            calls += "launch"
        }

        override fun foregroundChanged(foreground: Boolean) {
            calls += "foreground $foreground"
        }

        override fun didEnterBackground(done: () -> Unit) {
            calls += "background"
        }

        override fun willTerminate(timeoutUs: Long): Boolean {
            calls += "terminate"
            return true
        }

        override fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit) {
            calls += "card ${intent.rideId} ${intent.token} ${intent.phase} ${intent.action}"
        }

        override fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit) {
            calls += "health"
        }
    }

    private val host = RecordingHost()

    @Before
    fun attachHost() {
        PowerLogAndroid.host = host
    }

    @After
    fun forgetHost() {
        PowerLogAndroid.host = null
    }

    @Test
    fun theActivityEnteringTheForegroundOrBackgroundAndTheModulesEndReachTheHost() {
        val host = RecordingHost()
        PowerLogAndroid.host = host
        val module = PowerLogModule { RuntimeEnvironment.getApplication() }
        val appContext = org.robolectric.shadow.api.Shadow.newInstanceOf(expo.modules.kotlin.AppContext::class.java)
        expo.modules.kotlin.modules.Module::class
            .java
            .getDeclaredField("_appContextHolder")
            .apply { isAccessible = true }
            .set(module, java.lang.ref.WeakReference(appContext))
        val listeners = module.definition().eventListeners
        fun fire(event: EventName) = (listeners.getValue(event) as BasicEventListener).call()
        fire(EventName.ACTIVITY_ENTERS_FOREGROUND)
        fire(EventName.ACTIVITY_ENTERS_BACKGROUND)
        fire(EventName.ACTIVITY_ENTERS_FOREGROUND)
        fire(EventName.MODULE_DESTROY)
        assertEquals(
            listOf("foreground true", "foreground false", "background", "foreground true", "foreground false"),
            host.calls,
        )
    }

    @Test
    fun aFinishIntentTheModuleReceivesTwiceBecomesExactlyOneRideCardIntent() {
        val module = PowerLogModule { RuntimeEnvironment.getApplication() }
        val finish =
            RideCardIntents.command(
                Intent(),
                RideCardContent.Control(RideAction.finish, "Finish", "stop", "r", "t", RideCardState.running, true),
            )
        @Suppress("UNCHECKED_CAST")
        val newIntent =
            module.definition().eventListeners.getValue(EventName.ON_NEW_INTENT) as EventListenerWithPayload<Intent>
        newIntent.call(finish)
        newIntent.call(finish)

        assertEquals(listOf("card r t running finish"), host.calls)
    }
}
