package app.powerlog.platform.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.powerlog.application.contracts.BleOsArgs
import app.powerlog.application.contracts.BleOsCall
import app.powerlog.application.contracts.BleScenarioHarness
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.Clock
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.time.ClockReading
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow

class StepClock : Clock {
    var mono = 0L

    override fun monoUs(): Long = mono

    override fun utcUs(): Long = UTC + mono

    override fun read(): ClockReading = ClockReading(mono, utcUs())

    private companion object {
        const val UTC = 1_767_225_600_000_000L
    }
}

// Scenario bikes ("D1") become MAC addresses, which getRemoteDevice requires, and back in the events.
class AndroidBleHarness(private val context: Context) : BleScenarioHarness {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val bluetooth = manager.adapter
    private val clock = StepClock()
    private val adapter = AndroidBleCentral(context, Handler(Looper.getMainLooper()), clock)
    private var notifyFails = false
    private var lastBike: String? = null

    init {
        OsCalls.calls.clear()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(bluetooth).setState(BluetoothAdapter.STATE_ON)
    }

    private val earlierScans = allScans()

    override val osCalls: List<BleOsCall>
        get() = OsCalls.calls.map { (name, address) -> BleOsCall(name, bike(address)) }

    override val central: BleCentral =
        object : BleCentral by adapter {
            override fun attach(bleEvents: BleEvents) = adapter.attach(Named(bleEvents))

            override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long {
                lastBike = bike
                return adapter.connect(address(bike), profile, adopt)
            }
        }

    override fun os(occurrence: String, args: BleOsArgs) {
        when (occurrence) {
            "radio" -> radio(RadioState.valueOf(args.text("state")))
            "remembered" -> Unit
            "discovered" -> {
                clock.mono = args.long("atMonoUs")
                val result =
                    ScanResult(
                        bluetooth.getRemoteDevice(address(args.text("bike"))),
                        record(args.textOrNull("name"), args.texts("services"), args.bytesOrNull("manufacturerData")),
                        args.intOrNull("rssi") ?: 0,
                        0,
                    )
                scans().forEach { it.onScanResult(ScanSettingsCallbackType, result) }
            }
            "scanFailed" -> scans().forEach { it.onScanFailed(args.text("code").toInt()) }
            "connected" ->
                gatt(args.text("bike"))?.let {
                    callback(it)
                        .onConnectionStateChange(it, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
                }
            "connectFailed" ->
                gatt(args.text("bike"))?.let {
                    callback(it).onConnectionStateChange(it, GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)
                }
            "disconnected" ->
                gatt(args.text("bike"))?.let {
                    callback(it).onConnectionStateChange(it, CONNECTION_TIMEOUT, BluetoothProfile.STATE_DISCONNECTED)
                }
            "database" -> {
                val gatt = gatt(args.text("bike")) ?: return
                fake(gatt).database =
                    args.gattServices("services").map { service ->
                        BluetoothGattService(UUID.fromString(service.uuid), BluetoothGattService.SERVICE_TYPE_PRIMARY)
                            .apply {
                                service.characteristics.forEach { characteristic ->
                                    addCharacteristic(
                                        BluetoothGattCharacteristic(
                                                UUID.fromString(characteristic.uuid),
                                                characteristic.properties,
                                                0,
                                            )
                                            .apply {
                                                if (characteristic.properties and NOTIFY != 0)
                                                    addDescriptor(BluetoothGattDescriptor(CLIENT_CONFIGURATION, 0))
                                            }
                                    )
                                }
                            }
                    }
                notifyFails = args.flag("notifyFails")
            }
            "notify" -> {
                clock.mono = args.long("atMonoUs")
                val gatt = latest() ?: return
                val bytes = args.bytes("bytes")
                if (Build.VERSION.SDK_INT >= 33) callback(gatt).onCharacteristicChanged(gatt, notifier(gatt), bytes)
                else {
                    val shared = notifier(gatt)
                    @Suppress("DEPRECATION")
                    callback(gatt).onCharacteristicChanged(gatt, shared.apply { value = bytes })
                    @Suppress("DEPRECATION")
                    shared.value = ByteArray(bytes.size) { 0x55 }
                }
            }
            "notifyError" -> {
                val gatt = latest() ?: return
                @Suppress("DEPRECATION")
                callback(gatt).onCharacteristicChanged(gatt, notifier(gatt).apply { value = null })
            }
            "writeBusy" -> latest()?.let { fake(it).writeBusy = true }
            "writeReady" ->
                latest()?.let {
                    fake(it).writeBusy = false
                    callback(it).onCharacteristicWrite(it, writer(it), BluetoothGatt.GATT_SUCCESS)
                }
            "writeError" ->
                latest()?.let { callback(it).onCharacteristicWrite(it, writer(it), BluetoothGatt.GATT_FAILURE) }
            else -> error("Android has no OS occurrence $occurrence")
        }
    }

    override fun drain() {
        val looper = shadowOf(Looper.getMainLooper())
        do {
            looper.idle()
            val answered = answerDiscovery()
        } while (answered)
    }

    override fun frame(request: ControllerRequest): ControllerFrame = ControllerDecoders.all.first().request(request)

    private fun answerDiscovery(): Boolean {
        val gatt = latest() ?: return false
        val fake = fake(gatt)
        if (fake.discoveries > 0) {
            fake.discoveries--
            callback(gatt).onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            return true
        }
        val descriptor = fake.descriptorWrites.removeFirstOrNull() ?: return false
        callback(gatt)
            .onDescriptorWrite(
                gatt,
                descriptor,
                if (notifyFails) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS,
            )
        return true
    }

    private fun radio(state: RadioState) {
        val android =
            when (state) {
                RadioState.poweredOn -> BluetoothAdapter.STATE_ON
                RadioState.resetting -> BluetoothAdapter.STATE_TURNING_ON
                else -> BluetoothAdapter.STATE_OFF
            }
        shadowOf(bluetooth).setState(android)
        context.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, android)
        )
    }

    private fun allScans(): List<ScanCallback> =
        bluetooth.bluetoothLeScanner?.let { shadowOf(it).scanCallbacks.toList() }.orEmpty()

    private fun scans(): List<ScanCallback> = allScans().filter { it !in earlierScans }

    private fun gatt(bike: String): BluetoothGatt? =
        shadowOf(bluetooth.getRemoteDevice(address(bike))).bluetoothGatts.lastOrNull()

    private fun latest(): BluetoothGatt? = lastBike?.let(::gatt)

    private fun callback(gatt: BluetoothGatt): BluetoothGattCallback = fake(gatt).gattCallback

    private fun notifier(gatt: BluetoothGatt): BluetoothGattCharacteristic =
        fake(gatt).database.firstNotNullOf { it.getCharacteristic(UUID.fromString(UART_NOTIFY)) }

    private fun writer(gatt: BluetoothGatt): BluetoothGattCharacteristic =
        fake(gatt).database.firstNotNullOf { it.getCharacteristic(UUID.fromString(UART_WRITE)) }

    private inner class Named(private val events: BleEvents) : BleEvents by events {
        override fun advertised(advertisement: Advertisement) =
            events.advertised(advertisement.copy(bike = bike(advertisement.bike)))

        override fun linkUp(link: Long, bike: String) = events.linkUp(link, bike(bike))
    }

    companion object {
        private const val ScanSettingsCallbackType = 1
        private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private const val GATT_ERROR = 133
        private const val CONNECTION_TIMEOUT = 8
        private const val UART_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        private const val UART_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun fake(gatt: BluetoothGatt): FakeGatt = Shadow.extract(gatt)

        fun address(bike: String): String = "02:00:00:00:00:${bike.padStart(2, '0')}"

        fun bike(address: String): String = address.takeLast(2)

        // Advertising data structures, Bluetooth Core Supplement Part A §1: complete local name (0x09), complete list
        // of 128-bit service UUIDs (0x07) and manufacturer specific data (0xFF), whose first two bytes are the company.
        fun record(name: String?, services: List<String>, manufacturer: ByteArray?): ScanRecord {
            val bytes = buildList {
                if (name != null) add(structure(0x09, name.toByteArray()))
                if (services.isNotEmpty())
                    add(
                        structure(
                            0x07,
                            services.fold(ByteArray(0)) { all, uuid ->
                                val parsed = UUID.fromString(uuid)
                                all +
                                    ByteBuffer.allocate(16)
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .putLong(parsed.leastSignificantBits)
                                        .putLong(parsed.mostSignificantBits)
                                        .array()
                            },
                        )
                    )
                if (manufacturer != null) add(structure(0xFF, manufacturer))
            }
            val raw = bytes.fold(ByteArray(0)) { all, part -> all + part }
            return ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java).invoke(null, raw)
                as ScanRecord
        }

        private fun structure(type: Int, data: ByteArray): ByteArray =
            byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
    }
}
