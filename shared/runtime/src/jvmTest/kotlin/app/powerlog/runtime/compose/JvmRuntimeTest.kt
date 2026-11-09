package app.powerlog.runtime.compose

import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.fakes.FakeBleCentral
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PermissionGate
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ride.Platform
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JvmRuntimeTest {
    @Test
    fun aRuntimeKeepsItsStoresInItsDirectory() {
        val directory = Files.createTempDirectory("jvm-runtime").toFile()
        val threads = List(3) { java.util.concurrent.Executors.newSingleThreadExecutor() }
        try {
            val (ride, health, maintenance) = threads.map { it.asCoroutineDispatcher() }
            val executors =
                app.powerlog.application.Executors(ride, health, maintenance, Dispatchers.IO.limitedParallelism(4))
            val ports = ports(FakePermissionGate({ it() }), FakeFilePicker({ it() }))
            val runtime = JvmRuntime.create(directory, executors, FakeClock(), ports)
            val launched = CompletableFuture<RefusalDetail?>()
            runtime.host.launch(LaunchContext(LaunchReason.userInterface)) { launched.complete(it) }
            assertNull(launched.get(10, TimeUnit.SECONDS))

            val session = runtime.openBridgeSession()
            val changed = reply { session.call("settings.change", """{"kind":"sampleRate","value":"8"}""", it) }
            assertEquals("true", changed.getValue("ok").jsonPrimitive.content, changed.toString())
            val state = reply { session.next("settings.state", """{"params":{},"after":null,"key":"s"}""", it) }
            val saved = state.getValue("value").jsonObject.getValue("ride").jsonObject
            assertEquals("8", saved.getValue("sampleRate").jsonPrimitive.content, state.toString())
            assertTrue(
                directory.listFiles().orEmpty().any { it.isFile && it.length() > 0 },
                directory.list()?.toList().toString(),
            )
            session.close()
        } finally {
            threads.forEach { it.shutdownNow() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun prepareBluetoothPreparesTheComposedCentral() {
        val directory = Files.createTempDirectory("jvm-runtime").toFile()
        val ride = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val dispatcher = ride.asCoroutineDispatcher()
            val executors =
                app.powerlog.application.Executors(
                    dispatcher,
                    dispatcher,
                    dispatcher,
                    Dispatchers.IO.limitedParallelism(4),
                )
            val central = FakeBleCentral(Platform.ios, FakeClock()) { it() }
            val runtime =
                JvmRuntime.create(
                    directory,
                    executors,
                    FakeClock(),
                    ports(FakePermissionGate({ it() }), FakeFilePicker({ it() }), central),
                )
            assertTrue("prepare" !in central.osCalls)

            runtime.prepareBluetooth()

            assertEquals(listOf("prepare"), central.osCalls)
        } finally {
            ride.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun ports(permissions: PermissionGate, picker: FilePicker, ble: BleCentral? = null): Ports =
        Ports(
            permissions = permissions,
            picker = picker,
            device = FakeDeviceInfo(FakeDeviceInfo.snapshot(Platform.android)),
            ble = ble,
            location = null,
            health = null,
            keepAlive = null,
            tabLock = null,
            persistence = null,
            rideCard = null,
            watch = null,
            scheduler = null,
        )

    private fun reply(send: ((String) -> Unit) -> Unit): JsonObject {
        val reply = CompletableFuture<String>()
        send { reply.complete(it) }
        return Json.parseToJsonElement(reply.get(10, TimeUnit.SECONDS)).jsonObject
    }
}
