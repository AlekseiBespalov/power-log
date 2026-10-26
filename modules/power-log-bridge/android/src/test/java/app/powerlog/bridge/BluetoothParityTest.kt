package app.powerlog.bridge

import android.Manifest
import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BluetoothParityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val id = "02:00:00:00:00:01"
    private val states = mutableListOf<Payload>()
    private var recording = false
    private lateinit var bluetooth: ControllerBluetooth
    private val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())

    @Before
    fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter.getRemoteDevice(id)).setName("Fixture bike")
        bluetooth =
            ControllerBluetooth(
                context,
                handler,
                { event, value -> if (event == "onState") states.add(value) },
                { _, _, _ -> },
                { recording },
            )
    }

    @After
    fun close() {
        bluetooth.disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    private fun field(name: String): Any? =
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bluetooth)

    private fun field(name: String, value: Any?) =
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)

    private fun advance(millis: Long) = shadowOf(handler.looper).idleFor(Duration.ofMillis(millis))

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun receive(frame: ByteArray) = receiveAdapter(bluetooth, frame)

    private fun identity() {
        val payload = bytes(fixture.getJSONObject("identity").getString("payloadHex"))
        val crc = CycProtocol.crc(payload)
        stageIdentity(bluetooth)
        setAdapterRequest(bluetooth, ControllerRequest.identity, SystemClock.elapsedRealtime() / 1000.0)
        receive(byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3))
    }

    private fun telemetry() {
        setAdapterRequest(bluetooth, ControllerRequest.telemetry, SystemClock.elapsedRealtime() / 1000.0)
        receive(bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex")))
    }

    private fun failConnection(message: String = "Fixture connection lost") {
        ControllerBluetooth::class
            .java
            .getDeclaredMethod(
                "failed",
                String::class.java,
                TelemetryDiagnosticReason::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Map::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                bluetooth,
                message,
                TelemetryDiagnosticReason.linkDisconnected,
                false,
                false,
                emptyMap<String, Any?>(),
            )
    }

    @Test
    fun stopSearchPublishesIdleAndEachSearchHasTwentySeconds() {
        bluetooth.startScan()
        advance(15000)
        assertEquals("scanning", bluetooth.state["status"])
        bluetooth.stopScan()
        assertEquals("idle", states.last()["status"])
        bluetooth.startScan()
        advance(5000)
        assertEquals("scanning", bluetooth.state["status"])
        advance(14999)
        assertEquals("scanning", bluetooth.state["status"])
        advance(1)
        assertEquals("idle", states.last()["status"])
    }

    @Test
    fun manualStopSearchImmediatelyPublishesIdleAndCancelsItsDeadline() {
        bluetooth.startScan()
        advance(1000)
        bluetooth.stopScan()
        assertEquals("idle", states.last()["status"])
        states.clear()
        advance(20000)
        assertTrue(states.isEmpty())
    }

    @Test
    fun restartingSearchAndConnectingDoNotPublishAnIntermediateIdle() {
        bluetooth.startScan()
        states.clear()
        bluetooth.startScan()
        assertEquals(listOf("scanning"), states.map { it["status"] })
        states.clear()
        bluetooth.connect(id, 4) {}
        assertEquals(listOf("connecting"), states.map { it["status"] })
        bluetooth.stopScan()
        assertEquals("connecting", bluetooth.state["status"])
    }

    @Test
    fun connectCompletesOnAdmissionAndLaterFailureOnlyChangesState() {
        var completions = 0
        bluetooth.connect(id, 4) {
            assertNull(it)
            completions++
        }
        assertEquals(1, completions)
        assertEquals("connecting", bluetooth.state["status"])
        failConnection()
        assertEquals(1, completions)
        assertEquals("reconnecting", bluetooth.state["status"])
        bluetooth.disconnect()
        advance(60000)
        assertEquals(1, completions)
        assertEquals("idle", bluetooth.state["status"])
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun anotherConnectIsRejectedUntilTheCurrentControllerIsDisconnected() {
        bluetooth.connect(id, 4) {}
        for (status in listOf("connecting", "connected", "reconnecting")) {
            field("state", bluetooth.state + ("status" to status))
            for (device in listOf(id, "02:00:00:00:00:02")) {
                val error =
                    assertThrows(IllegalStateException::class.java) {
                        bluetooth.connect(device, 8) { fail("Unexpected admission") }
                    }
                assertEquals("Disconnect the current controller before connecting again.", error.message)
                assertEquals(4, bluetooth.hz)
                assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
            }
        }
    }

    @Test
    fun bluetoothOffRejectsAdmissionWithoutStartingAnAttempt() {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        val error =
            assertThrows(IllegalStateException::class.java) {
                bluetooth.connect(id, 4) { fail("Unexpected admission") }
            }
        assertEquals("Bluetooth must be powered on; scan first to request permission.", error.message)
        assertEquals(0, bluetooth.diagnostics()["connectionAttempts"])
        assertEquals("idle", bluetooth.state["status"])
    }

    @Test
    fun manualDisconnectKeepsSelectedIdentityAndSelectingAnotherBikeDoesNotReuseIt() {
        bluetooth.connect(id, 4) {}
        identity()
        telemetry()
        val connected = bluetooth.state
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        for (key in listOf("deviceId", "deviceName", "controllerModel", "firmwareLabel")) {
            assertNotNull(connected[key])
            assertEquals(connected[key], bluetooth.state[key])
        }
        assertEquals("Fixture bike", bluetooth.state["deviceName"])
        assertNull(bluetooth.deviceId)
        bluetooth.connect("02:00:00:00:00:02", 4) {}
        assertNull(bluetooth.state["controllerModel"])
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        bluetooth.connect(id, 4) {}
        assertEquals(connected["controllerModel"], bluetooth.state["controllerModel"])
    }

    @Test
    fun retryKeepsIdentityAndRecoveryErrorUntilFreshTelemetry() {
        bluetooth.connect(id, 4) {}
        identity()
        telemetry()
        val model = bluetooth.state["controllerModel"]
        failConnection()
        completeCancellation(bluetooth, handler)
        advance(1000)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals(model, bluetooth.state["controllerModel"])
        assertEquals("Fixture connection lost", bluetooth.state["error"])
        assertEquals(true, bluetooth.state["recoverableConnectionError"])
        identity()
        assertEquals("Fixture connection lost", bluetooth.state["error"])
        telemetry()
        assertEquals("connected", bluetooth.state["status"])
        assertNull(bluetooth.state["error"])
        assertEquals(false, bluetooth.state["recoverableConnectionError"])
    }

    @Test
    fun oneTwentySecondDeadlineCoversConnectionDiscoveryAndIdentity() {
        bluetooth.connect(id, 4) {}
        val gatt = field("gatt") as BluetoothGatt
        advance(15000)
        assertEquals("connecting", bluetooth.state["status"])
        val service =
            BluetoothGattService(UUID.fromString(ControllerGatt.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writer =
            BluetoothGattCharacteristic(
                UUID.fromString(ControllerGatt.WRITE),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val notify =
            BluetoothGattCharacteristic(
                UUID.fromString(ControllerGatt.NOTIFY),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(writer)
        service.addCharacteristic(notify)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).allowCharacteristicNotification(notify)
        advance(4000)
        shadowOf(gatt)
            .gattCallback
            .onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        advance(999)
        assertEquals("connecting", bluetooth.state["status"])
        advance(1)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals("Controller connection or identity handshake timed out.", bluetooth.state["error"])
        assertFalse(shadowOf(gatt).isClosed)
        completeCancellation(bluetooth, handler)
        assertTrue(shadowOf(gatt).isClosed)
        advance(999)
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
        advance(1)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun connectionDeadlineUsesFiveRetriesThenStopsUnlessARideIsActive() {
        for (active in listOf(false, true)) {
            recording = active
            bluetooth.connect(id, 4) {}
            for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L)) {
                advance(20000)
                assertEquals("reconnecting", bluetooth.state["status"])
                completeCancellation(bluetooth, handler)
                advance(delay)
            }
            advance(20000)
            if (active) {
                assertEquals("reconnecting", bluetooth.state["status"])
                val attempts = bluetooth.diagnostics()["connectionAttempts"] as Int
                completeCancellation(bluetooth, handler)
                advance(29999)
                assertEquals(attempts, bluetooth.diagnostics()["connectionAttempts"])
                advance(1)
                assertEquals(attempts + 1, bluetooth.diagnostics()["connectionAttempts"])
            } else {
                assertEquals("error", bluetooth.state["status"])
                assertEquals(
                    "Reconnect limit reached. Controller connection or identity handshake timed out.",
                    bluetooth.state["error"],
                )
            }
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun cancellationBeforeConnectionClosesWithoutCallbackAndAllowsReconnect() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        bluetooth.disconnect()
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        bluetooth.connect(id, 4) {}
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
        assertEquals("connecting", bluetooth.state["status"])
    }

    @Test fun establishedCancellationTimeoutRetainsIdleRetryCount() = cancellationTimeout(false)

    @Test fun establishedCancellationTimeoutRetainsRecordingRetryCount() = cancellationTimeout(true)

    private fun cancellationTimeout(active: Boolean) {
        recording = active
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        advance(11000)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        assertEquals(id, bluetooth.deviceId)
        assertEquals(1, field("retries"))
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
        val current = field("gatt")
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        advance(0)
        assertSame(current, field("gatt"))
    }

    @Test
    fun radioOffReleasesHandleAndRadioOnResumesIntent() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        context.sendBroadcast(
            android.content
                .Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        )
        advance(0)
        assertTrue(shadowOf(old).isClosed)
        assertEquals(id, bluetooth.deviceId)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        context.sendBroadcast(
            android.content
                .Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
        )
        advance(0)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun lateDiscoveryFromAnEarlierSearchCannotPopulateTheNewSearch() {
        val deadline = telemetryFixture("deadlines")
        bluetooth.startScan()
        val old = field("scanner") as android.bluetooth.le.ScanCallback
        bluetooth.startScan()
        old.onScanResult(0, android.bluetooth.le.ScanResult(adapter.getRemoteDevice(id), null, -50, 0))
        advance(0)
        assertTrue((field("devices") as Map<*, *>).isEmpty())
        val current = field("scanner") as android.bluetooth.le.ScanCallback
        advance((deadline.getDouble("attemptDeadline") * 1000).toLong())
        current.onScanResult(0, android.bluetooth.le.ScanResult(adapter.getRemoteDevice(id), null, -50, 0))
        advance(0)
        assertTrue((field("devices") as Map<*, *>).isEmpty())
        assertEquals("idle", bluetooth.state["status"])
    }

    @Test
    fun retryFixturesUseTheAdapterAndDoNotExtendTheCancellationFence() {
        val rows = telemetryFixture("transport").getJSONArray("retries")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            recording = row.getBoolean("active")
            bluetooth.connect(id, 4) {}
            field("retries", row.getInt("attempt") - 1)
            field(
                "stableSince",
                if (row.isNull("stable")) null else SystemClock.elapsedRealtime() / 1000.0 - row.getDouble("stable"),
            )
            ControllerBluetooth::class
                .java
                .getDeclaredMethod(
                    "failed",
                    String::class.java,
                    TelemetryDiagnosticReason::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Map::class.java,
                )
                .apply { isAccessible = true }
                .invoke(
                    bluetooth,
                    "fixture",
                    TelemetryDiagnosticReason.linkDisconnected,
                    row.getBoolean("peer") && row.getBoolean("completed"),
                    false,
                    emptyMap<String, Any?>(),
                )
            val transport = adapterSession(bluetooth).state
            if (row.isNull("delay")) assertEquals("error", bluetooth.state["status"])
            else {
                assertEquals(
                    row.getDouble("delay"),
                    transport.reconnectDue!! - SystemClock.elapsedRealtime() / 1000.0,
                    0.000001,
                )
                assertEquals(transport.reconnectDue!! + 5, transport.cancellationDeadline!!, 0.0)
            }
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun cancellationCompletionAndOldCallbacksFollowTheDeadlineFixture() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        advance(20000)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        advance(1000)
        val current = field("gatt")
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        advance(0)
        assertSame(current, field("gatt"))
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun preConnectTimeoutRejectsLateNativeConnectionWithoutWaitingForCancellation() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        advance(20000)
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        advance(1000)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
        assertNotSame(old, field("gatt"))
    }
}
