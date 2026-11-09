package app.powerlog.platform.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.os.Looper
import app.powerlog.application.contracts.BleOsArgs
import app.powerlog.application.contracts.BleScenarioRunner
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.time.ClockReading
import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [FakeGatt::class, RecordingDevice::class])
class AndroidBleCentralTest {
    private val context = RuntimeEnvironment.getApplication()
    private val runner = BleScenarioRunner(File("../../../tests/fixtures/contract/ports/ble-central.json").readText())

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    }

    @Test
    fun everyAndroidScenarioPasses() {
        assertScenariosPass()
    }

    @Test
    @Config(sdk = [32])
    fun everyAndroidScenarioPassesOnTheGattCallsBeforeApi33() {
        assertScenariosPass()
    }

    @Test
    fun aConnectionIsDirectOverLowEnergyWithItsCallbacksOnTheRideHandler() {
        val harness = AndroidBleHarness(context)
        harness.central.attach(Recorded())
        harness.central.prepare()
        harness.central.connect("D1", runner.profiles.getValue("uart"), adopt = true)

        val device =
            context
                .getSystemService(BluetoothManager::class.java)
                .adapter
                .getRemoteDevice(AndroidBleHarness.address("D1"))
        val recorded = Shadow.extract<RecordingDevice>(device)
        assertSame(Looper.getMainLooper(), recorded.handler?.looper)
        assertEquals(BluetoothDevice.TRANSPORT_LE, recorded.transport)
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, recorded.phy)
    }

    @Test
    @Config(sdk = [32])
    fun beforeApi33TheConnectionTakesNoHandlerSoCallbacksCopyOnTheBinderThread() {
        val harness = AndroidBleHarness(context)
        harness.central.attach(Recorded())
        harness.central.prepare()
        harness.central.connect("D1", runner.profiles.getValue("uart"), adopt = false)

        val device =
            context
                .getSystemService(BluetoothManager::class.java)
                .adapter
                .getRemoteDevice(AndroidBleHarness.address("D1"))
        assertEquals(null, Shadow.extract<RecordingDevice>(device).handler)
    }

    @Test
    fun aSearchFiltersEveryProfilesServiceAtLowLatency() {
        val harness = AndroidBleHarness(context)
        harness.central.attach(Recorded())
        harness.central.startSearch(listOf(runner.profiles.getValue("uart")), inventory = false)

        val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
        val scan = shadowOf(scanner).activeScans.last()
        assertEquals(
            listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            scan.scanFilters().map { it.serviceUuid.toString() },
        )
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, scan.scanSettings()?.scanMode)
        harness.central.stopSearch()
        assertTrue(shadowOf(scanner).activeScans.none { it.scanCallback() === scan.scanCallback() })
    }

    @Test
    fun aNotificationsBytesAreCopiedAndOnlyTheRequestBytesAreWritten() {
        val harness = AndroidBleHarness(context)
        val events = Recorded()
        harness.central.attach(events)
        harness.central.prepare()
        val link = harness.central.connect("D1", runner.profiles.getValue("uart"), adopt = false)
        harness.os("connected", args { put("bike", "D1") })
        harness.os("database", uart())
        harness.central.ready(link, inventory = false)
        harness.drain()

        val identity = harness.frame(ControllerRequest.identity)
        assertEquals(SendResult.accepted, harness.central.send(link, identity))
        val gatt =
            shadowOf(
                    context
                        .getSystemService(BluetoothManager::class.java)
                        .adapter
                        .getRemoteDevice(AndroidBleHarness.address("D1"))
                )
                .bluetoothGatts
                .last()
        assertArrayEquals(identity.bytes, AndroidBleHarness.fake(gatt).writes.single())

        val bytes = byteArrayOf(1, 2, 3)
        AndroidBleHarness.fake(gatt)
            .gattCallback
            .onCharacteristicChanged(
                gatt,
                AndroidBleHarness.fake(gatt)
                    .database
                    .single()
                    .getCharacteristic(java.util.UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")),
                bytes,
            )
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), events.notified.single())
    }

    private fun assertScenariosPass() {
        val results = runner.run("android") { AndroidBleHarness(context) }
        assertTrue("the file has Android scenarios", results.isNotEmpty())
        val failures = results.filter { it.failure != null }
        assertEquals(emptyList<String>(), failures.map { "${it.scenario}: ${it.failure}" })
    }

    private fun uart(): BleOsArgs = args {
        put("bike", "D1")
        putJsonArray("services") {
            add(
                buildJsonObject {
                    put("uuid", "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
                    putJsonArray("characteristics") {
                        add(
                            buildJsonObject {
                                put("uuid", "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
                                put(
                                    "properties",
                                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                                )
                            }
                        )
                        add(
                            buildJsonObject {
                                put("uuid", "6e400003-b5a3-f393-e0a9-e50e24dcca9e")
                                put("properties", BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                            }
                        )
                    }
                }
            )
        }
        put("maxWriteBytes", 182)
    }

    private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): BleOsArgs =
        BleOsArgs(buildJsonObject(build))

    private class Recorded : BleEvents {
        val notified = mutableListOf<ByteArray>()

        override fun radioChanged(state: RadioState) = Unit

        override fun advertised(advertisement: Advertisement) = Unit

        override fun searchEnded(error: PortError?) = Unit

        override fun linkUp(link: Long, bike: String) = Unit

        override fun linkFailed(link: Long, error: PortError) = Unit

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) = Unit

        override fun linkReady(link: Long, result: GattReady) = Unit

        override fun servicesInvalidated(link: Long) = Unit

        override fun canSend(link: Long) = Unit

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {
            notified += bytes
        }

        override fun notificationFailed(link: Long, error: PortError) = Unit

        override fun writeFailed(link: Long, error: PortError) = Unit
    }
}
