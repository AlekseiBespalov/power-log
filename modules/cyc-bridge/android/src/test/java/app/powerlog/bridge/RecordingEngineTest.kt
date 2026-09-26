package app.powerlog.bridge

import android.Manifest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RecordingEngineTest {
    @Test
    fun nativeOwnerSurvivesMissingUIListenersAndRejectsStaleCommands() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        val engine = RecordingEngine.get(context)
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
        val first = command {
            engine.start(mapOf("saveToHealth" to false, "recordGPS" to false, "useWatch" to false))
        }
            .str("id")
        command {
            engine.listeners.clear()
            engine.listeners.add { _, _ -> error("UI detached") }
            assertEquals("paused", engine.action("pause", first)["phase"])
            assertEquals("running", engine.action("resume", first)["phase"])
            engine.action("lap", first)
            assertThrows(IllegalArgumentException::class.java) { engine.action("stop", "old-ride") }
            assertThrows(IllegalStateException::class.java) { engine.delete(first) }
            engine.checkBike("synthetic-bike")
            assertThrows(IllegalStateException::class.java) { engine.checkBike("different-bike") }
            assertEquals("idle", engine.action("stop", first)["phase"])
            assertEquals("completed", engine.store.metadata(first)["phase"])
            assertEquals("notRequested", engine.store.metadata(first)["healthKitState"])
            val second =
                engine
                    .start(
                        mapOf("saveToHealth" to false, "recordGPS" to false, "useWatch" to false)
                    )
                    .str("id")
            engine.action("discard", second)
            assertEquals(1, engine.store.list(emptyMap()).size)
            assertEquals(first, engine.store.list(emptyMap()).first()["id"])
            engine.delete(first)
            assertTrue(engine.store.list(emptyMap()).isEmpty())
        }
    }
}
