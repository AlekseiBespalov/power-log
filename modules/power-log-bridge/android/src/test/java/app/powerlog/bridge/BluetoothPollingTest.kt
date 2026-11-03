package app.powerlog.bridge

import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.powerlog.core.presentation.RideFailure
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BluetoothPollingTest {
    private val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun field(bluetooth: ControllerBluetooth, name: String, value: Any?) {
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)
    }

    private fun field(bluetooth: ControllerBluetooth, name: String): Any? =
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bluetooth)

    private fun receive(bluetooth: ControllerBluetooth, frame: ByteArray) = receiveAdapter(bluetooth, frame)

    @Test
    fun malformedConnectCannotChangePollingOrChooseADevice() {
        val handler = Handler(Looper.getMainLooper())
        val bluetooth =
            ControllerBluetooth(
                RuntimeEnvironment.getApplication(),
                handler,
                { _, _ -> },
                { _, _, _ -> },
                { false },
            )
        bluetooth.setHz(4)
        try {
            for ((field, input) in rejectedRideOptions().filter { it.first == "sampleHz" }) {
                assertEquals("sampleHz", field)
                assertThrows(IllegalArgumentException::class.java) {
                    val parsed =
                        BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to input["sampleHz"]))
                    bluetooth.connect(parsed.deviceId, parsed.sampleHz) { fail("Bluetooth completion ran") }
                }
                assertEquals(4, bluetooth.hz)
                assertNull(bluetooth.deviceId)
            }
        } finally {
            bluetooth.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun coalescedIdentityCannotAttributePreexistingTelemetryToTheFirstPoll() {
        val context = RuntimeEnvironment.getApplication()
        val handler = Handler(Looper.getMainLooper())
        var samples = 0
        val bluetooth = ControllerBluetooth(context, handler, { _, _ -> }, { _, _, _ -> samples++ }, { true })
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:01")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        shadowOf(gatt).setGattCallback(object : BluetoothGattCallback() {})
        val writer =
            BluetoothGattCharacteristic(
                UUID.fromString(ControllerGatt.WRITE),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        BluetoothGattService(UUID.fromString(ControllerGatt.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .addCharacteristic(writer)
        field(bluetooth, "gatt", gatt)
        field(bluetooth, "writer", writer)
        stageIdentity(bluetooth)
        setAdapterRequest(bluetooth, ControllerRequest.identity, SystemClock.elapsedRealtime() / 1000.0)
        val payload = bytes(fixture.getJSONObject("identity").getString("payloadHex"))
        val crc = CycProtocol.crc(payload)
        val identity =
            byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
        val telemetry = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        try {
            receive(bluetooth, identity + telemetry)
            assertEquals(0, samples)
            assertArrayEquals(
                adapterSession(bluetooth).request(ControllerRequest.telemetry),
                shadowOf(gatt).latestWrittenBytes,
            )
            receive(bluetooth, telemetry)
            assertEquals(1, samples)
        } finally {
            bluetooth.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun aBackgroundPreviewPollsAtOneHertzWhileARideKeepsItsRate() {
        val handler = Handler(Looper.getMainLooper())
        var recording = false
        fun pollDelay(bluetooth: ControllerBluetooth) =
            ControllerBluetooth::class
                .java
                .getDeclaredField("nextPollAt")
                .apply { isAccessible = true }
                .getLong(bluetooth) - SystemClock.elapsedRealtime()
        ControllerAdapterFixture(handler, recording = { recording }).use { adapter ->
            adapter.bluetooth.setBackground(true)
            adapter.identity()
            assertEquals(1000L, pollDelay(adapter.bluetooth))
            adapter.bluetooth.setBackground(false)
            assertEquals(250L, pollDelay(adapter.bluetooth))
            recording = true
            adapter.bluetooth.setBackground(true)
            assertEquals(250L, pollDelay(adapter.bluetooth))
        }
    }

    @Test
    fun repliesQueuedBehindCaptureKeepTheirOriginalRequestOwnership() {
        val handler = Handler(Looper.getMainLooper())
        val readings = mutableListOf<TelemetryReading>()
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        ControllerAdapterFixture(handler, readings::add).use { adapter ->
            adapter.identity()
            handler.post {
                adapter.queueNotification(frame)
                adapter.queueNotification(frame)
                ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            }
            shadowOf(handler.looper).idle()
            assertEquals("connected", adapter.bluetooth.state["status"])
            assertEquals(1, readings.size)
            shadowOf(handler.looper).idleFor(Duration.ofMillis(20))
            adapter.notification(frame)
            assertEquals("connected", adapter.bluetooth.state["status"])
            assertEquals(2, readings.size)
        }
    }

    @Test
    fun responsesPastTheirDeadlineAreRejectedBeforeTheWatchdogRuns() {
        val identity = CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val handler = Handler(Looper.getMainLooper())
        for (age in listOf(2500L, 2501L)) {
            var samples = 0
            val bluetooth =
                ControllerBluetooth(
                    RuntimeEnvironment.getApplication(),
                    handler,
                    { _, _ -> },
                    { _, _, _ -> samples++ },
                    { true },
                )
            field(bluetooth, "desired", "02:00:00:00:00:01")
            readyAdapter(bluetooth, identity)
            setAdapterRequest(bluetooth, ControllerRequest.telemetry, (SystemClock.elapsedRealtime() - age) / 1000.0)
            receive(bluetooth, frame)
            assertEquals(if (age == 2500L) 1 else 0, samples)
            assertEquals(if (age == 2500L) "connected" else "reconnecting", bluetooth.state["status"])
            if (age > 2500L) assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
            bluetooth.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun explicitReconnectKeepsTheRateAdmittedForTheRide() {
        val handler = Handler(Looper.getMainLooper())
        var recording = true
        val bluetooth =
            ControllerBluetooth(
                RuntimeEnvironment.getApplication(),
                handler,
                { _, _ -> },
                { _, _, _ -> },
                { recording },
            )
        val id = "02:00:00:00:00:01"
        bluetooth.setHz(4)
        field(bluetooth, "desired", id)
        field(bluetooth, "state", mapOf("status" to "connected"))
        assertThrows(IllegalStateException::class.java) { bluetooth.connect(id, 8) { fail("Unexpected admission") } }
        assertEquals(4, bluetooth.hz)
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(BluetoothManager::class.java).adapter)
            .setState(BluetoothAdapter.STATE_ON)
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        bluetooth.connect(id, 2) {}
        assertEquals(4, bluetooth.hz)
        recording = false
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        bluetooth.connect(id, 8) {}
        assertEquals(8, bluetooth.hz)
        bluetooth.disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    @Test
    fun captureWorkDoesNotAddAnExtraDelayToEveryPoll() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(BluetoothManager::class.java).adapter)
            .setState(BluetoothAdapter.STATE_ON)
        val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val identity = CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val handler = Handler(Looper.getMainLooper())
        for (hz in listOf(2, 4, 8)) for (storageMillis in listOf(0L, 80L, 600L)) {
            val delivered = mutableListOf<TelemetryReading>()
            val bluetooth =
                ControllerBluetooth(
                    RuntimeEnvironment.getApplication(),
                    handler,
                    { _, _ -> },
                    { reading, _, _ ->
                        delivered.add(reading)
                        ShadowSystemClock.advanceBy(Duration.ofMillis(storageMillis))
                    },
                    { true },
                )
            bluetooth.setHz(hz)
            val sentAt = SystemClock.elapsedRealtime()
            fun field(name: String, value: Any) {
                ControllerBluetooth::class
                    .java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(bluetooth, value)
            }
            field("desired", "02:00:00:00:00:01")
            field("nextPollAt", sentAt + 1000L / hz)
            readyAdapter(bluetooth, identity)
            setAdapterRequest(bluetooth, ControllerRequest.telemetry, sentAt / 1000.0)
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            receive(bluetooth, frame)
            assertEquals(1, delivered.size)
            assertEquals(
                maxOf(SystemClock.uptimeMillis() + 5, minOf(sentAt + 1000 / hz, SystemClock.uptimeMillis() + 250)),
                shadowOf(handler.looper).nextScheduledTaskTime.toMillis(),
            )
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun reapplyingTheRideRatePacesTheNextRequestFromTheRetainedReply() {
        val handler = Handler(Looper.getMainLooper())
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val pending = ControllerBluetooth::class.java.getDeclaredField("pendingSince").apply { isAccessible = true }
        for (hz in listOf(2, 4, 8)) ControllerAdapterFixture(handler).use { adapter ->
            adapter.bluetooth.setHz(hz)
            adapter.identity()
            val sentAt = SystemClock.elapsedRealtime()
            shadowOf(handler.looper).idleFor(Duration.ofMillis(20))
            adapter.notification(frame)
            adapter.bluetooth.setHz(hz)
            shadowOf(handler.looper).idleFor(Duration.ofMillis(1000L / hz - 20))
            assertNull(pending.get(adapter.bluetooth))
            shadowOf(handler.looper).idleFor(Duration.ofMillis(20))
            assertEquals((sentAt + 1000L / hz + 20) / 1000.0, pending.get(adapter.bluetooth))
        }
    }

    @Test
    fun delayedPollsNeverExceedTheRequestedRateAndLongStallsDoNotCatchUp() {
        val handler = Handler(Looper.getMainLooper())
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        for (hz in listOf(2, 4, 8)) {
            ControllerAdapterFixture(handler).use { adapter ->
                adapter.bluetooth.setHz(hz)
                adapter.identity()
                repeat(12) { index ->
                    val sentAt =
                        ControllerBluetooth::class
                            .java
                            .getDeclaredField("pendingSince")
                            .apply { isAccessible = true }
                            .get(adapter.bluetooth) as Double
                    val due =
                        ControllerBluetooth::class
                            .java
                            .getDeclaredField("nextPollAt")
                            .apply { isAccessible = true }
                            .getLong(adapter.bluetooth)
                    assertEquals(kotlin.math.round(sentAt * 1000).toLong() + 1000 / hz, due)
                    ShadowSystemClock.advanceBy(Duration.ofMillis(20))
                    adapter.notification(frame)
                    val delayed = if (index == 10) 2000L else 80L
                    ShadowSystemClock.advanceBy(
                        Duration.ofMillis(maxOf(0, due - SystemClock.elapsedRealtime()) + delayed)
                    )
                    shadowOf(handler.looper).idle()
                    val nextSent =
                        ControllerBluetooth::class
                            .java
                            .getDeclaredField("pendingSince")
                            .apply { isAccessible = true }
                            .get(adapter.bluetooth) as Double
                    assertTrue(nextSent - sentAt + 0.000001 >= 1.0 / hz)
                }
            }
        }
    }

    @Test
    fun writeTimeoutFixturesRunThroughTheAdapterTimer() {
        val handler = Handler(Looper.getMainLooper())
        val rows = telemetryFixture("transport").getJSONArray("timeouts")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            ControllerAdapterFixture(handler).use { adapter ->
                adapter.identity()
                val at = SystemClock.elapsedRealtime() / 1000.0
                field(adapter.bluetooth, "pendingSince", null)
                field(adapter.bluetooth, "writeBlockedAt", at - row.getDouble("now"))
                (field(adapter.bluetooth, "timer") as Runnable).run()
                assertEquals(row.getBoolean("expired"), adapter.bluetooth.state["status"] == "reconnecting")
                if (row.getBoolean("expired"))
                    assertEquals(RideFailure.bikeReconnecting.androidText, adapter.bluetooth.state["error"])
            }
        }
    }

    @Test
    fun aLateReplyAndTheReplyToItsResentRequestRecordOneReading() {
        val handler = Handler(Looper.getMainLooper())
        val readings = mutableListOf<TelemetryReading>()
        ControllerAdapterFixture(handler, readings::add).use { adapter ->
            adapter.identity()
            shadowOf(handler.looper).idleFor(Duration.ofMillis(1010))
            val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
            adapter.queueNotification(frame)
            adapter.queueNotification(frame)
            shadowOf(handler.looper).idle()
            assertEquals(1, readings.size)
            assertEquals("connected", adapter.bluetooth.state["status"])
        }
    }

    @Test
    fun unansweredTelemetryFixturesRunThroughTheAdapterTimer() {
        val handler = Handler(Looper.getMainLooper())
        val rows = telemetryFixture("transport").getJSONArray("telemetryReplies")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            ControllerAdapterFixture(handler).use { adapter ->
                shadowOf(handler.looper).idleFor(Duration.ofMillis(1000 - SystemClock.elapsedRealtime() % 1000))
                adapter.identity()
                val at = SystemClock.elapsedRealtime() / 1000.0
                val origin = at - row.getDouble("now")
                val sentAt = origin + row.getDouble("sentAt")
                val since = origin + row.optDouble("unansweredSince", row.getDouble("sentAt"))
                field(adapter.bluetooth, "pendingSince", sentAt)
                field(adapter.bluetooth, "unansweredSince", since)
                val resends = {
                    adapter.bluetooth.diagnosticLog.read().lines().count { it.contains("\"event\":\"request_resent\"") }
                }
                val before = resends()
                (field(adapter.bluetooth, "timer") as Runnable).run()
                val action = row.getString("action")
                assertEquals(
                    row.toString(),
                    action == "responseTimeout",
                    adapter.bluetooth.state["status"] == "reconnecting",
                )
                if (action != "responseTimeout") {
                    assertEquals(
                        if (action == "resendRequest") at else sentAt,
                        field(adapter.bluetooth, "pendingSince"),
                    )
                    assertEquals(since, field(adapter.bluetooth, "unansweredSince"))
                }
                assertEquals(if (action == "resendRequest") before + 1 else before, resends())
            }
        }
    }

    @Test
    @Config(sdk = [31, 32, 35], shadows = [BlockedControllerGatt::class])
    fun busyGattWriteWaitsForTheNativeCallbackOrTheCoreDeadline() {
        val handler = Handler(Looper.getMainLooper())
        for (recovers in listOf(false, true)) {
            val samples = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, samples::add).use { adapter ->
                val io = org.robolectric.shadow.api.Shadow.extract<BlockedControllerGatt>(adapter.gatt)
                io.blocked = true
                adapter.identity()
                assertEquals(1, io.acceptedWrites.size)
                assertArrayEquals(
                    adapterSession(adapter.bluetooth).request(ControllerRequest.identity),
                    io.acceptedWrites.single(),
                )
                assertEquals("connecting", adapter.bluetooth.state["status"])
                assertTrue(samples.isEmpty())
                if (recovers) {
                    shadowOf(handler.looper).idleFor(Duration.ofMillis(500))
                    io.blocked = false
                    val writer =
                        adapter.gatt
                            .getService(UUID.fromString(ControllerGatt.SERVICE))
                            .getCharacteristic(UUID.fromString(ControllerGatt.WRITE))
                    io.gattCallback.onCharacteristicWrite(adapter.gatt, writer, BluetoothGatt.GATT_SUCCESS)
                    shadowOf(handler.looper).idle()
                    adapter.notification(
                        bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
                    )
                    assertEquals(1, samples.size)
                    assertEquals(2, io.acceptedWrites.size)
                    assertArrayEquals(
                        adapterSession(adapter.bluetooth).request(ControllerRequest.telemetry),
                        io.acceptedWrites.last(),
                    )
                    assertEquals("connected", adapter.bluetooth.state["status"])
                } else {
                    shadowOf(handler.looper).idleFor(Duration.ofMillis(2499))
                    assertEquals("connecting", adapter.bluetooth.state["status"])
                    shadowOf(handler.looper).idleFor(Duration.ofMillis(1))
                    assertEquals("reconnecting", adapter.bluetooth.state["status"])
                    assertEquals(RideFailure.bikeReconnecting.androidText, adapter.bluetooth.state["error"])
                    assertEquals(1, io.acceptedWrites.size)
                    assertTrue(io.disconnectRequested)
                }
            }
        }
    }

    @Test
    @Config(sdk = [31, 32, 35], shadows = [BlockedControllerGatt::class])
    fun writeCompletionCannotEraseABusyDeadlineBeforeTheWatchdogRuns() {
        val handler = Handler(Looper.getMainLooper())
        for (elapsed in listOf(2499L, 2500L)) {
            val readings = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, readings::add).use { adapter ->
                val io = org.robolectric.shadow.api.Shadow.extract<BlockedControllerGatt>(adapter.gatt)
                io.blocked = true
                adapter.identity()
                val timer =
                    ControllerBluetooth::class
                        .java
                        .getDeclaredField("timer")
                        .apply { isAccessible = true }
                        .get(adapter.bluetooth) as Runnable
                handler.removeCallbacks(timer)
                ShadowSystemClock.advanceBy(Duration.ofMillis(elapsed))
                io.blocked = false
                val writer =
                    adapter.gatt
                        .getService(UUID.fromString(ControllerGatt.SERVICE))
                        .getCharacteristic(UUID.fromString(ControllerGatt.WRITE))
                io.gattCallback.onCharacteristicWrite(adapter.gatt, writer, BluetoothGatt.GATT_SUCCESS)
                shadowOf(handler.looper).idle()
                adapter.notification(bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex")))
                if (elapsed == 2499L) {
                    assertEquals("connected", adapter.bluetooth.state["status"])
                    assertEquals(1, readings.size)
                } else {
                    assertEquals("reconnecting", adapter.bluetooth.state["status"])
                    assertEquals(RideFailure.bikeReconnecting.androidText, adapter.bluetooth.state["error"])
                    assertTrue(readings.isEmpty())
                    assertEquals(1, io.acceptedWrites.size)
                    assertTrue(io.disconnectRequested)
                    assertTrue(adapterSession(adapter.bluetooth).state.cancellationPending)
                }
            }
        }
    }
}

@org.robolectric.annotation.Implements(BluetoothGatt::class)
class BlockedControllerGatt : ShadowBluetoothGatt() {
    var blocked = false
    var disconnectRequested = false
    val acceptedWrites = mutableListOf<ByteArray>()

    @org.robolectric.annotation.Implementation
    override fun disconnect() {
        disconnectRequested = true
    }

    @org.robolectric.annotation.Implementation(maxSdk = 32)
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        if (blocked) return false
        acceptedWrites.add(characteristic.value.copyOf())
        return true
    }

    @org.robolectric.annotation.Implementation(minSdk = 33)
    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Int {
        if (blocked) return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        acceptedWrites.add(value.copyOf())
        return BluetoothStatusCodes.SUCCESS
    }
}
