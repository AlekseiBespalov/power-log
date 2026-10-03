package app.powerlog.bridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.HandlerThread
import android.os.Looper
import expo.modules.adapters.react.permissions.PermissionsService
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.permissions.PermissionsResponseListener
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.events.EventListenerWithSenderAndPayload
import expo.modules.kotlin.events.EventName
import expo.modules.kotlin.events.OnActivityResultPayload
import expo.modules.kotlin.functions.AsyncFunctionWithPromiseComponent
import expo.modules.kotlin.modules.Module
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    instrumentedPackages = ["app.powerlog.bridge"],
    shadows = [WorkoutPermissionsTest.PermissionAppContext::class],
)
class WorkoutPermissionsTest {
    @Implements(value = AppContext::class, isInAndroidSdk = false)
    class PermissionAppContext {
        lateinit var context: Context
        lateinit var activity: Activity
        lateinit var manager: Permissions

        @Implementation fun getReactContext(): Context = context

        @Implementation fun getThrowingActivity(): Activity = activity

        @Implementation fun getPermissions(): Permissions = manager
    }

    private class PermissionModule : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val healthAccess = FakeHealthConnectAccess()
        val engine: RecordingEngine
        val permissionRequests = mutableListOf<Set<String>>()
        private val appContext = Shadow.newInstanceOf(AppContext::class.java)
        private val module = CycBridgeModule()
        private val definition = module.definition()

        init {
            shadowOf(context)
                .grantPermissions(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
            val locations = context.getSystemService(LocationManager::class.java)
            shadowOf(locations).setLocationEnabled(true)
            shadowOf(locations).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            engine = RecordingEngine.get(context, healthAccess = healthAccess)
            engine.awaitReady()
            healthAccess.grants = engine.health.essentialPermissions()
            command { engine.handler.removeCallbacks(field("tick") as Runnable) }
            Shadow.extract<PermissionAppContext>(appContext).apply {
                context = this@PermissionModule.context
                activity = this@PermissionModule.activity
                manager =
                    object : PermissionsService(context) {
                        override fun askForPermissions(
                            responseListener: PermissionsResponseListener,
                            vararg permissions: String,
                        ) {
                            permissionRequests.add(permissions.toSet())
                            responseListener.onResult(emptyMap())
                        }
                    }
            }
            Module::class
                .java
                .getDeclaredField("_appContextHolder")
                .apply { isAccessible = true }
                .set(module, WeakReference(appContext))
        }

        private fun field(name: String): Any? =
            RecordingEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

        fun <T> command(body: () -> T): T {
            val result = CompletableFuture<T>()
            engine.handler.post {
                try {
                    result.complete(body())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
            return result.get(20, TimeUnit.SECONDS)
        }

        @Suppress("UNCHECKED_CAST")
        fun call(name: String, gps: Boolean): CompletableFuture<Any?> {
            val result = CompletableFuture<Any?>()
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        result.complete(value)
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        result.completeExceptionally(AssertionError("$code: $message", cause))
                    }
                }
            val function = definition.asyncFunctions.getValue(name) as AsyncFunctionWithPromiseComponent
            val body =
                AsyncFunctionWithPromiseComponent::class
                    .java
                    .getDeclaredField("body")
                    .apply { isAccessible = true }
                    .get(function) as (Array<out Any?>, Promise) -> Unit
            body(arrayOf(mapOf("indoor" to false, "saveToHealth" to true, "recordGPS" to gps)), promise)
            drain()
            return result
        }

        private fun drain() {
            engine.reads.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            command {}
        }

        @Suppress("UNCHECKED_CAST")
        fun finishHealthRequest() {
            val listener =
                definition.eventListeners.getValue(EventName.ON_ACTIVITY_RESULT)
                    as EventListenerWithSenderAndPayload<Activity, OnActivityResultPayload>
            listener.call(activity, OnActivityResultPayload(8642, Activity.RESULT_OK, null))
            drain()
        }

        override fun close() {
            command {
                val id = engine.state().str("id")
                if (id.isNotEmpty()) engine.action("discard", id)
            }
            (field("healthWork") as ExecutorService).apply {
                shutdown()
                awaitTermination(20, TimeUnit.SECONDS)
            }
            command { engine.handler.removeCallbacksAndMessages(null) }
            (field("thread") as HandlerThread).apply {
                quitSafely()
                join(20000)
            }
            engine.reads.shutdownNow()
            engine.store.close()
            RecordingEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun startWorkoutContinuesToEngineWithoutRequestingMissingOptionalHealthGrants() {
        PermissionModule().use { module ->
            for (gps in listOf(false, true)) {
                val result = module.call("startWorkout", gps)
                assertNull(shadowOf(module.activity).nextStartedActivityForResult)
                assertEquals(1, module.permissionRequests.size)
                val state = result.get(20, TimeUnit.SECONDS) as Map<*, *>
                assertEquals("running", state["phase"])
                module.command {
                    val id = state["id"] as String
                    assertEquals(id, module.engine.state()["id"])
                    val metadata = module.engine.store.metadata(id)
                    assertTrue(metadata.flag("saveToHealth"))
                    assertEquals(gps, metadata.flag("recordGPS"))
                    module.engine.action("discard", id)
                }
                assertEquals(module.engine.health.essentialPermissions(), module.healthAccess.grants)
                module.permissionRequests.clear()
            }
        }
    }

    @Test
    fun explicitSetupRequestsTheFullSelectedHealthTypesIncludingDistanceWithoutGPS() {
        PermissionModule().use { module ->
            for (gps in listOf(false, true)) {
                module.healthAccess.grants = module.engine.health.essentialPermissions()
                val result = module.call("requestWorkoutPermissions", gps)
                val request = requireNotNull(shadowOf(module.activity).nextStartedActivityForResult)
                assertEquals(8642, request.requestCode)
                assertEquals(HealthPermissionsActivity::class.java.name, request.intent.component?.className)
                assertEquals(gps, request.intent.getBooleanExtra("gps", !gps))
                assertFalse(result.isDone)
                assertTrue(module.permissionRequests.isEmpty())
                val expected = buildSet {
                    add("android.permission.health.WRITE_EXERCISE")
                    add("android.permission.health.WRITE_POWER")
                    add("android.permission.health.WRITE_DISTANCE")
                    if (gps) {
                        add("android.permission.health.WRITE_SPEED")
                        add("android.permission.health.WRITE_EXERCISE_ROUTE")
                    }
                }
                val healthActivity =
                    Robolectric.buildActivity(HealthPermissionsActivity::class.java, request.intent).create()
                try {
                    val permissions = requireNotNull(shadowOf(healthActivity.get()).lastRequestedPermission)
                    assertEquals(expected, permissions.requestedPermissions.toSet())
                } finally {
                    healthActivity.destroy()
                }
                module.healthAccess.grants = expected
                module.finishHealthRequest()
                val status = result.get(20, TimeUnit.SECONDS) as Map<*, *>
                val health = status["health"] as Map<*, *>
                val writes = health["writeAuthorization"] as Map<*, *>
                expected.forEach { assertEquals("authorized", writes[it]) }
                assertEquals(1, module.permissionRequests.size)
                assertEquals("idle", module.command { module.engine.state()["phase"] })
                module.permissionRequests.clear()
            }
        }
    }

    @Test
    fun invalidOptionsRejectBeforeAccessingThePermissionContextOrStarting() {
        val module = CycBridgeModule()
        val request =
            CycBridgeModule::class
                .java
                .getDeclaredMethod(
                    "ridePermissions",
                    Map::class.java,
                    Promise::class.java,
                    Boolean::class.javaPrimitiveType,
                    Function1::class.java,
                )
                .apply { isAccessible = true }
        val invalid =
            rejectedRideOptions() +
                listOf("useWatch", "saveToHealth", "recordGPS").flatMap { field ->
                    listOf(0, "false", emptyList<Any>(), emptyMap<String, Any>()).map {
                        field to mapOf("indoor" to false, field to it)
                    }
                }
        for ((field, input) in invalid) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid options were accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_PERMISSIONS", code)
                        assertTrue(message.orEmpty().contains(field))
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            request.invoke(module, input, promise, true, { _: RideOptions -> fail("Permission continuation ran") })
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedConnectRejectsBeforeAccessingTheEngineOrBluetoothContext() {
        val module = CycBridgeModule()
        val connect =
            CycBridgeModule::class.java.getDeclaredMethod("connect", Map::class.java, Promise::class.java).apply {
                isAccessible = true
            }
        val invalid =
            rejectedRideOptions()
                .filter { it.first == "sampleHz" }
                .map {
                    "hz" to mapOf("deviceId" to "bike", "hz" to it.second["sampleHz"])
                } +
                listOf(null, "", " ", true, 42).map { "deviceId" to mapOf("deviceId" to it, "hz" to 4) } +
                listOf("deviceId" to emptyMap())
        for ((field, input) in invalid) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid connect was accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_BLUETOOTH", code)
                        assertTrue(message.orEmpty().contains(field))
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            connect.invoke(module, input, promise)
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedCatalogAndMonitorRequestsRejectBeforeOpeningTheStore() {
        val module = CycBridgeModule()
        val read =
            CycBridgeModule::class
                .java
                .getDeclaredMethod(
                    "read",
                    Map::class.java,
                    Promise::class.java,
                    Function1::class.java,
                    Function1::class.java,
                )
                .apply { isAccessible = true }
        val cases =
            listOf<Pair<Payload, (Payload) -> Any>>(
                mapOf("limit" to 1.5) to { BridgeInputs.catalog(it) },
                mapOf("beforeID" to "ride") to { BridgeInputs.catalog(it) },
                mapOf("source" to "unknown", "generation" to 0) to
                    {
                        BridgeInputs.monitor(MonitorOperation.Describe, it)
                    },
                mapOf(
                    "source" to "workout",
                    "id" to "ride",
                    "generation" to 0,
                    "metrics" to listOf("humanPowerW", 1),
                ) to { BridgeInputs.monitor(MonitorOperation.Latest, it) },
            )
        for ((input, parse) in cases) {
            var rejected = false
            val promise =
                object : Promise {
                    override fun resolve(value: Any?) {
                        fail("Invalid read was accepted")
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = true
                        assertEquals("E_POWER_LOG", code)
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            read.invoke(module, input, promise, parse, { _: Any -> fail("Read continuation ran") })
            assertTrue(rejected)
        }
    }
}
