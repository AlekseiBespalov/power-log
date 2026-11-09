package app.powerlog.runtime.web.ble

internal external interface Thenable<out T> {
    fun then(onFulfilled: (T) -> Unit, onRejected: (error: dynamic) -> Unit): dynamic
}

internal external interface Bluetooth {
    fun requestDevice(options: dynamic): Thenable<BluetoothDevice>

    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
}

internal external interface BluetoothDevice {
    val id: String
    val name: String?
    val gatt: BluetoothRemoteGATTServer?

    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)

    fun removeEventListener(type: String, listener: (event: dynamic) -> Unit)
}

internal external interface BluetoothRemoteGATTServer {
    fun connect(): Thenable<BluetoothRemoteGATTServer>

    fun disconnect()

    fun getPrimaryService(service: String): Thenable<BluetoothRemoteGATTService>
}

internal external interface BluetoothRemoteGATTService {
    val uuid: String

    fun getCharacteristic(characteristic: String): Thenable<BluetoothRemoteGATTCharacteristic>

    fun getCharacteristics(): Thenable<Array<BluetoothRemoteGATTCharacteristic>>
}

internal external interface BluetoothRemoteGATTCharacteristic {
    val uuid: String
    val properties: BluetoothCharacteristicProperties
    val value: dynamic

    fun startNotifications(): Thenable<BluetoothRemoteGATTCharacteristic>

    fun writeValueWithoutResponse(value: ByteArray): Thenable<Unit>

    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)

    fun removeEventListener(type: String, listener: (event: dynamic) -> Unit)
}

internal external interface BluetoothCharacteristicProperties {
    val broadcast: Boolean
    val read: Boolean
    val writeWithoutResponse: Boolean
    val write: Boolean
    val notify: Boolean
    val indicate: Boolean
    val authenticatedSignedWrites: Boolean
    val reliableWrite: Boolean
    val writableAuxiliaries: Boolean
}
