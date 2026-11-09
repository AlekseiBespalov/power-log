package app.powerlog.platform.host

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.permissions.PermissionsResponse
import expo.modules.interfaces.permissions.PermissionsResponseListener
import expo.modules.interfaces.permissions.PermissionsStatus
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HostUiContextTest {
    private val owner = Any()
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @After
    fun detach() {
        HostUiContext.detach(owner)
    }

    private fun start(action: String, done: (Int?, Intent?) -> Unit) {
        HostUiContext.startForResult(Intent(action), done)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun withoutAModuleThereIsNoActivityPromptOrStartedActivity() {
        val results = mutableListOf<Int?>()
        assertNull(HostUiContext.activity())
        assertFalse(HostUiContext.requestPermissions(listOf("android.permission.BLUETOOTH_SCAN")) {})
        start("app.powerlog.test") { code, _ -> results += code }
        assertEquals(listOf<Int?>(null), results)
    }

    @Test
    fun anActivityResultReachesTheCallerThatStartedItOnce() {
        HostUiContext.attach(owner, { activity }, { null })
        val results = mutableListOf<String>()
        start("first") { code, data -> results += "first $code ${data?.action}" }
        start("second") { code, _ -> results += "second $code" }
        val started =
            List(2) { shadowOf(activity).nextStartedActivityForResult }.associate { it.intent.action to it.requestCode }
        val firstCode = started.getValue("first")
        val secondCode = started.getValue("second")

        assertTrue(HostUiContext.result(secondCode, Activity.RESULT_CANCELED, null))
        assertTrue(HostUiContext.result(firstCode, Activity.RESULT_OK, Intent("answer")))
        assertFalse(HostUiContext.result(firstCode, Activity.RESULT_OK, null))
        assertEquals(firstCode + 1, secondCode)
        assertTrue(firstCode >= 8643)
        assertEquals(listOf("second ${Activity.RESULT_CANCELED}", "first ${Activity.RESULT_OK} answer"), results)
    }

    @Test
    fun anActivityThatCannotStartAnswersNullAndLeavesNoPendingResult() {
        val refusing = Robolectric.buildActivity(Refusing::class.java).setup().get()
        HostUiContext.attach(owner, { refusing }, { null })
        val results = mutableListOf<Int?>()
        start("nowhere") { code, _ -> results += code }
        HostUiContext.attach(owner, { activity }, { null })
        start("next") { code, _ -> results += code }
        val code = shadowOf(activity).nextStartedActivityForResult.requestCode

        assertFalse(HostUiContext.result(code - 1, Activity.RESULT_OK, null))
        assertEquals(listOf<Int?>(null), results)
    }

    @Test
    fun onlyTheAttachedModulesDetachCancelsItsPendingResults() {
        val results = mutableListOf<Int?>()
        val reloaded = Any()
        HostUiContext.attach(reloaded, { activity }, { null })
        HostUiContext.attach(owner, { activity }, { null })
        start("pending") { code, _ -> results += code }

        HostUiContext.detach(reloaded)
        assertEquals(activity, HostUiContext.activity())
        assertEquals(emptyList<Int?>(), results)
        HostUiContext.detach(owner)
        assertNull(HostUiContext.activity())
        assertEquals(listOf<Int?>(Activity.RESULT_CANCELED), results)
    }

    class Refusing : Activity() {
        @Deprecated("Deprecated in Java")
        override fun startActivityForResult(intent: Intent, requestCode: Int) {
            throw ActivityNotFoundException()
        }
    }

    @Test
    fun permissionsAreAskedThroughExpoAndAnsweredAsGrantedOrNot() {
        val asked = mutableListOf<List<String>>()
        val permissions =
            Proxy.newProxyInstance(Permissions::class.java.classLoader, arrayOf(Permissions::class.java)) {
                _,
                method,
                args ->
                if (method.name == "askForPermissions") {
                    @Suppress("UNCHECKED_CAST") val names = (args[1] as Array<String>).toList()
                    asked += names
                    (args[0] as PermissionsResponseListener).onResult(
                        mapOf(
                            names[0] to PermissionsResponse(PermissionsStatus.GRANTED),
                            names[1] to PermissionsResponse(PermissionsStatus.DENIED),
                        )
                    )
                }
                null
            } as Permissions
        HostUiContext.attach(owner, { activity }, { permissions })
        val answers = mutableListOf<Map<String, Boolean>>()

        assertTrue(HostUiContext.requestPermissions(listOf("scan", "fine")) { answers += it })

        assertEquals(listOf(listOf("scan", "fine")), asked)
        assertEquals(listOf(mapOf("scan" to true, "fine" to false)), answers)
    }
}
