package app.powerlog.runtime.web.ble

import app.powerlog.application.contracts.BleOsCall
import app.powerlog.application.fakes.FakeBleCentral
import app.powerlog.application.port.GattService
import app.powerlog.domain.core.controller.TransportProfile

internal class FakeWebBluetooth(private val profiles: List<TransportProfile>, private val post: (() -> Unit) -> Unit) {
    private class Database(val services: List<GattService>, val notifyFails: Boolean)

    private inner class Device(val id: String, var name: String?) {
        var connected = false
        var connecting: FakeThenable? = null
        val listeners = Listeners()
        val characteristics = mutableMapOf<String, dynamic>()
        val gatt: dynamic = jsObject()
        val handle: dynamic = jsObject()

        init {
            handle.id = id
            handle.name = name
            handle.gatt = gatt
            handle.addEventListener = listeners::add
            handle.removeEventListener = listeners::remove
            gatt.connect = { connect(this) }
            gatt.disconnect = { disconnect(this) }
            gatt.getPrimaryService = { uuid: String -> primaryService(this, uuid) }
        }
    }

    private val devices = mutableMapOf<String, Device>()
    val calls = mutableListOf<BleOsCall>()
    private val databases = mutableMapOf<String, Database>()
    private val radio = Listeners()
    private val writes = mutableListOf<FakeThenable>()
    private var chooser: FakeThenable? = null
    private var notifying: dynamic = null
    private val shared = ByteArray(64)

    val requests = mutableListOf<dynamic>()

    val bluetooth: dynamic = jsObject()

    init {
        bluetooth.requestDevice = { options: dynamic ->
            requests.add(options)
            FakeThenable(post).also { chooser = it }.handle
        }
        bluetooth.addEventListener = radio::add
    }

    var answerInsideWrites: ByteArray? = null

    fun availability(on: Boolean) {
        val event = jsObject()
        event.value = on
        radio.dispatch("availabilitychanged", event)
    }

    fun choose(bike: String, name: String?) {
        val device = devices.getOrPut(bike) { Device(bike, name) }
        device.name = name
        device.handle.name = name
        chooser?.resolve(device.handle)
        chooser = null
    }

    fun rejectChooser(name: String) {
        chooser?.reject(DOMException("The chooser ended.", name))
        chooser = null
    }

    fun connected(bike: String) {
        val device = devices[bike] ?: return
        val pending = device.connecting ?: return
        device.connecting = null
        device.connected = true
        pending.resolve(device.gatt)
    }

    fun connectFailed(bike: String) {
        val device = devices[bike] ?: return
        val pending = device.connecting ?: return
        device.connecting = null
        pending.reject(DOMException("Connection failed for unknown reason.", "NetworkError"))
    }

    fun disconnected(bike: String) {
        val device = devices[bike] ?: return
        device.connected = false
        device.listeners.dispatch("gattserverdisconnected", jsObject())
    }

    fun database(bike: String, services: List<GattService>, notifyFails: Boolean) {
        databases[bike] = Database(services, notifyFails)
    }

    fun notify(bytes: ByteArray) {
        val characteristic = notifying ?: return
        val offset = 3
        for (index in bytes.indices) shared[offset + index] = bytes[index]
        characteristic.value = DataView(shared.asDynamic().buffer, offset, bytes.size)
        characteristic.dispatch("characteristicvaluechanged", jsObject())
        for (index in bytes.indices) shared[offset + index] = 0
    }

    fun notifyError() {
        val characteristic = notifying ?: return
        characteristic.value = null
        characteristic.dispatch("characteristicvaluechanged", jsObject())
    }

    fun settleWrites(errorName: String?) {
        val pending = writes.filter { it.pending }
        writes.clear()
        for (write in pending) if (errorName == null) write.resolve(undefined)
        else write.reject(DOMException("GATT operation failed for unknown reason.", errorName))
    }

    private fun connect(device: Device): dynamic {
        calls += BleOsCall("connect", device.id)
        val pending = FakeThenable(post)
        device.connecting = pending
        return pending.handle
    }

    private fun disconnect(device: Device) {
        calls += BleOsCall("cancel", device.id)
        device.connecting?.reject(DOMException("Connection attempt aborted.", "AbortError"))
        device.connecting = null
        if (!device.connected) return
        device.connected = false
        post { device.listeners.dispatch("gattserverdisconnected", jsObject()) }
    }

    private fun primaryService(device: Device, uuid: String): dynamic {
        val pending = FakeThenable(post)
        val service = servicesOf(device.id).firstOrNull { it.uuid == uuid }
        when {
            !device.connected -> pending.reject(DOMException("GATT Server is disconnected.", "NetworkError"))
            service == null -> pending.reject(DOMException("No service matching $uuid.", "NotFoundError"))
            else -> pending.resolve(serviceHandle(device, service))
        }
        return pending.handle
    }

    private fun serviceHandle(device: Device, service: GattService): dynamic {
        val handle = jsObject()
        handle.uuid = service.uuid
        handle.getCharacteristic = { uuid: String ->
            val pending = FakeThenable(post)
            val found = service.characteristics.firstOrNull { it.uuid == uuid }
            if (found == null) pending.reject(DOMException("No characteristic matching $uuid.", "NotFoundError"))
            else pending.resolve(characteristic(device, found.uuid, found.properties))
            pending.handle
        }
        handle.getCharacteristics = {
            val pending = FakeThenable(post)
            pending.resolve(
                service.characteristics.map { characteristic(device, it.uuid, it.properties) }.toTypedArray()
            )
            pending.handle
        }
        return handle
    }

    private fun characteristic(device: Device, uuid: String, properties: Int): dynamic =
        device.characteristics.getOrPut(uuid) {
            val listeners = Listeners()
            val handle = jsObject()
            handle.uuid = uuid
            handle.properties = propertiesOf(properties)
            handle.value = null
            handle.addEventListener = listeners::add
            handle.removeEventListener = listeners::remove
            handle.dispatch = { type: String, event: dynamic -> listeners.dispatch(type, event) }
            handle.startNotifications = {
                val pending = FakeThenable(post)
                if (databases[device.id]?.notifyFails == true)
                    pending.reject(DOMException("GATT Error: Not supported.", "NotSupportedError"))
                else {
                    notifying = handle
                    pending.resolve(handle)
                }
                pending.handle
            }
            handle.writeValueWithoutResponse = { _: ByteArray ->
                val pending = FakeThenable(post)
                writes += pending
                answerInsideWrites?.let(::notify)
                pending.handle
            }
            handle
        }

    private fun servicesOf(bike: String): List<GattService> =
        databases[bike]?.services ?: profiles.flatMap { FakeBleCentral.services(it) }

    private fun propertiesOf(bits: Int): dynamic {
        val properties = jsObject()
        properties.broadcast = bits and 0x01 != 0
        properties.read = bits and 0x02 != 0
        properties.writeWithoutResponse = bits and 0x04 != 0
        properties.write = bits and 0x08 != 0
        properties.notify = bits and 0x10 != 0
        properties.indicate = bits and 0x20 != 0
        properties.authenticatedSignedWrites = bits and 0x40 != 0
        properties.reliableWrite = bits and 0x80 != 0
        properties.writableAuxiliaries = false
        return properties
    }

    private class Listeners {
        private val byType = mutableMapOf<String, MutableList<(dynamic) -> Unit>>()

        fun add(type: String, listener: (dynamic) -> Unit) {
            byType.getOrPut(type) { mutableListOf() } += listener
        }

        fun remove(type: String, listener: (dynamic) -> Unit) {
            byType[type]?.remove(listener)
        }

        fun dispatch(type: String, event: dynamic) {
            for (listener in byType[type].orEmpty().toList()) listener(event)
        }
    }
}

internal class FakeThenable(private val post: (() -> Unit) -> Unit) {
    private var outcome: Pair<Boolean, dynamic>? = null
    private val waiting = mutableListOf<Pair<(dynamic) -> Unit, (dynamic) -> Unit>>()

    val handle: dynamic = jsObject()

    init {
        handle.then = { fulfilled: (dynamic) -> Unit, rejected: (dynamic) -> Unit ->
            val callbacks = fulfilled to rejected
            if (outcome == null) waiting += callbacks else deliver(callbacks)
        }
    }

    val pending: Boolean
        get() = outcome == null

    fun resolve(value: dynamic) = settle(true, value)

    fun reject(error: dynamic) = settle(false, error)

    private fun settle(fulfilled: Boolean, value: dynamic) {
        if (outcome != null) return
        outcome = fulfilled to value
        waiting.forEach(::deliver)
        waiting.clear()
    }

    private fun deliver(callbacks: Pair<(dynamic) -> Unit, (dynamic) -> Unit>) {
        val (fulfilled, value) = outcome!!
        post { if (fulfilled) callbacks.first(value) else callbacks.second(value) }
    }
}

private fun jsObject(): dynamic = js("({})")

private external class DOMException(message: String, name: String)

private external class DataView(buffer: dynamic, byteOffset: Int, byteLength: Int)
