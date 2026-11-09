@file:Suppress("MissingPermission", "DEPRECATION")

package app.powerlog.platform.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
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
import app.powerlog.domain.core.time.ClockReading
import java.util.UUID

class AndroidBleCentral(private val context: Context, private val handler: Handler, private val clock: Clock) :
    BleCentral {
    private enum class State {
        connecting,
        connected,
        cancelling,
        ended,
    }

    private inner class Link(val id: Long, val bike: String, val profile: TransportProfile) {
        var state = State.connecting
        var gatt: BluetoothGatt? = null
        var mtu = DEFAULT_MTU
        var writer: BluetoothGattCharacteristic? = null
        var notifier: BluetoothGattCharacteristic? = null
        var inventory = false
        var operationPending = false
        var owesCanSend = false
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var events: BleEvents? = null
    private var receiver: BroadcastReceiver? = null
    private var scan: ScanCallback? = null
    private var nextLink = 1L
    private var link: Link? = null

    override fun attach(bleEvents: BleEvents) {
        events = bleEvents
    }

    override fun prepare() {
        if (receiver != null) return
        val radioReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    handler.post { radioChanged(radioOf(state)) }
                }
            }
        receiver = radioReceiver
        ContextCompat.registerReceiver(
            context,
            radioReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun radio(): RadioState {
        val adapter = manager?.adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            return RadioState.unsupported
        return try {
            radioOf(adapter.state)
        } catch (_: SecurityException) {
            RadioState.denied
        }
    }

    override fun startSearch(profiles: List<TransportProfile>, inventory: Boolean) {
        stopSearch()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val at = clock.read()
                    handler.post { if (scan === this) advertised(result, inventory, at) }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scan !== this) return@post
                        scan = null
                        events?.searchEnded(
                            PortError(PortErrorKind.searchFailed, PlatformCode("AndroidScan", errorCode.toString()))
                        )
                    }
                }
            }
        val failure =
            try {
                val scanner = manager?.adapter?.bluetoothLeScanner
                if (scanner == null) PortError(PortErrorKind.poweredOff)
                else {
                    scan = callback
                    scanner.startScan(
                        profiles
                            .map { it.service }
                            .distinct()
                            .map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() },
                        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                        callback,
                    )
                    null
                }
            } catch (_: SecurityException) {
                PortError(PortErrorKind.denied)
            } catch (_: RuntimeException) {
                PortError(PortErrorKind.searchFailed)
            }
        if (failure != null) {
            scan = null
            handler.post { events?.searchEnded(failure) }
        }
    }

    override fun stopSearch() {
        val callback = scan ?: return
        scan = null
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: RuntimeException) {}
    }

    override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long {
        if (link?.state == State.cancelling) {
            val refused = nextLink++
            handler.post { events?.linkFailed(refused, PortError(PortErrorKind.notReady)) }
            return refused
        }
        link?.let(::end)
        val created = Link(nextLink++, bike, profile)
        link = created
        val radio = radio()
        if (radio != RadioState.poweredOn) {
            created.state = State.ended
            handler.post { events?.linkFailed(created.id, PortError(radioFailure(radio))) }
            return created.id
        }
        val failure =
            try {
                val device = manager.adapter.getRemoteDevice(bike)
                created.gatt =
                    if (Build.VERSION.SDK_INT >= 33)
                        device.connectGatt(
                            context,
                            false,
                            callback(created),
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            handler,
                        )
                    else
                        device.connectGatt(
                            context,
                            false,
                            callback(created),
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                        )
                if (created.gatt == null) PortError(PortErrorKind.connectionFailed) else null
            } catch (_: SecurityException) {
                PortError(PortErrorKind.denied)
            } catch (_: IllegalArgumentException) {
                PortError(PortErrorKind.connectionFailed)
            }
        if (failure != null) {
            created.state = State.ended
            handler.post { events?.linkFailed(created.id, failure) }
        }
        return created.id
    }

    override fun ready(link: Long, inventory: Boolean) {
        val current = live(link) ?: return
        val gatt = current.gatt ?: return
        current.inventory = inventory
        val started =
            try {
                gatt.discoverServices()
            } catch (_: SecurityException) {
                false
            }
        if (!started) handler.post { if (live(link) === current) ready(current, GattResult.serviceMissing, failed()) }
    }

    override fun send(link: Long, frame: ControllerFrame): SendResult {
        val current = live(link) ?: return SendResult.notConnected
        val gatt = current.gatt ?: return SendResult.notConnected
        val writer = current.writer ?: return SendResult.notConnected
        if (frame.bytes.size > current.mtu - ATT_HEADER_BYTES) return SendResult.tooLong
        if (current.operationPending) {
            current.owesCanSend = true
            return SendResult.blocked
        }
        val status =
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    gatt.writeCharacteristic(writer, frame.bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                else {
                    writer.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    writer.value = frame.bytes
                    if (gatt.writeCharacteristic(writer)) BluetoothStatusCodes.SUCCESS
                    else BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                }
            } catch (_: SecurityException) {
                handler.post { events?.writeFailed(link, PortError(PortErrorKind.denied)) }
                return SendResult.accepted
            }
        return when (status) {
            BluetoothStatusCodes.SUCCESS -> {
                current.operationPending = true
                SendResult.accepted
            }
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                current.owesCanSend = true
                SendResult.blocked
            }
            else -> {
                val error = PortError(PortErrorKind.writeFailed, bluetoothStatus(status))
                handler.post { events?.writeFailed(link, error) }
                SendResult.accepted
            }
        }
    }

    override fun cancel(link: Long) {
        val current = this.link?.takeIf { it.id == link } ?: return
        when (current.state) {
            State.connecting -> {
                disconnect(current)
                end(current)
                handler.post { events?.linkDown(link, null, false) }
            }
            State.connected -> {
                current.state = State.cancelling
                disconnect(current)
            }
            State.cancelling,
            State.ended -> Unit
        }
    }

    override fun release(link: Long): Boolean {
        val current = this.link?.takeIf { it.id == link && it.state == State.cancelling } ?: return false
        end(current)
        handler.post { events?.linkDown(link, null, false) }
        return true
    }

    private fun callback(owner: Link) =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = onRide {
                if (!owns(owner, gatt)) return@onRide
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (owner.state != State.connecting) return@onRide
                    owner.state = State.connected
                    events?.linkUp(owner.id, owner.bike)
                    return@onRide
                }
                if (newState != BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS)
                    return@onRide
                val previous = owner.state
                end(owner)
                val code = PlatformCode("AndroidGattStatus", status.toString())
                when (previous) {
                    State.connecting -> events?.linkFailed(owner.id, PortError(PortErrorKind.connectionFailed, code))
                    State.connected -> events?.linkDown(owner.id, PortError(PortErrorKind.linkLost, code), false)
                    State.cancelling -> events?.linkDown(owner.id, null, false)
                    State.ended -> Unit
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = onRide {
                if (owns(owner, gatt) && status == BluetoothGatt.GATT_SUCCESS) owner.mtu = mtu
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = onRide {
                if (!owns(owner, gatt) || owner.state != State.connected) return@onRide
                if (status != BluetoothGatt.GATT_SUCCESS)
                    ready(owner, GattResult.serviceMissing, PortError(PortErrorKind.failed, gattStatus(status)))
                else enableNotifications(owner, gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                onRide {
                    if (!owns(owner, gatt) || owner.state != State.connected || !owner.operationPending) return@onRide
                    owner.operationPending = false
                    if (status == BluetoothGatt.GATT_SUCCESS) ready(owner, GattResult.ready, null)
                    else ready(owner, GattResult.notifyFailed, PortError(PortErrorKind.failed, gattStatus(status)))
                    owedCanSend(owner)
                }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) = onRide {
                if (!owns(owner, gatt) || characteristic.uuid != owner.writer?.uuid) return@onRide
                owner.operationPending = false
                if (status != BluetoothGatt.GATT_SUCCESS)
                    events?.writeFailed(owner.id, PortError(PortErrorKind.writeFailed, gattStatus(status)))
                owedCanSend(owner)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                val at = clock.read()
                if (owns(owner, gatt) && characteristic.uuid == owner.notifier?.uuid)
                    events?.notified(owner.id, value.copyOf(), at)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val at = clock.read()
                val value = characteristic.value?.copyOf()
                val uuid = characteristic.uuid
                onRide {
                    if (!owns(owner, gatt) || uuid != owner.notifier?.uuid) return@onRide
                    if (value == null) events?.notificationFailed(owner.id, PortError(PortErrorKind.failed))
                    else events?.notified(owner.id, value, at)
                }
            }
        }

    // Before API 33 GATT callbacks arrive on a binder thread, and the OS writes each notification into the shared
    // characteristic first, so the bytes and the receipt time are taken there before the hop to ride.
    private fun onRide(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) block() else handler.post(block)
    }

    private fun enableNotifications(owner: Link, gatt: BluetoothGatt) {
        val profile = owner.profile
        val service = gatt.getService(UUID.fromString(profile.service))
        if (service == null) return ready(owner, GattResult.serviceMissing, null)
        val writer = service.getCharacteristic(UUID.fromString(profile.write))
        val notifier = service.getCharacteristic(UUID.fromString(profile.notify))
        val descriptor = notifier?.getDescriptor(CLIENT_CONFIGURATION)
        if (
            writer == null ||
                writer.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0 ||
                notifier == null ||
                notifier.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
                descriptor == null
        )
            return ready(owner, GattResult.characteristicMissing, null)
        owner.writer = writer
        owner.notifier = notifier
        val status =
            try {
                if (!gatt.setCharacteristicNotification(notifier, true))
                    return ready(owner, GattResult.notifyFailed, null)
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, value)
                else {
                    descriptor.value = value
                    if (gatt.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS
                    else BluetoothStatusCodes.ERROR_UNKNOWN
                }
            } catch (_: SecurityException) {
                return ready(owner, GattResult.notifyFailed, PortError(PortErrorKind.denied))
            }
        if (status == BluetoothStatusCodes.SUCCESS) owner.operationPending = true
        else ready(owner, GattResult.notifyFailed, PortError(PortErrorKind.failed, bluetoothStatus(status)))
    }

    private fun ready(owner: Link, outcome: GattResult, error: PortError?) {
        val gatt = owner.gatt
        val inventory =
            if (owner.inventory && gatt != null)
                gatt.services.map { service ->
                    GattService(
                        service.uuid.toString(),
                        service.characteristics.map { GattCharacteristic(it.uuid.toString(), it.properties) },
                    )
                }
            else null
        val maxWriteBytes = if (outcome == GattResult.ready) owner.mtu - ATT_HEADER_BYTES else null
        events?.linkReady(owner.id, GattReady(outcome, maxWriteBytes, inventory, error))
    }

    private fun owedCanSend(owner: Link) {
        if (!owner.owesCanSend || owner.state != State.connected) return
        owner.owesCanSend = false
        events?.canSend(owner.id)
    }

    private fun advertised(result: ScanResult, inventory: Boolean, at: ClockReading) {
        val record = result.scanRecord
        val manufacturer =
            if (!inventory) null
            else
                record
                    ?.manufacturerSpecificData
                    ?.takeIf { it.size() > 0 }
                    ?.let { data ->
                        val company = data.keyAt(0)
                        byteArrayOf(company.toByte(), (company shr 8).toByte()) + (data.valueAt(0) ?: ByteArray(0))
                    }
        events?.advertised(
            Advertisement(
                bike = result.device.address,
                name = record?.deviceName,
                rssi = result.rssi,
                services = record?.serviceUuids.orEmpty().map { it.uuid.toString() },
                manufacturerData = manufacturer,
                at = at,
            )
        )
    }

    private fun radioChanged(state: RadioState) {
        events?.radioChanged(state)
        if (state == RadioState.poweredOn) return
        val current = link ?: return
        val previous = current.state
        if (previous == State.ended) return
        end(current)
        when (previous) {
            State.connecting -> events?.linkFailed(current.id, PortError(radioFailure(state)))
            State.connected -> events?.linkDown(current.id, PortError(radioFailure(state)), false)
            State.cancelling -> events?.linkDown(current.id, null, false)
            State.ended -> Unit
        }
    }

    private fun live(id: Long): Link? = link?.takeIf { it.id == id && it.state == State.connected }

    private fun owns(owner: Link, gatt: BluetoothGatt): Boolean =
        link === owner && owner.gatt === gatt && owner.state != State.ended

    private fun disconnect(owner: Link) {
        try {
            owner.gatt?.disconnect()
        } catch (_: RuntimeException) {}
    }

    private fun end(owner: Link) {
        owner.state = State.ended
        val gatt = owner.gatt ?: return
        owner.gatt = null
        try {
            gatt.close()
        } catch (_: RuntimeException) {}
    }

    private fun failed() = PortError(PortErrorKind.failed)

    private companion object {
        const val DEFAULT_MTU = 23
        const val ATT_HEADER_BYTES = 3
        val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun radioOf(state: Int): RadioState =
            when (state) {
                BluetoothAdapter.STATE_ON -> RadioState.poweredOn
                BluetoothAdapter.STATE_TURNING_ON -> RadioState.resetting
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> RadioState.poweredOff
                else -> RadioState.unknown
            }

        fun radioFailure(state: RadioState): PortErrorKind =
            when (state) {
                RadioState.poweredOff -> PortErrorKind.poweredOff
                RadioState.denied -> PortErrorKind.denied
                RadioState.unsupported -> PortErrorKind.unsupported
                else -> PortErrorKind.notReady
            }

        fun gattStatus(status: Int) = PlatformCode("AndroidGattStatus", status.toString())

        fun bluetoothStatus(status: Int) = PlatformCode("AndroidBluetoothStatus", status.toString())
    }
}
