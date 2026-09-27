package app.powerlog.bridge

import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private fun field(bluetooth: CycBluetooth, name: String, value: Any?) {
        CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)
    }

    private fun receive(bluetooth: CycBluetooth, frame: ByteArray) {
        CycBluetooth::class
            .java
            .getDeclaredMethod("receive", ByteArray::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .invoke(bluetooth, frame, SystemClock.elapsedRealtime(), iso())
    }

    @Test
    fun malformedConnectCannotChangePollingOrChooseADevice() {
        val handler = Handler(Looper.getMainLooper())
        val bluetooth =
            CycBluetooth(RuntimeEnvironment.getApplication(), handler, { _, _ -> }, { _, _, _, _, _ -> }, { false })
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
        val bluetooth = CycBluetooth(context, handler, { _, _ -> }, { _, _, _, _, _ -> samples++ }, { true })
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:01")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        shadowOf(gatt).setGattCallback(object : BluetoothGattCallback() {})
        val writer =
            BluetoothGattCharacteristic(
                UUID.fromString(CycProtocol.WRITE),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        BluetoothGattService(UUID.fromString(CycProtocol.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .addCharacteristic(writer)
        field(bluetooth, "gatt", gatt)
        field(bluetooth, "writer", writer)
        field(bluetooth, "pending", CycProtocol.Read.IDENTITY)
        field(bluetooth, "pendingSince", SystemClock.elapsedRealtime())
        val payload = bytes(fixture.getJSONObject("identity").getString("payloadHex"))
        val crc = CycProtocol.crc(payload)
        val identity =
            byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
        val telemetry = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        try {
            receive(bluetooth, identity + telemetry)
            assertEquals(0, samples)
            assertEquals(0L, bluetooth.sampleCount)
            assertArrayEquals(CycProtocol.request(CycProtocol.Read.TELEMETRY), shadowOf(gatt).latestWrittenBytes)
            receive(bluetooth, telemetry)
            assertEquals(1, samples)
            assertEquals(1L, bluetooth.sampleCount)
        } finally {
            bluetooth.disconnect()
            handler.removeCallbacksAndMessages(null)
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
                CycBluetooth(
                    RuntimeEnvironment.getApplication(),
                    handler,
                    { _, _ -> },
                    { _, _, _, _, _ -> samples++ },
                    { true },
                )
            field(bluetooth, "desired", "02:00:00:00:00:01")
            field(bluetooth, "identity", identity)
            field(bluetooth, "pending", CycProtocol.Read.TELEMETRY)
            field(bluetooth, "pendingSince", SystemClock.elapsedRealtime() - age)
            receive(bluetooth, frame)
            assertEquals(if (age == 2500L) 1 else 0, samples)
            assertEquals(if (age == 2500L) "connected" else "reconnecting", bluetooth.state["status"])
            val timeouts =
                CycBluetooth::class.java.getDeclaredField("timeouts").apply { isAccessible = true }.get(bluetooth)
            assertEquals(if (age == 2500L) 0 else 1, timeouts)
            bluetooth.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun explicitReconnectKeepsTheRateAdmittedForTheRide() {
        val handler = Handler(Looper.getMainLooper())
        var recording = true
        val bluetooth =
            CycBluetooth(
                RuntimeEnvironment.getApplication(),
                handler,
                { _, _ -> },
                { _, _, _, _, _ -> },
                { recording },
            )
        val id = "02:00:00:00:00:01"
        bluetooth.setHz(4)
        field(bluetooth, "desired", id)
        field(bluetooth, "state", mapOf("status" to "connected"))
        bluetooth.connect(id, 8) { assertNull(it) }
        assertEquals(4, bluetooth.hz)
        bluetooth.disconnect()
        bluetooth.connect(id, 2) {}
        assertEquals(4, bluetooth.hz)
        recording = false
        bluetooth.connect(id, 8) {}
        assertEquals(8, bluetooth.hz)
        bluetooth.disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    @Test
    fun captureWorkDoesNotAddAnExtraDelayToEveryPoll() {
        val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val identity = CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val handler = Handler(Looper.getMainLooper())
        for (hz in listOf(2, 4, 8)) for (storageMillis in listOf(0L, 80L, 600L)) {
            val bluetooth =
                CycBluetooth(
                    RuntimeEnvironment.getApplication(),
                    handler,
                    { _, _ -> },
                    { _, _, _, _, _ -> ShadowSystemClock.advanceBy(Duration.ofMillis(storageMillis)) },
                    { true },
                )
            bluetooth.setHz(hz)
            val sentAt = SystemClock.elapsedRealtime()
            fun field(name: String, value: Any) {
                CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)
            }
            field("pending", CycProtocol.Read.TELEMETRY)
            field("pendingSince", sentAt)
            field("nextPollAt", nextTelemetryPollMillis(0, sentAt, hz))
            field("identity", identity)
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            receive(bluetooth, frame)
            assertEquals(1L, bluetooth.sampleCount)
            assertEquals(
                maxOf(sentAt + 1000 / hz, SystemClock.uptimeMillis()),
                shadowOf(handler.looper).nextScheduledTaskTime.toMillis(),
            )
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun shortTimerDelaysDoNotAccumulateAndLongStallsDoNotQueueCatchupRequests() {
        for (hz in listOf(2, 4, 8)) {
            val origin = 1000L
            val period = 1000L / hz
            var due = nextTelemetryPollMillis(0, origin, hz)
            repeat(60 * hz) { index ->
                val timerDelay = if (index % 7 == 0) 80L else 10L
                due = nextTelemetryPollMillis(due, due + timerDelay, hz)
            }
            assertEquals(origin + 60000 + period, due)
            val resumedAt = due + 2000
            assertEquals(resumedAt + period, nextTelemetryPollMillis(due, resumedAt, hz))
            assertEquals(resumedAt + period, nextTelemetryPollMillis(0, resumedAt, hz))
        }
    }
}
