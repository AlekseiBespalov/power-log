package app.powerlog.bridge

import android.Manifest
import android.content.Intent
import android.os.HandlerThread
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingOwnerFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val access = FakeHealthConnectAccess()
    private var at = 0L
    private lateinit var engine: RecordingEngine

    private fun field(name: String): Any? =
        RecordingEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    private fun <T> command(body: () -> T): T {
        val result = CompletableFuture<T>()
        engine.handler.post {
            try {
                result.complete(body())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(30, TimeUnit.SECONDS)
    }

    private fun awaitHealthWork() {
        repeat(6) {
            engine.healthTasks.submit {}.get(30, TimeUnit.SECONDS)
            command {}
        }
    }

    private fun open(fresh: Boolean = false) {
        if (fresh) {
            check(context.packageName == "app.powerlog.bridge.test")
            context.deleteDatabase("power-log.sqlite")
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        for (permission in
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        engine =
            RecordingEngine.get(
                context,
                RecordingClock {
                    RecordingClockReading(iso(1767225600000L + at), at)
                },
                access,
                healthScheduler = { _, _, _ -> },
            )
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            ControllerBluetooth::class
                .java
                .getDeclaredField("state")
                .apply { isAccessible = true }
                .set(engine.bluetooth, mapOf("status" to "connected"))
        }
        awaitHealthWork()
    }

    private fun close() {
        awaitHealthWork()
        command {
            engine.handler.removeCallbacksAndMessages(null)
            (field("wake") as? PowerManager.WakeLock)?.let { if (it.isHeld) it.release() }
        }
        context.stopService(Intent(context, RecordingService::class.java))
        instrumentation.waitForIdleSync()
        (field("thread") as HandlerThread).apply {
            quitSafely()
            join(30000)
        }
        engine.healthTasks.shutdownNow()
        engine.healthTasks.awaitTermination(30, TimeUnit.SECONDS)
        engine.reads.shutdownNow()
        (field("previewStore") as RideStore).close()
        engine.store.close()
        RecordingEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    private fun discard(id: String) {
        command { engine.action("discard", id) }
        awaitHealthWork()
        repeat(15) { if (engine.store.exists(id) && engine.store.healthJob(id) != null) engine.health.save(id) }
        RecordingEngine.healthCompleted()
        awaitHealthWork()
        command { assertFalse(engine.store.exists(id)) }
    }

    @Test
    fun localDiscardLeavesTheHandlerAvailableWhileHealthWaits() {
        at = 0
        open(fresh = true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val write = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val id = command {
                engine.start(RideOptions(true, saveToHealth = false, recordGPS = false), access.grants).str("id")
            }
            val other = command {
                engine.store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading(iso(1767225600000L), 0),
                )
            }
            val worker = executor.submit {
                val store = RideStore.shared(context)
                RideStore.withHealthExecution {
                    entered.countDown()
                    check(write.await(10, TimeUnit.SECONDS))
                    store.update(other, "completed", RideTiming(0.0, 0.0, iso(1767225600000L)), emptyMap())
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val discarded = CompletableFuture<Unit>()
            engine.handler.post {
                try {
                    engine.action("discard", id)
                    discarded.complete(Unit)
                } catch (error: Throwable) {
                    discarded.completeExceptionally(error)
                }
            }
            discarded.get(2, TimeUnit.SECONDS)
            val responsive = CompletableFuture<Boolean>()
            engine.handler.post { responsive.complete(true) }
            assertTrue(responsive.get(2, TimeUnit.SECONDS))
            write.countDown()
            worker.get(5, TimeUnit.SECONDS)
            discarded.get(5, TimeUnit.SECONDS)
            command {
                assertFalse(engine.store.exists(id))
                assertEquals("completed", engine.store.metadata(other)["phase"])
            }
        } finally {
            write.countDown()
            executor.shutdownNow()
            close()
        }
    }

    @Test
    fun lifecycleActionFixtureExecutesThroughTheNativeOwner() {
        val fixture =
            JSONObject(instrumentation.context.assets.open("lifecycle/F10.json").bufferedReader().use { it.readText() })
        at = 0
        open(fresh = true)
        try {
            command {
                val id =
                    engine.start(RideOptions(false, saveToHealth = false, recordGPS = false), access.grants).str("id")
                var lap: String? = null
                val rows = fixture.getJSONArray("actions")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    at = row.getLong("at") * 1000
                    val action = row.getString("action")
                    val commandId =
                        if (row.optBoolean("duplicate")) checkNotNull(lap) else java.util.UUID.randomUUID().toString()
                    if (action == "lap") lap = commandId
                    if (row.getBoolean("admitted")) engine.action(action, id, commandId)
                    else assertThrows(IllegalStateException::class.java) { engine.action(action, id, commandId) }
                    assertEquals(row.getString("phase"), engine.state()["phase"])
                    assertEquals(row.getInt("events"), engine.store.events(id).size)
                    assertEquals(row.getDouble("timer"), engine.state().num("timerSeconds"), 0.0)
                }
                assertEquals(fixture.getDouble("elapsed"), engine.store.timing(id).elapsed, 0.0)
                engine.delete(id)
            }
        } finally {
            close()
        }
    }

    @Test
    fun localFinishKeepsItsCutoffAcrossReopenWhenHealthHasNotRun() {
        val fixture =
            JSONObject(instrumentation.context.assets.open("lifecycle/F11.json").bufferedReader().use { it.readText() })
        at = 0
        open(fresh = true)
        try {
            val id = command {
                val id =
                    engine.start(RideOptions(false, saveToHealth = true, recordGPS = false), access.grants).str("id")
                at = 8000
                engine.action("pause", id)
                at = fixture.getLong("stopAt") * 1000
                engine.action("resume", id)
                engine.action("stop", id)
                assertEquals(1, engine.store.events(id).count { it.action == "stop" })
                id
            }
            val cutoff = command { engine.store.metadata(id)["endedAt"] }
            assertEquals(fixture.getString("expectedCutoff"), cutoff)
            close()
            at = 100000
            open()
            command {
                assertEquals(cutoff, engine.store.metadata(id)["endedAt"])
                assertEquals(1, engine.store.events(id).count { it.action == "stop" })
                assertEquals(fixture.getDouble("timer"), engine.store.timing(id).timer, 0.0)
                repeat(15) { engine.health.save(id) }
                assertEquals("saved", engine.store.healthJob(id)?.status)
            }
            close()
            open()
            command {
                assertEquals(cutoff, engine.store.metadata(id)["endedAt"])
                assertTrue(app.powerlog.core.completion.RideCompletion.exportReady(engine.store.completion(id), id))
                assertEquals(1, engine.store.events(id).count { it.action == "stop" })
                engine.delete(id)
            }
        } finally {
            close()
        }
    }
}
