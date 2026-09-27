package app.powerlog.bridge

import android.Manifest
import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private lateinit var bluetooth: CycBluetooth
    private val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())

    @Before
    fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter.getRemoteDevice(id)).setName("Fixture bike")
        bluetooth =
            CycBluetooth(
                context,
                handler,
                { event, value -> if (event == "onState") states.add(value) },
                { _, _, _, _, _ -> },
                { recording },
            )
    }

    @After
    fun close() {
        bluetooth.disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    private fun field(name: String): Any? =
        CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bluetooth)

    private fun field(name: String, value: Any?) =
        CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)

    private fun advance(millis: Long) = shadowOf(handler.looper).idleFor(Duration.ofMillis(millis))

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun receive(frame: ByteArray) {
        CycBluetooth::class
            .java
            .getDeclaredMethod("receive", ByteArray::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .invoke(bluetooth, frame, SystemClock.elapsedRealtime(), iso())
    }

    private fun identity() {
        val payload = bytes(fixture.getJSONObject("identity").getString("payloadHex"))
        val crc = CycProtocol.crc(payload)
        field("pending", CycProtocol.Read.IDENTITY)
        field("pendingSince", SystemClock.elapsedRealtime())
        receive(byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3))
    }

    private fun telemetry() {
        field("pending", CycProtocol.Read.TELEMETRY)
        field("pendingSince", SystemClock.elapsedRealtime())
        receive(bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex")))
    }

    private fun failConnection(message: String = "Fixture connection lost") {
        CycBluetooth::class
            .java
            .getDeclaredMethod(
                "failed",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(bluetooth, message, false, false)
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
        for (key in listOf("deviceId", "deviceName", "controllerModel", "firmwareLabel")) {
            assertNotNull(connected[key])
            assertEquals(connected[key], bluetooth.state[key])
        }
        assertEquals("Fixture bike", bluetooth.state["deviceName"])
        assertNull(bluetooth.deviceId)
        bluetooth.connect("02:00:00:00:00:02", 4) {}
        assertNull(bluetooth.state["controllerModel"])
        bluetooth.disconnect()
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
            BluetoothGattService(UUID.fromString(CycProtocol.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writer =
            BluetoothGattCharacteristic(
                UUID.fromString(CycProtocol.WRITE),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val notify =
            BluetoothGattCharacteristic(
                UUID.fromString(CycProtocol.NOTIFY),
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
        assertEquals("CYC connection or identity handshake timed out.", bluetooth.state["error"])
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
                advance(delay)
            }
            advance(20000)
            if (active) {
                assertEquals("reconnecting", bluetooth.state["status"])
                val attempts = bluetooth.diagnostics()["connectionAttempts"] as Int
                advance(29999)
                assertEquals(attempts, bluetooth.diagnostics()["connectionAttempts"])
                advance(1)
                assertEquals(attempts + 1, bluetooth.diagnostics()["connectionAttempts"])
            } else {
                assertEquals("error", bluetooth.state["status"])
                assertEquals(
                    "Reconnect limit reached. CYC connection or identity handshake timed out.",
                    bluetooth.state["error"],
                )
            }
            bluetooth.disconnect()
        }
    }
}
