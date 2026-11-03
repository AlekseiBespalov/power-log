package app.powerlog.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPausedSystemClock
import org.robolectric.shadows.ShadowSystemClock

internal fun setFixtureUtc() {
    ShadowPausedSystemClock::class
        .java
        .getDeclaredField("currentUptimeNs")
        .apply { isAccessible = true }
        .setLong(null, 1767225600000000000L)
}

@Implements(NotificationManager::class)
class RecordingNotificationManager : ShadowNotificationManager() {
    val publications = mutableListOf<Pair<Long, Notification>>()

    @Implementation
    override fun notify(id: Int, notification: Notification) {
        publications.add(SystemClock.uptimeMillis() to notification)
        super.notify(id, notification)
    }
}

@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class RecordingContextImpl : ShadowContextImpl() {
    companion object {
        var beforeForegroundStart: (() -> Unit)? = null
    }

    @Implementation
    override fun startForegroundService(intent: Intent): ComponentName? {
        beforeForegroundStart?.invoke()
        return super.startForegroundService(intent)
    }
}

@Implements(value = expo.modules.kotlin.AppContext::class, isInAndroidSdk = false)
class RecordingAppContext {
    @Implementation fun getCurrentActivity(): android.app.Activity? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    instrumentedPackages = ["app.powerlog.bridge"],
    shadows = [RecordingNotificationManager::class, RecordingContextImpl::class, RecordingAppContext::class],
)
abstract class RecordingEngineTestBase {
    internal lateinit var engine: RecordingEngine
    internal var service: ServiceController<RecordingService>? = null
    internal val healthAccess = FakeHealthConnectAccess().apply { available = false }
    internal var scheduledHealth: (String, Long?) -> Unit = { _, _ -> }
    internal var readClock: () -> RecordingClockReading = SystemRecordingClock::read

    internal fun field(name: String): Any? =
        RecordingEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    internal fun <T> command(body: () -> T): T {
        val result = CompletableFuture<T>()
        engine.handler.post {
            try {
                result.complete(body())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!result.isDone && System.nanoTime() < deadline) Thread.sleep(1)
        return result.get(1, TimeUnit.SECONDS)
    }

    internal fun awaitHealthWork() {
        repeat(6) {
            engine.healthTasks.submit {}.get(20, TimeUnit.SECONDS)
            command {}
        }
    }

    @Before
    fun setup() {
        RecordingContextImpl.beforeForegroundStart = null
        setFixtureUtc()
        ShadowPausedSystemClock::class
            .java
            .getDeclaredField("currentRealtimeNs")
            .apply { isAccessible = true }
            .setLong(null, 1000000000L)
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val locations = context.getSystemService(LocationManager::class.java)
        shadowOf(locations).setLocationEnabled(true)
        shadowOf(locations).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        engine =
            RecordingEngine.get(
                context,
                RecordingClock { readClock() },
                healthAccess,
                healthScheduler = { id, resume, _ -> scheduledHealth(id, resume) },
            )
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            setBikeStatus("connected")
        }
    }

    internal fun setBikeStatus(status: String) {
        ControllerBluetooth::class
            .java
            .getDeclaredField("state")
            .apply { isAccessible = true }
            .set(engine.bluetooth, mapOf("status" to status))
    }

    internal fun shutdown() {
        service?.destroy()
        service = null
        if ((field("thread") as HandlerThread).isAlive) command { engine.handler.removeCallbacksAndMessages(null) }
        (field("thread") as HandlerThread).apply {
            quitSafely()
            join(20000)
        }
        engine.healthTasks.shutdownNow()
        engine.healthTasks.awaitTermination(20, TimeUnit.SECONDS)
        engine.storageTasks.shutdown()
        engine.storageTasks.awaitTermination(20, TimeUnit.SECONDS)
        engine.reads.shutdownNow()
        engine.store.close()
        (field("previewStore") as RideStore).close()
        RecordingEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @After
    fun close() {
        RecordingContextImpl.beforeForegroundStart = null
        service?.destroy()
        service = null
        scheduledHealth = { _, _ -> }
        try {
            if ((field("thread") as HandlerThread).isAlive)
                command {
                    val id = engine.state().str("id")
                    if (
                        id.isNotEmpty() &&
                            engine.state().str("phase") in listOf("running", "paused", "interrupted", "finishing")
                    )
                        engine.action("discard", id)
                }
        } finally {
            shutdown()
        }
    }

    internal fun start(
        gps: Boolean = false,
        saveToHealth: Boolean = false,
        sampleHz: Int = 4,
        distanceSource: String = "auto",
    ): String {
        if (saveToHealth) healthAccess.available = true
        return engine
            .start(
                BridgeInputs.ride(
                    mapOf(
                        "indoor" to false,
                        "saveToHealth" to saveToHealth,
                        "recordGPS" to gps,
                        "sampleHz" to sampleHz,
                        "distanceSource" to distanceSource,
                    )
                ),
                healthAccess.grants,
            )
            .str("id")
    }

    internal fun openNotificationService(startCommand: Boolean = true) {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context.packageName, "${context.packageName}.MainActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager)
            .addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        service = Robolectric.buildService(RecordingService::class.java).create()
        if (startCommand) {
            service!!.startCommand(0, 1)
            idleNotifications()
        }
    }

    internal fun idleNotifications() = shadowOf(Looper.getMainLooper()).idle()

    internal fun postedNotification(): Notification =
        requireNotNull(
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))
                .getNotification(4101)
        )

    internal fun notificationPublications() =
        (shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))
                as RecordingNotificationManager)
            .publications

    internal fun observeHeartRate(id: String) {
        val now = SystemRecordingClock.read()
        val clock =
            PresentationClock(
                field("captureClockEpoch") as String,
                now.monotonicMillis * 1000,
                timeUs(now.timestamp),
            )
        val live =
            LivePresentation.observe(
                field("livePresentation") as LivePresentationState,
                id,
                LiveSource.heartRate,
                LiveReading(
                    LiveAcquisition("heart", null, clock.utcUs, clock.epoch, clock.monotonicUs),
                    mapOf("heartRateBpm" to 150.0),
                ),
                clock,
            )
        RecordingEngine::class.java.getDeclaredField("livePresentation").apply { isAccessible = true }.set(engine, live)
        engine.flush()
        RecordingEngine::class.java.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(engine)
    }

    internal fun tick() {
        val tick = field("tick") as Runnable
        tick.run()
        engine.handler.removeCallbacks(tick)
    }

    internal fun failCapture() {
        RecordingEngine::class
            .java
            .getDeclaredMethod("captureFailed", Exception::class.java)
            .apply { isAccessible = true }
            .invoke(engine, SQLiteException("injected capture failure"))
    }

    internal fun <T> commandWithMain(body: () -> T): T {
        val result = CompletableFuture<T>()
        engine.handler.post {
            try {
                result.complete(body())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!result.isDone && System.nanoTime() < deadline) {
            idleNotifications()
            Thread.sleep(1)
        }
        idleNotifications()
        return result.get(1, TimeUnit.SECONDS)
    }

    internal fun failureCode(state: Payload) = (state["failure"] as? Map<*, *>)?.get("code")

    internal fun assertFailure(expected: RideFailure, state: Payload) {
        assertEquals(mapOf("code" to expected.name, "message" to expected.text), state["failure"])
        assertEquals(expected.text, state["error"])
    }

    internal fun assertWireFixture(name: String, wire: Payload) {
        val fixture = File(contractFixture("completion/health-outcomes.json").parentFile, "android-live.json")
        val normalized =
            wire +
                mapOf(
                    "id" to "11111111-1111-4111-8111-111111111111",
                    "controlToken" to "22222222-2222-4222-8222-222222222222",
                    "readWorkoutIds" to
                        (wire["readWorkoutIds"] as List<*>).map {
                            if (it == wire["id"]) "11111111-1111-4111-8111-111111111111" else it
                        },
                )
        val cases = if (fixture.exists()) JSONObject(fixture.readText()) else JSONObject()
        if (System.getenv("POWER_LOG_UPDATE_ANDROID_WIRE_FIXTURES") == "1") {
            cases.put(name, JSONObject(normalized))
            fixture.writeText(cases.toString(2) + "\n")
        }
        assertEquals(cases.getJSONObject(name).toString(), JSONObject(normalized).toString())
    }

    internal fun healthWorker(id: String) =
        androidx.work.testing.TestWorkerBuilder.from(
                RuntimeEnvironment.getApplication(),
                EngineStoreWorker::class.java,
                java.util.concurrent.Executor { it.run() },
            )
            .setInputData(androidx.work.workDataOf("ride" to id))
            .build()

    internal fun failureSnapshotCase(name: String): Map<*, *> =
        ((JSONObject(contractFixture("presentation/failures.json").readText()).map()["bridge"] as Map<*, *>)["snapshot"]
                as Map<*, *>)
            .let { it["cases"] as List<*> }
            .map { it as Map<*, *> }
            .single { it["platform"] == "android" && it["name"] == name }

    internal fun assertNoFailedStart() {
        assertNull(engine.store.lifecycleState().ride)
        assertTrue(engine.store.list(CatalogInput()).isEmpty())
        for (query in
            listOf(
                "SELECT count(*) FROM rides WHERE id NOT LIKE 'live-%'",
                "SELECT count(*) FROM lifecycle_states",
                "SELECT count(*) FROM lifecycle_intents",
                "SELECT count(*) FROM publication_jobs",
            )) engine.store.writableDatabase.rawQuery(query, null).use {
            assertTrue(it.moveToFirst())
            assertEquals(query, 0L, it.getLong(0))
        }
        val state = engine.state()
        assertEquals("idle", state["phase"])
        assertNull(state["id"])
        assertNull(state["pendingAction"])
        assertEquals(false, state["startBusy"])
    }

    internal fun advanceTo(seconds: Long) {
        val target = (field("startClock") as Long) + seconds * 1000
        ShadowSystemClock.advanceBy(Duration.ofMillis(target - SystemClock.elapsedRealtime()))
    }

    internal fun location(
        seconds: Double,
        accuracy: Float = 5f,
        speed: Float? = null,
        courseAccuracy: Float? = null,
    ) {
        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0
                longitude = seconds * 0.00001
                this.accuracy = accuracy
                if (speed != null) this.speed = speed
                if (courseAccuracy != null) {
                    bearing = 90f
                    bearingAccuracyDegrees = courseAccuracy
                }
                time =
                    java.time.Instant.parse(readClock().timestamp).toEpochMilli() -
                        (SystemClock.elapsedRealtime() - (field("startClock") as Long) - (seconds * 1000).toLong())
                elapsedRealtimeNanos = ((field("startClock") as Long) * 1_000_000) + (seconds * 1e9).toLong()
            }
        (field("locationListener") as LocationListener).onLocationChanged(fix)
    }

    internal fun <T> withUtc(millis: Long, body: () -> T): T {
        // Robolectric's UTC follows uptime; elapsedRealtime has a separate backing clock.
        val clock =
            ShadowPausedSystemClock::class.java.getDeclaredField("currentUptimeNs").apply {
                isAccessible = true
            }
        val previous = clock.getLong(null)
        clock.setLong(null, millis * 1_000_000)
        try {
            assertEquals(iso(millis), iso())
            return body()
        } finally {
            clock.setLong(null, previous)
        }
    }

    internal fun telemetry(
        speed: Double? = null,
        epoch: String = "40000000-0000-4000-8000-000000000001",
        acquiredAt: Long = SystemClock.elapsedRealtime(),
        power: Double? = 120.0,
    ) {
        RecordingEngine::class
            .java
            .getDeclaredMethod(
                "captureTelemetry",
                TelemetryReading::class.java,
                String::class.java,
                RecordingClockReading::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                engine,
                TelemetryReading(
                    (power?.let { mapOf(TelemetryMetric.humanPowerW to it) }
                        ?: mapOf(TelemetryMetric.cadenceRpm to 80.0)) +
                        (speed?.let { mapOf(TelemetryMetric.controllerSpeedMps to it) } ?: emptyMap()),
                    CycProtocol.restoreIdentity("X6", "20250725", "5.3"),
                ),
                epoch,
                RecordingClockReading(iso(), acquiredAt),
            )
    }

    internal fun <T> withStartDelay(beforeOwnerRecord: Boolean = false, duringCreate: () -> Unit, body: () -> T): T {
        val db = engine.store.writableDatabase
        var delays = 0
        db.setCustomScalarFunction("delay_start") { value ->
            delays++
            assertTrue(db.inTransaction())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            duringCreate()
            value
        }
        db.execSQL(
            if (beforeOwnerRecord)
                "CREATE TEMP TRIGGER delay_start BEFORE INSERT ON rides WHEN NEW.id NOT LIKE 'live-%' BEGIN SELECT delay_start(NEW.id); END"
            else
                "CREATE TEMP TRIGGER delay_start BEFORE INSERT ON lifecycle WHEN NEW.action='start' BEGIN SELECT delay_start(NEW.ride); END"
        )
        try {
            return body().also { assertEquals(1, delays) }
        } finally {
            db.execSQL("DROP TRIGGER delay_start")
        }
    }

    internal fun stream(name: String): Map<*, *> = (engine.state()["streams"] as Map<*, *>)[name] as Map<*, *>

    internal fun recordStore(id: String): RideStore =
        if (id.startsWith("live-")) field("previewStore") as RideStore else engine.store

    internal fun monitorQuery(id: String, request: MonitorInput): Payload =
        (if (id.startsWith("live-")) field("previewMonitor") as RideMonitor else engine.monitor).query(id, request)

    internal fun times(id: String) =
        recordStore(id)
            .readableDatabase
            .rawQuery("SELECT time FROM observations WHERE ride=? ORDER BY id", arrayOf(id))
            .use {
                buildList { while (it.moveToNext()) add(it.getDouble(0)) }
            }
}

class EngineStoreWorker(context: android.content.Context, parameters: androidx.work.WorkerParameters) :
    HealthPublicationWorker(context, parameters) {
    internal override fun publisher(store: RideStore): HealthExport {
        stores.add(store)
        hold(store)
        return HealthExport(applicationContext, store, FakeHealthConnectAccess().apply { available = false })
    }

    internal override fun continueAt(id: String, next: Long?) {}

    companion object {
        internal val stores = java.util.concurrent.CopyOnWriteArrayList<RideStore>()
        internal var hold: (RideStore) -> Unit = {}
    }
}
