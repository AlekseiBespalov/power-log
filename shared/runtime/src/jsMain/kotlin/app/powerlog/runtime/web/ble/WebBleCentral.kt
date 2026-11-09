package app.powerlog.runtime.web.ble

import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.Clock
import app.powerlog.application.port.GattCharacteristic
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.GattService
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.failure.PlatformCode

internal class WebBleCentral(
    private val bluetooth: Bluetooth,
    private val clock: Clock,
    private val post: (() -> Unit) -> Unit,
) : BleCentral {
    private enum class State {
        connecting,
        connected,
        ended,
    }

    private inner class Link(
        val id: Long,
        val bike: String,
        val profile: TransportProfile,
        val device: BluetoothDevice,
    ) {
        var state = State.connecting
        var server: BluetoothRemoteGATTServer? = null
        var writer: BluetoothRemoteGATTCharacteristic? = null
        var reader: BluetoothRemoteGATTCharacteristic? = null
        var operations = 0
        var owesCanSend = false
        val onDisconnected: (dynamic) -> Unit = { disconnected(this) }
        val onValue: (dynamic) -> Unit = { valueChanged(this) }
    }

    private var events: BleEvents? = null
    private var commandDepth = 0
    private var available = true
    private var nextLink = 1L
    private var link: Link? = null
    private val chosen = mutableMapOf<String, BluetoothDevice>()

    override fun attach(bleEvents: BleEvents) {
        events = bleEvents
        quietly {
            bluetooth.addEventListener("availabilitychanged") { event -> availabilityChanged(event.value === true) }
        }
    }

    override fun prepare() {}

    override fun radio(): RadioState = if (available) RadioState.poweredOn else RadioState.poweredOff

    override fun startSearch(profiles: List<TransportProfile>, inventory: Boolean): Unit = command {
        val services = profiles.map { it.service }.distinct()
        val options: dynamic = js("({})")
        options.filters =
            profiles
                .flatMap { profile ->
                    listOf(servicesFilter(profile.service)) + profile.namePrefixes.map(::prefixFilter)
                }
                .toTypedArray()
        options.optionalServices = services.toTypedArray()
        val request =
            try {
                bluetooth.requestDevice(options)
            } catch (error: dynamic) {
                post { events?.searchEnded(searchError(error)) }
                return@command
            }
        request.then({ device -> chose(device, services) }, { error -> emit { searchEnded(searchError(error)) } })
    }

    override fun stopSearch() {}

    override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long = command {
        val id = nextLink++
        if (radio() != RadioState.poweredOn) {
            link = null
            post { events?.linkFailed(id, PortError(PortErrorKind.poweredOff)) }
            return@command id
        }
        val device = chosen[bike]
        val gatt = device?.gatt
        if (device == null || gatt == null) {
            link = null
            val error = PortError(if (device == null) PortErrorKind.unknownBike else PortErrorKind.unsupported)
            post { events?.linkFailed(id, error) }
            return@command id
        }
        val created = Link(id, bike, profile, device)
        link = created
        quietly { device.addEventListener("gattserverdisconnected", created.onDisconnected) }
        gattCall(created, { gatt.connect() }, { server -> connected(created, server) }) { error ->
            if (current(created) && created.state == State.connecting) {
                close(created)
                emit { linkFailed(id, PortError(kindOf(error, PortErrorKind.connectionFailed), codeOf(error))) }
            }
        }
        return@command id
    }

    override fun ready(link: Long, inventory: Boolean): Unit = command {
        val target = live(link) ?: return@command
        val server = target.server ?: return@command
        gattCall(
            target,
            { server.getPrimaryService(target.profile.service) },
            { service ->
                if (inventory) listCharacteristics(target, service) else characteristics(target, service, null)
            },
        ) { error ->
            readied(target, GattResult.serviceMissing, if (inventory) emptyList() else null, missingError(error))
        }
    }

    override fun send(link: Long, frame: ControllerFrame): SendResult = command {
        val target = live(link)
        val writer = target?.writer ?: return@command SendResult.notConnected
        if (target.operations > 0) {
            target.owesCanSend = true
            return@command SendResult.blocked
        }
        gattCall(target, { writer.writeValueWithoutResponse(frame.bytes) }, {}) { error ->
            if (current(target) && target.state == State.connected)
                emit { writeFailed(target.id, PortError(PortErrorKind.writeFailed, codeOf(error))) }
        }
        return@command SendResult.accepted
    }

    override fun cancel(link: Long): Unit = command {
        val target = this.link?.takeIf { it.id == link && it.state != State.ended } ?: return@command
        close(target)
        disconnect(target.device)
        post { events?.linkDown(target.id, null, false) }
    }

    override fun release(link: Long): Boolean = false

    private fun chose(device: BluetoothDevice, services: List<String>) {
        val at = clock.read()
        chosen[device.id] = device
        emit {
            advertised(Advertisement(device.id, device.name ?: null, null, services, null, at))
            searchEnded(null)
        }
    }

    private fun connected(target: Link, server: BluetoothRemoteGATTServer) {
        if (!current(target) || target.state != State.connecting) {
            if (link?.let { it.device === target.device && it.state != State.ended } != true) disconnect(target.device)
            return
        }
        target.state = State.connected
        target.server = server
        emit { linkUp(target.id, target.bike) }
    }

    private fun disconnected(target: Link) {
        if (!current(target)) return
        when (target.state) {
            State.connecting -> {
                close(target)
                emit { linkFailed(target.id, PortError(PortErrorKind.connectionFailed)) }
            }
            State.connected -> {
                close(target)
                emit { linkDown(target.id, PortError(PortErrorKind.linkLost), false) }
            }
            State.ended -> Unit
        }
    }

    private fun availabilityChanged(on: Boolean) {
        available = on
        emit { radioChanged(radio()) }
        val target = link?.takeIf { !on && it.state != State.ended } ?: return
        close(target)
        disconnect(target.device)
        emit { linkDown(target.id, PortError(PortErrorKind.poweredOff), false) }
    }

    private fun listCharacteristics(target: Link, service: BluetoothRemoteGATTService) {
        gattCall(
            target,
            { service.getCharacteristics() },
            { found ->
                val listed = found.map { GattCharacteristic(it.uuid, propertiesOf(it.properties)) }
                characteristics(target, service, listOf(GattService(service.uuid, listed)))
            },
        ) { error ->
            readied(
                target,
                GattResult.characteristicMissing,
                listOf(GattService(service.uuid, emptyList())),
                missingError(error),
            )
        }
    }

    private fun characteristics(target: Link, service: BluetoothRemoteGATTService, inventory: List<GattService>?) {
        val missing = { error: dynamic ->
            readied(target, GattResult.characteristicMissing, inventory, missingError(error))
        }
        gattCall(
            target,
            { service.getCharacteristic(target.profile.write) },
            { writer ->
                gattCall(
                    target,
                    { service.getCharacteristic(target.profile.notify) },
                    { reader -> subscribe(target, writer, reader, inventory) },
                    missing,
                )
            },
            missing,
        )
    }

    private fun subscribe(
        target: Link,
        writer: BluetoothRemoteGATTCharacteristic,
        reader: BluetoothRemoteGATTCharacteristic,
        inventory: List<GattService>?,
    ) {
        if (!current(target) || target.state != State.connected) return
        val notifies = reader.properties.notify || reader.properties.indicate
        if (!writer.properties.writeWithoutResponse || !notifies) {
            readied(target, GattResult.characteristicMissing, inventory, null)
            return
        }
        target.reader = reader
        quietly { reader.addEventListener("characteristicvaluechanged", target.onValue) }
        gattCall(
            target,
            { reader.startNotifications() },
            {
                if (current(target) && target.state == State.connected) target.writer = writer
                readied(target, GattResult.ready, inventory, null)
            },
        ) { error ->
            quietly { reader.removeEventListener("characteristicvaluechanged", target.onValue) }
            if (target.reader === reader) target.reader = null
            readied(target, GattResult.notifyFailed, inventory, PortError(PortErrorKind.failed, codeOf(error)))
        }
    }

    private fun readied(target: Link, outcome: GattResult, inventory: List<GattService>?, error: PortError?) {
        if (current(target) && target.state == State.connected)
            emit { linkReady(target.id, GattReady(outcome, null, inventory, error)) }
    }

    private fun valueChanged(target: Link) {
        val at = clock.read()
        if (!current(target) || target.state != State.connected) return
        val view: dynamic = target.reader?.value
        if (view == null) emit { notificationFailed(target.id, PortError(PortErrorKind.failed)) }
        else {
            val bytes = copyOf(view)
            emit { notified(target.id, bytes, at) }
        }
    }

    private fun <T> gattCall(target: Link, call: () -> Thenable<T>, ok: (T) -> Unit, failed: (dynamic) -> Unit) {
        target.operations++
        val settle = { outcome: () -> Unit ->
            target.operations--
            outcome()
            if (target.operations == 0 && target.owesCanSend && current(target) && target.state == State.connected) {
                target.owesCanSend = false
                emit { canSend(target.id) }
            }
        }
        val pending =
            try {
                call()
            } catch (error: dynamic) {
                post { settle { failed(error) } }
                return
            }
        pending.then({ value -> settle { ok(value) } }, { error -> settle { failed(error) } })
    }

    private fun live(id: Long): Link? = link?.takeIf { it.id == id && it.state == State.connected }

    private fun current(target: Link): Boolean = link === target

    private fun close(target: Link) {
        target.state = State.ended
        quietly { target.device.removeEventListener("gattserverdisconnected", target.onDisconnected) }
        quietly { target.reader?.removeEventListener("characteristicvaluechanged", target.onValue) }
        target.server = null
        target.writer = null
        target.reader = null
    }

    private fun disconnect(device: BluetoothDevice) {
        quietly { device.gatt?.disconnect() }
    }

    private inline fun <T> command(work: () -> T): T {
        commandDepth++
        try {
            return work()
        } finally {
            commandDepth--
        }
    }

    private fun emit(event: BleEvents.() -> Unit) {
        val sink = events ?: return
        if (commandDepth > 0) post { sink.event() } else sink.event()
    }

    private inline fun quietly(work: () -> Unit) {
        try {
            work()
        } catch (_: dynamic) {}
    }

    private fun searchError(error: dynamic): PortError {
        val code = codeOf(error)
        val kind =
            when (code?.code) {
                "NotFoundError" -> PortErrorKind.cancelled
                "SecurityError" -> PortErrorKind.noUserActivation
                "NotAllowedError" -> PortErrorKind.denied
                else -> PortErrorKind.failed
            }
        return PortError(kind, code.takeIf { kind == PortErrorKind.failed })
    }

    private fun missingError(error: dynamic): PortError? {
        val code = codeOf(error)
        return if (code?.code == "NotFoundError") null else PortError(PortErrorKind.failed, code)
    }

    private fun kindOf(error: dynamic, otherwise: PortErrorKind): PortErrorKind =
        if (codeOf(error)?.code == "NotAllowedError") PortErrorKind.denied else otherwise

    private fun codeOf(error: dynamic): PlatformCode? {
        val name: dynamic = error?.name
        return if (jsTypeOf(name) == "string") PlatformCode("DOMException", name.unsafeCast<String>()) else null
    }

    private companion object {
        fun servicesFilter(service: String): dynamic {
            val filter: dynamic = js("({})")
            filter.services = arrayOf(service)
            return filter
        }

        fun prefixFilter(prefix: String): dynamic {
            val filter: dynamic = js("({})")
            filter.namePrefix = prefix
            return filter
        }

        // The GATT characteristic properties bits, Bluetooth Core Specification Vol 3 Part G §3.3.1.1; reliable writes
        // and writable auxiliaries live in the extended properties, which the 0x80 bit announces.
        fun propertiesOf(properties: BluetoothCharacteristicProperties): Int =
            listOf(
                    properties.broadcast to 0x01,
                    properties.read to 0x02,
                    properties.writeWithoutResponse to 0x04,
                    properties.write to 0x08,
                    properties.notify to 0x10,
                    properties.indicate to 0x20,
                    properties.authenticatedSignedWrites to 0x40,
                    (properties.reliableWrite || properties.writableAuxiliaries) to 0x80,
                )
                .filter { it.first }
                .fold(0) { bits, (_, bit) -> bits or bit }

        fun copyOf(view: dynamic): ByteArray {
            val start: Int = view.byteOffset
            val end: Int = start + view.byteLength.unsafeCast<Int>()
            return Int8Array(view.buffer.slice(start, end)).unsafeCast<ByteArray>()
        }
    }
}

private external class Int8Array(buffer: dynamic)
