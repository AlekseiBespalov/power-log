package app.powerlog.platform.host

import android.os.Handler
import android.os.HandlerThread
import app.powerlog.runtime.clock.AndroidClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DevHooksTest {
    @Test
    fun theSwitchesAreReadAndTheirTimersRemovedOnRideAfterCreateReturns() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, DevHooks.FILE)
        val switches = """{"bike":true,"slice":true,"crashAfterSeconds":600,"killAfterSeconds":900}"""
        file.writeText(switches)
        val ride = HandlerThread("PowerLogRide").apply { start() }
        try {
            val held = CountDownLatch(1)
            Handler(ride.looper).post { held.await() }
            DevHooks.create(context, AndroidClock(), ride)
            assertEquals(switches, file.readText())
            held.countDown()
            val drained = CountDownLatch(1)
            Handler(ride.looper).post { drained.countDown() }
            assertTrue(drained.await(10, TimeUnit.SECONDS))
            assertEquals(
                """{"bike":true,"slice":true,"crashAfterSeconds":null,"killAfterSeconds":null}""",
                file.readText(),
            )
        } finally {
            ride.quitSafely()
        }
    }
}
