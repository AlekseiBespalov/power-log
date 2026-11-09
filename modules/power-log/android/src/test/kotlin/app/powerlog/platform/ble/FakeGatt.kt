package app.powerlog.platform.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import java.util.UUID
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt

// Robolectric's own BluetoothGatt answers discovery, writes and disconnects synchronously inside the call; this one
// records each operation and leaves its callback to the test, as the OS delivers it later on the callback handler.
@Implements(BluetoothGatt::class)
class FakeGatt : ShadowBluetoothGatt() {
    @field:RealObject private lateinit var real: BluetoothGatt
    var database: List<BluetoothGattService> = emptyList()
    var notificationsAllowed = true
    var writeBusy = false
    var discoveries = 0
    val descriptorWrites = mutableListOf<BluetoothGattDescriptor>()
    val writes = mutableListOf<ByteArray>()
    var disconnects = 0
    var closes = 0

    @Implementation
    override fun discoverServices(): Boolean {
        discoveries++
        return true
    }

    @Implementation override fun getServices(): List<BluetoothGattService> = database

    @Implementation
    override fun getService(uuid: UUID): BluetoothGattService? = database.firstOrNull { it.uuid == uuid }

    @Implementation
    override fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean): Boolean =
        notificationsAllowed

    @Implementation(minSdk = 33)
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Int {
        descriptorWrites += descriptor
        return BluetoothStatusCodes.SUCCESS
    }

    @Implementation
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
        descriptorWrites += descriptor
        return true
    }

    @Implementation(minSdk = 33)
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, type: Int): Int {
        if (writeBusy) return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        check(type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) { "every write is without response" }
        writes += value.copyOf()
        return BluetoothStatusCodes.SUCCESS
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        if (writeBusy) return false
        check(characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            "every write is without response"
        }
        writes += characteristic.value.copyOf()
        return true
    }

    @Implementation
    override fun disconnect() {
        OsCalls.calls += "cancel" to real.device.address
        disconnects++
    }

    @Implementation
    override fun close() {
        closes++
    }
}

@Implements(BluetoothDevice::class)
class RecordingDevice : ShadowBluetoothDevice() {
    @field:RealObject private lateinit var real: BluetoothDevice
    var handler: Handler? = null
    var transport: Int? = null
    var phy: Int? = null

    @Implementation(minSdk = 26)
    override fun connectGatt(
        context: Context,
        autoConnect: Boolean,
        callback: BluetoothGattCallback,
        transport: Int,
        phy: Int,
        handler: Handler?,
    ): BluetoothGatt {
        check(!autoConnect) { "connects directly" }
        OsCalls.calls += "connect" to real.address
        this.handler = handler
        this.transport = transport
        this.phy = phy
        return super.connectGatt(context, autoConnect, callback, transport, phy, handler)
    }
}

object OsCalls {
    val calls = mutableListOf<Pair<String, String>>()
}
