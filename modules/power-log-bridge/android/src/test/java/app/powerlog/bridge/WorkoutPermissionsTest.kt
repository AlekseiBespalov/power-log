package app.powerlog.bridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.HandlerThread
import android.os.Looper
import app.powerlog.core.presentation.RideFailure
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
import expo.modules.kotlin.modules.ModuleDefinitionData
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
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

@Suppress("UNCHECKED_CAST")
private fun invokeFunction(definition: ModuleDefinitionData, name: String, args: Array<Any?>, promise: Promise) {
    val function = definition.asyncFunctions.getValue(name) as AsyncFunctionWithPromiseComponent
    val body =
        AsyncFunctionWithPromiseComponent::class
            .java
            .getDeclaredField("body")
            .apply { isAccessible = true }
            .get(function) as (Array<out Any?>, Promise) -> Unit
    body(args, promise)
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    instrumentedPackages = ["app.powerlog.bridge"],
    shadows = [WorkoutPermissionsTest.PermissionAppContext::class],
)
class WorkoutPermissionsTest {
    @Before
    fun validWallClock() {
        setFixtureUtc()
    }

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
        private val module = PowerLogBridgeModule()
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
            engine = RecordingEngine.get(context, healthAccess = healthAccess, healthScheduler = { _, _, _ -> })
            engine.awaitReady()
            healthAccess.grants = engine.health.essentialPermissions()
            command {
                engine.handler.removeCallbacks(field("tick") as Runnable)
                ControllerBluetooth::class
                    .java
                    .getDeclaredField("state")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, mapOf("status" to "connected"))
            }
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

        fun invoke(name: String, vararg args: Any?): Reply {
            val reply = Reply()
            invokeFunction(definition, name, arrayOf(*args), reply)
            drain()
            return reply
        }

        @Suppress("UNCHECKED_CAST")
        fun call(name: String, gps: Boolean, drain: Boolean = true): CompletableFuture<Any?> {
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
            body(
                arrayOf(mapOf("indoor" to false, "saveToHealth" to true, "recordGPS" to gps, "sampleHz" to 2)),
                promise,
            )
            if (drain) drain()
            return result
        }

        fun drain() {
            engine.reads.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            repeat(6) {
                engine.healthTasks.submit {}.get(20, TimeUnit.SECONDS)
                command {}
            }
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
                if (id.isNotEmpty()) {
                    engine.action("discard", id)
                    repeat(15) {
                        if (engine.store.exists(id) && engine.store.healthJob(id) != null) engine.health.save(id)
                    }
                    RecordingEngine.healthCompleted()
                }
            }
            command { engine.handler.removeCallbacksAndMessages(null) }
            (field("thread") as HandlerThread).apply {
                quitSafely()
                join(20000)
            }
            engine.healthTasks.shutdownNow()
            engine.healthTasks.awaitTermination(20, TimeUnit.SECONDS)
            engine.reads.shutdownNow()
            engine.store.close()
            (field("previewStore") as RideStore).close()
            RecordingEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun healthOnStartWaitsForAnOrdinaryProviderRead() {
        PermissionModule().use { module ->
            module.healthAccess.grants +=
                androidx.health.connect.client.permission.HealthPermission.getReadPermission(
                    androidx.health.connect.client.records.PowerRecord::class
                )
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            module.healthAccess.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val reading = executor.submit {
                kotlinx.coroutines.runBlocking {
                    BoundedHealthConnectAccess(module.healthAccess)
                        .read(
                            androidx.health.connect.client.records.PowerRecord::class,
                            java.time.Instant.parse("2026-10-02T00:00:00Z"),
                            java.time.Instant.parse("2026-10-02T00:05:00Z"),
                            null,
                            false,
                        )
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val grantReads = module.healthAccess.grantReads
                val bounded =
                    HealthExport(module.context, module.engine.store, module.healthAccess, providerTimeoutMillis = 50)
                assertThrows(java.util.concurrent.TimeoutException::class.java) { bounded.granted() }
                assertEquals(grantReads, module.healthAccess.grantReads)
                val start = module.call("startWorkout", false, drain = false)
                assertThrows(java.util.concurrent.TimeoutException::class.java) {
                    start.get(200, TimeUnit.MILLISECONDS)
                }
                release.countDown()
                reading.get(5, TimeUnit.SECONDS)
                module.drain()
                val state = start.get(5, TimeUnit.SECONDS) as Map<*, *>
                assertEquals("running", state["phase"])
                assertTrue(module.engine.store.metadata(state["id"] as String).flag("saveToHealth"))
            } finally {
                release.countDown()
                reading.get(5, TimeUnit.SECONDS)
                module.healthAccess.beforeRead = {}
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun startWorkoutContinuesToEngineWithoutRequestingMissingOptionalHealthGrants() {
        PermissionModule().use { module ->
            module.healthAccess.beforeGranted = {
                val responsive = CompletableFuture<Boolean>()
                module.engine.handler.post { responsive.complete(true) }
                assertTrue(responsive.get(2, TimeUnit.SECONDS))
            }
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
                module.drain()
                val id = state["id"] as String
                repeat(15) {
                    if (module.engine.store.exists(id) && module.engine.store.healthJob(id) != null)
                        module.engine.health.save(id)
                }
                RecordingEngine.healthCompleted()
                module.drain()
                assertEquals(module.engine.health.essentialPermissions(), module.healthAccess.grants)
                module.permissionRequests.clear()
            }
            module.healthAccess.beforeGranted = {}
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
                val expected = module.engine.health.permissions(gps)
                val healthActivity =
                    Robolectric.buildActivity(HealthPermissionsActivity::class.java, request.intent).create()
                try {
                    val permissions = requireNotNull(shadowOf(healthActivity.get()).lastRequestedPermission)
                    assertEquals(expected, permissions.requestedPermissions.toSet())
                    assertFalse(
                        androidx.health.connect.client.permission.HealthPermission
                            .PERMISSION_READ_HEALTH_DATA_HISTORY in permissions.requestedPermissions
                    )
                } finally {
                    healthActivity.destroy()
                }
                module.healthAccess.grants = expected
                module.finishHealthRequest()
                val status = result.get(20, TimeUnit.SECONDS) as Map<*, *>
                val health = status["health"] as Map<*, *>
                val writes = health["writeAuthorization"] as Map<*, *>
                val reads = module.engine.health.status()["readAuthorization"] as Map<*, *>
                expected.forEach { assertEquals("authorized", (if (it.contains(".READ_")) reads else writes)[it]) }
                assertEquals(1, module.permissionRequests.size)
                assertEquals("idle", module.command { module.engine.state()["phase"] })
                module.permissionRequests.clear()
            }
        }
    }

    @Test
    fun invalidOptionsRejectBeforeAccessingThePermissionContextOrStarting() {
        val module = PowerLogBridgeModule()
        val request =
            PowerLogBridgeModule::class
                .java
                .getDeclaredMethod(
                    "ridePermissions",
                    Map::class.java,
                    Promise::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Function1::class.java,
                )
                .apply { isAccessible = true }
        val invalid =
            rejectedRideOptions() +
                listOf("useWatch", "saveToHealth", "recordGPS").flatMap { field ->
                    listOf(0, "false", emptyList<Any>(), emptyMap<String, Any>()).map {
                        field to mapOf("indoor" to false, "sampleHz" to 2, field to it)
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
                        assertEquals(field, RideFailure.startInterrupted.name, code)
                        assertEquals(field, RideFailure.startInterrupted.text, message)
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            request.invoke(
                module,
                input,
                promise,
                "start",
                true,
                { _: RideOptions -> fail("Permission continuation ran") },
            )
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedConnectRejectsBeforeAccessingTheEngineOrBluetoothContext() {
        val module = PowerLogBridgeModule()
        val connect =
            PowerLogBridgeModule::class.java.getDeclaredMethod("connect", Map::class.java, Promise::class.java).apply {
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
                        assertEquals(field, RideFailure.bikeUnavailable.name, code)
                        assertEquals(field, RideFailure.bikeUnavailable.text, message)
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            connect.invoke(module, input, promise)
            assertTrue(rejected)
        }
    }

    @Test
    fun malformedCatalogAndMonitorRequestsRejectBeforeOpeningTheStore() {
        val module = PowerLogBridgeModule()
        val read =
            PowerLogBridgeModule::class
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
                        assertEquals(RideFailure.unknown.name, code)
                        assertEquals(RideFailure.unknown.text, message)
                        assertTrue(cause is IllegalArgumentException)
                    }
                }
            read.invoke(module, input, promise, parse, { _: Any -> fail("Read continuation ran") })
            assertTrue(rejected)
        }
    }

    @Test
    fun functionBodiesRejectEngineAccessFailuresWithSharedFailures() {
        val definition = PowerLogBridgeModule().definition()
        val cases =
            listOf(
                Triple("getWorkoutState", arrayOf<Any?>(), RideFailure.unknown),
                Triple(
                    "startWorkout",
                    arrayOf<Any?>(
                        mapOf("indoor" to false, "saveToHealth" to false, "recordGPS" to false, "sampleHz" to 2)
                    ),
                    RideFailure.startInterrupted,
                ),
                Triple("connect", arrayOf<Any?>(mapOf("deviceId" to "bike", "hz" to 4)), RideFailure.bikeUnavailable),
                Triple("listWorkouts", arrayOf<Any?>(emptyMap<String, Any?>()), RideFailure.unknown),
                Triple("readWorkout", arrayOf<Any?>("ride", null, null), RideFailure.unknown),
                Triple("cancelWorkoutRead", arrayOf<Any?>("read"), RideFailure.unknown),
                Triple(
                    "describeMonitorSource",
                    arrayOf<Any?>(mapOf("source" to "live", "generation" to 0)),
                    RideFailure.unknown,
                ),
                Triple("cancelMonitorRead", arrayOf<Any?>("read"), RideFailure.unknown),
                Triple("exportOpen", arrayOf<Any?>(emptyMap<String, Any?>()), RideFailure.unknown),
                Triple("sinkOpen", arrayOf<Any?>("fit", emptyMap<String, Any?>()), RideFailure.unknown),
                Triple("shareFile", arrayOf<Any?>("content://app.powerlog/export.fit"), RideFailure.unknown),
            )
        for ((name, args, failure) in cases) {
            val reply = Reply()
            invokeFunction(definition, name, args, reply)
            assertTrue(name, reply.settled)
            val rejection = assertThrows(name, Rejection::class.java) { reply.get() }
            assertEquals(name, failure.name, rejection.code)
            assertEquals(name, failure.text, rejection.message)
        }
    }

    @Test
    fun nativeFailuresReachJavaScriptOnlyAsSharedCodesAndText() {
        PermissionModule().use { module ->
            val missing = java.util.UUID.randomUUID().toString()
            val cases =
                listOf(
                    Triple("stopWorkout", arrayOf<Any?>(null), RideFailure.unknown),
                    Triple("deleteWorkout", arrayOf<Any?>(missing), RideFailure.rideDeleted),
                    Triple("readWorkout", arrayOf<Any?>(missing, null, null), RideFailure.rideDeleted),
                )
            for ((name, args, failure) in cases) {
                val rejection = assertThrows(name, Rejection::class.java) { module.invoke(name, *args).get() }
                assertEquals(name, failure.name, rejection.code)
                assertEquals(name, failure.text, rejection.message)
            }
        }
    }
}
