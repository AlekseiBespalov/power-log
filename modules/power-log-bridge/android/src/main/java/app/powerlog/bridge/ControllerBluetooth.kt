@file:Suppress("MissingPermission", "DEPRECATION")

package app.powerlog.bridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycControllerDecoder
import java.util.UUID
import kotlin.math.roundToLong

internal object ControllerGatt {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
}

internal class ControllerRequestOwnership(val generation: Long, val role: ControllerRequest, val sentAt: Double)

internal class ControllerBluetooth(
    private val context: Context,
    private val handler: Handler,
    private val emit: (String, Payload) -> Unit,
    private val sample: (TelemetryReading, String, RecordingClockReading) -> Unit,
    private val recording: () -> Boolean,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val session = TelemetrySession(CycControllerDecoder())
    private val bluetoothTrace = BluetoothTrace()
    private var gatt: BluetoothGatt? = null
    private var established = false
    private var writer: BluetoothGattCharacteristic? = null
    private var desired: String? = null
    private var selected: BluetoothDevice? = null
    private val knownControllers = mutableMapOf<String, ControllerIdentity>()
    private var recoveryError: String? = null
    val deviceId: String?
        get() = desired

    private var epoch = UUID.randomUUID().toString()
    private var retries = 0
    private var lastSample = 0L
    private var stableSince: Double? = null
    private var pending: ControllerRequest? = null
    private var pendingSince: Double? = null
    private var writeBlockedAt: Double? = null
    private var nativeWritePending = false
    @Volatile private var requestOwnership: ControllerRequestOwnership? = null
    private var nextPollAt = 0L
    private var timer: Runnable? = null
    private var scanGeneration = 0L
    private var discovery: TelemetryDiscovery? = null
    private var scanner: ScanCallback? = null
    var hz = 2
        private set

    var state: Payload = mapOf("status" to "idle")
        private set

    private var attempts = 0
    private val devices = mutableMapOf<String, BluetoothDevice>()

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.post {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_TURNING_OFF,
                            BluetoothAdapter.STATE_OFF -> {
                                stopScan(publishIdle = false)
                                session.radioUnavailable()
                                gatt?.let(::release)
                                clearRequests()
                                if (desired != null) {
                                    recoveryError = "Bluetooth is off."
                                    publish("reconnecting", recoveryError)
                                }
                                schedule()
                            }
                            BluetoothAdapter.STATE_ON -> if (desired != null && gatt == null) begin()
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun now() = SystemClock.elapsedRealtime() / 1000.0

    fun bluetoothTraceState(): Payload {
        val state = bluetoothTrace.status(now())
        return mapOf(
            "capturing" to state.capturing,
            "eventCount" to state.eventCount,
            "byteCount" to state.byteCount,
            "stopReason" to state.stopReason,
            "lastEvent" to state.lastEvent,
        )
    }

    fun startBluetoothTrace() {
        bluetoothTrace.start(now())
        trace("capture_started", "platform=android; status=${state["status"]}; requests=111,50")
    }

    fun stopBluetoothTrace() = bluetoothTrace.stop()

    fun clearBluetoothTrace() = bluetoothTrace.clear()

    fun readBluetoothTrace(): String = bluetoothTrace.exportJson(now())

    private fun trace(event: String, detail: String, bytes: ByteArray? = null) =
        bluetoothTrace.record(now(), event, detail, bytes)

    fun setHz(value: Int) {
        BridgeInputs.sampleHz(value)
        nextPollAt =
            maxOf(
                SystemClock.elapsedRealtime(),
                ((pendingSince ?: lastSample.takeIf { it > 0 }?.div(1000.0) ?: now()) * 1000).roundToLong() +
                    1000L / value,
            )
        hz = value
        schedule()
    }

    fun startScan() {
        check(manager?.adapter?.isEnabled == true) { "Turn on Bluetooth to find your bike." }
        stopScan(publishIdle = false)
        devices.clear()
        val window = TelemetryDiscovery.begin(++scanGeneration, now())
        discovery = window
        val callback =
            object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val receivedAt = now()
                    handler.post {
                        if (
                            !window.accepts(discovery?.generation ?: return@post, receivedAt) ||
                                !window.accepts(scanGeneration, now())
                        )
                            return@post
                        val device = result.device
                        trace(
                            "advertisement",
                            "name=${result.scanRecord?.deviceName}; rssi=${result.rssi}; services=${result.scanRecord?.serviceUuids}",
                            result.scanRecord?.bytes,
                        )
                        devices[device.address] = device
                        emit(
                            "onDevice",
                            mapOf(
                                "id" to device.address,
                                "name" to
                                    (runCatching { device.name }.getOrNull()
                                        ?: result.scanRecord?.deviceName
                                        ?: "Bike"),
                                "rssi" to result.rssi,
                            ),
                        )
                    }
                }

                override fun onScanFailed(code: Int) {
                    handler.post {
                        if (!window.accepts(discovery?.generation ?: return@post, now())) return@post
                        stopScan(publishIdle = false)
                        publish("error", "Bluetooth scan failed ($code). Try again.")
                    }
                }
            }
        scanner = callback
        publish("scanning")
        manager.adapter.bluetoothLeScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(ControllerGatt.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        schedule()
    }

    fun stopScan(publishIdle: Boolean = true) {
        scanner?.let { runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanner = null
        discovery = null
        if (publishIdle && state["status"] == "scanning") publish("idle")
        schedule()
    }

    fun validateConnect(id: String, frequency: Int) {
        BridgeInputs.sampleHz(frequency)
        require(BluetoothAdapter.checkBluetoothAddress(id)) { "Invalid local Bluetooth device identifier." }
        check(desired == null) { "Disconnect the current controller before connecting again." }
        check(!session.state.cancellationPending) { "Wait for Bluetooth cancellation to complete." }
        check(manager?.adapter?.isEnabled == true) { "Bluetooth must be powered on; scan first to request permission." }
    }

    fun connect(id: String, frequency: Int, completion: (Throwable?) -> Unit) {
        validateConnect(id, frequency)
        if (!recording()) setHz(frequency)
        stopScan(publishIdle = false)
        selected = devices[id] ?: manager!!.adapter.getRemoteDevice(id)
        desired = id
        retries = 0
        recoveryError = null
        session.manualDisconnect(false)
        begin()
        completion(null)
    }

    private fun publish(status: String, error: String? = null) {
        val identity = selected?.address?.let { knownControllers[it] }
        state =
            mapOf(
                "status" to status,
                "deviceId" to selected?.address,
                "deviceName" to selected?.let { runCatching { it.name }.getOrNull() ?: "Bike" },
                "controllerModel" to identity?.controllerModel,
                "firmwareLabel" to identity?.firmwareLabel,
                "error" to error,
                "recoverableConnectionError" to (status == "reconnecting" && error != null),
            )
        emit("onState", state)
    }

    private fun begin() {
        if (desired == null || !session.begin(now(), false)) return
        val attempt = session.state.generation
        attempts++
        clearRequests()
        stableSince = null
        publish(if (retries == 0) "connecting" else "reconnecting", recoveryError)
        try {
            val adapter = manager?.adapter
            if (adapter?.isEnabled != true) {
                failed("Bluetooth is off.")
                return
            }
            val device = devices[desired] ?: adapter.getRemoteDevice(desired)
            gatt =
                device.connectGatt(
                    context,
                    false,
                    object : BluetoothGattCallback() {
                        private fun event(connection: BluetoothGatt, body: () -> Unit) {
                            handler.post {
                                if (
                                    gatt !== connection ||
                                        attempt != session.state.generation ||
                                        session.state.cancellationPending ||
                                        session.state.reconnectDue != null
                                )
                                    return@post
                                if (handle(session.check(now(), null, writeBlockedAt))) return@post
                                try {
                                    body()
                                } catch (_: SecurityException) {
                                    failed("Allow Nearby devices to reconnect your bike.", terminal = true)
                                } catch (_: Exception) {
                                    failed("Bluetooth could not read the bike. Reconnecting…")
                                }
                                schedule()
                            }
                        }

                        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                handler.post {
                                    if (gatt !== connection) return@post
                                    if (session.state.cancellationPending) {
                                        session.disconnected(attempt, now())
                                        if (!session.state.cancellationPending) {
                                            release(connection)
                                            if (session.state.cancellationFailed && desired != null)
                                                handle(TelemetryTransportAction.cancellationTimeout)
                                            else schedule()
                                        }
                                    } else if (attempt == session.state.generation) {
                                        failed("Bike disconnected ($status).", peer = true)
                                    }
                                }
                            } else {
                                handler.post {
                                    if (
                                        gatt === connection &&
                                            session.state.reconnectDue != null &&
                                            newState == BluetoothProfile.STATE_CONNECTED
                                    ) {
                                        session.cancelDuringBackoff()
                                        runCatching { connection.disconnect() }
                                        schedule()
                                    }
                                }
                                event(connection) {
                                    if (status != BluetoothGatt.GATT_SUCCESS)
                                        failed("Bike connection failed ($status).")
                                    else if (
                                        newState == BluetoothProfile.STATE_CONNECTED &&
                                            advance(attempt, TelemetryStage.connecting, TelemetryStage.services)
                                    ) {
                                        established = true
                                        if (!connection.discoverServices()) failed("Could not discover bike services.")
                                    }
                                }
                            }
                        }

                        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) =
                            event(connection) {
                                for (service in connection.services) {
                                    trace("service", service.uuid.toString())
                                    for (characteristic in service.characteristics) {
                                        trace(
                                            "characteristic",
                                            "service=${service.uuid}; uuid=${characteristic.uuid}; properties=${characteristic.properties}",
                                        )
                                    }
                                }
                                if (session.state.stage != TelemetryStage.services) return@event
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    failed("Bike service discovery failed ($status).")
                                    return@event
                                }
                                if (!advance(attempt, TelemetryStage.services, TelemetryStage.characteristics))
                                    return@event
                                val service = connection.getService(UUID.fromString(ControllerGatt.SERVICE))
                                writer = service?.getCharacteristic(UUID.fromString(ControllerGatt.WRITE))
                                val notify = service?.getCharacteristic(UUID.fromString(ControllerGatt.NOTIFY))
                                val descriptor =
                                    notify?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                if (writer == null || notify == null || descriptor == null) {
                                    failed(
                                        "This bike does not provide the controller telemetry service.",
                                        terminal = true,
                                    )
                                    return@event
                                }
                                if (!advance(attempt, TelemetryStage.characteristics, TelemetryStage.notifications))
                                    return@event
                                if (!connection.setCharacteristicNotification(notify, true)) {
                                    failed("Could not enable bike notifications.")
                                    return@event
                                }
                                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                val success =
                                    if (Build.VERSION.SDK_INT >= 33)
                                        connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                                    else {
                                        descriptor.value = value
                                        connection.writeDescriptor(descriptor)
                                    }
                                nativeWritePending = success
                                if (!success) failed("Could not subscribe to bike notifications.")
                            }

                        override fun onDescriptorWrite(
                            connection: BluetoothGatt,
                            descriptor: BluetoothGattDescriptor,
                            status: Int,
                        ) =
                            event(connection) {
                                if (session.state.stage != TelemetryStage.notifications) return@event
                                nativeWritePending = false
                                if (status != BluetoothGatt.GATT_SUCCESS) failed("Bike notifications failed ($status).")
                                else if (advance(attempt, TelemetryStage.notifications, TelemetryStage.identity))
                                    send(ControllerRequest.identity)
                            }

                        override fun onCharacteristicWrite(
                            connection: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int,
                        ) =
                            event(connection) {
                                if (characteristic.uuid.toString() != ControllerGatt.WRITE) return@event
                                nativeWritePending = false
                                if (status != BluetoothGatt.GATT_SUCCESS) failed("Bike request failed ($status).")
                                else if (writeBlockedAt != null) pending?.let(::send)
                            }

                        override fun onCharacteristicChanged(
                            connection: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            value: ByteArray,
                        ) {
                            val owner = requestOwnership
                            val receipt = SystemRecordingClock.read()
                            val notification = value.copyOf()
                            event(connection) {
                                if (characteristic.uuid.toString() == ControllerGatt.NOTIFY)
                                    receive(notification, receipt, owner)
                            }
                        }

                        override fun onCharacteristicChanged(
                            connection: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                        ) {
                            if (Build.VERSION.SDK_INT < 33)
                                onCharacteristicChanged(connection, characteristic, characteristic.value)
                        }
                    },
                    BluetoothDevice.TRANSPORT_LE,
                )
        } catch (_: SecurityException) {
            failed("Allow Nearby devices to reconnect your bike.", terminal = true)
        } catch (_: Exception) {
            failed("Bluetooth could not connect. Try again.")
        }
        schedule()
    }

    private fun advance(generation: Long, expected: TelemetryStage, next: TelemetryStage): Boolean {
        val action = session.advance(generation, expected, next, now())
        handle(action)
        return action == TelemetryTransportAction.advanced
    }

    private fun handle(action: TelemetryTransportAction): Boolean {
        if (action != TelemetryTransportAction.none)
            trace("transport", "action=${action.name}; stage=${session.state.stage.name}")
        val message =
            when (action) {
                TelemetryTransportAction.attemptTimeout -> "Controller connection or identity handshake timed out."
                TelemetryTransportAction.responseTimeout -> "Bike response timed out."
                TelemetryTransportAction.replyExpired -> "Bike response arrived after its freshness deadline."
                TelemetryTransportAction.writeTimeout -> "Bike write timed out."
                TelemetryTransportAction.replyRejected -> "Bike returned an invalid controller reply."
                TelemetryTransportAction.cancellationTimeout ->
                    "Bluetooth cancellation timed out. Disconnect and try again."
                else -> null
            } ?: return false
        if (action == TelemetryTransportAction.cancellationTimeout) {
            gatt?.let(::release)
            session.state.cancellationGeneration?.let { session.disconnected(it, now()) }
            session.manualDisconnect(false)
            clearRequests()
            if (desired != null) begin() else publish("idle")
        } else failed(message, terminal = action == TelemetryTransportAction.replyRejected)
        return true
    }

    private fun send(read: ControllerRequest) {
        if (handle(session.check(now(), null, writeBlockedAt))) return
        val connection = gatt ?: return
        val characteristic = writer ?: return
        if (pendingSince != null || session.state.stage !in listOf(TelemetryStage.identity, TelemetryStage.ready))
            return
        val sentAtMillis = SystemClock.elapsedRealtime()
        val sentAt = sentAtMillis / 1000.0
        pending = read
        val type =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val bytes = session.request(read)
            trace("write", "request=${read.name}; characteristic=${characteristic.uuid}; writeType=$type", bytes)
            val owner = ControllerRequestOwnership(session.state.generation, read, sentAt)
            // A notification can arrive before the native write call returns.
            requestOwnership = owner
            val result =
                if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(characteristic, bytes, type)
                else {
                    characteristic.writeType = type
                    characteristic.value = bytes
                    // Before API 33 this write reports another GATT operation still in flight only as false.
                    if (connection.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS
                    else if (nativeWritePending) BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                    else BluetoothStatusCodes.ERROR_UNKNOWN
                }
            if (result != BluetoothStatusCodes.SUCCESS) requestOwnership = null
            if (result == BluetoothStatusCodes.SUCCESS) {
                nativeWritePending = true
                pendingSince = sentAt
                writeBlockedAt = null
                if (read == ControllerRequest.telemetry) nextPollAt = sentAtMillis + 1000L / hz
            } else if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                if (writeBlockedAt == null) writeBlockedAt = sentAt
            } else failed("Could not read bike telemetry.")
        } catch (_: SecurityException) {
            failed("Allow Nearby devices to reconnect your bike.", terminal = true)
        } catch (_: Exception) {
            failed("Bluetooth could not read the bike. Reconnecting…")
        }
        schedule()
    }

    private fun receive(bytes: ByteArray, receipt: RecordingClockReading, owner: ControllerRequestOwnership?) {
        trace(
            "notification",
            "characteristic=${ControllerGatt.NOTIFY}; stage=${session.state.stage.name}; request=${owner?.role?.name}",
            bytes,
        )
        if (owner == null || owner !== requestOwnership) return
        val receivedAt = receipt.monotonicMillis
        val decision = session.notifications(owner.generation, owner.role, bytes, owner.sentAt, receivedAt / 1000.0)
        trace(
            "decoder",
            "action=${decision.action.name}; discardedBytes=${decision.discardedBytes}; pendingBytes=${decision.pendingBytes}",
        )
        for (reply in decision.replies.filterIsInstance<ControllerRejectedReply>()) trace(
            "rejected",
            "request=${reply.kind.name}; reason=${reply.reason}",
        )
        if (handle(decision.action)) return
        if (decision.action != TelemetryTransportAction.replyAccepted) return
        requestOwnership = null
        pending = null
        pendingSince = null
        when (val reply = decision.replies.single()) {
            is ControllerIdentityReply -> {
                selected?.address?.let { knownControllers[it] = reply.identity }
                if (!advance(session.state.generation, TelemetryStage.identity, TelemetryStage.ready)) return
                epoch = UUID.randomUUID().toString()
                publish(state.str("status"), recoveryError)
                send(ControllerRequest.telemetry)
            }
            is ControllerReadingReply -> {
                if (stableSince == null) stableSince = receivedAt / 1000.0
                if (receivedAt / 1000.0 - checkNotNull(stableSince) >= TelemetryTiming.stableTelemetrySeconds)
                    retries = 0
                lastSample = receivedAt
                if (state["status"] != "connected") {
                    recoveryError = null
                    publish("connected")
                }
                sample(reply.reading, epoch, receipt)
                if (now() * 1000 >= nextPollAt) send(ControllerRequest.telemetry)
            }
            is ControllerRejectedReply -> error("Rejected reply cannot be admitted")
        }
        schedule()
    }

    private fun clearRequests() {
        requestOwnership = null
        nativeWritePending = false
        pending = null
        pendingSince = null
        writeBlockedAt = null
        writer = null
        nextPollAt = 0L
    }

    private fun release(connection: BluetoothGatt) {
        if (gatt === connection) {
            gatt = null
            established = false
        }
        runCatching { connection.close() }
    }

    private fun failed(message: String, peer: Boolean = false, terminal: Boolean = false) {
        trace("error", message)
        val at = now()
        if (!established) {
            gatt?.let(::release)
            session.state.cancellationGeneration?.let { session.disconnected(it, at) }
        }
        val plan =
            if (terminal) null
            else
                TelemetryReconnectPolicy.plan(
                    (retries + 1).coerceAtMost(7),
                    recording(),
                    peer,
                    peer,
                    stableSince?.let { at - it },
                    false,
                    false,
                    gatt == null || peer,
                )
        val connection = gatt
        clearRequests()
        if (plan == null || desired == null) {
            session.manualDisconnect(connection != null && !peer)
            desired = null
            recoveryError = null
            publish("error", if (terminal) message else "Reconnect limit reached. $message")
        } else {
            session.scheduleRetry(plan, at)
            retries = (retries + 1).coerceAtMost(6)
            recoveryError = message
            publish("reconnecting", message)
        }
        if (connection != null && (peer || plan == null || desired == null)) {
            runCatching { connection.disconnect() }
            release(connection)
            session.state.cancellationGeneration?.let { session.disconnected(it, at) }
        } else if (connection != null) runCatching { connection.disconnect() }
        schedule()
    }

    private fun schedule() {
        timer?.let(handler::removeCallbacks)
        timer = null
        val transport = session.state
        if (
            discovery == null &&
                desired == null &&
                transport.reconnectDue == null &&
                transport.stage == TelemetryStage.idle
        )
            return
        val at = now()
        val delay =
            TelemetryTiming.pollingDelay(
                at,
                manager?.adapter?.isEnabled == true,
                transport.stage == TelemetryStage.ready,
                discovery != null,
                discovery?.deadline,
                transport.reconnectDue,
                transport.attemptDeadline,
                transport.cancellationPending,
                pendingSince?.plus(TelemetryTiming.telemetryTimeoutSeconds),
                writeBlockedAt?.plus(TelemetryTiming.telemetryTimeoutSeconds),
                nextPollAt
                    .takeIf {
                        transport.stage == TelemetryStage.ready && pendingSince == null && writeBlockedAt == null
                    }
                    ?.div(1000.0),
                null,
            )
        val cancellationDelay = transport.cancellationDeadline?.takeIf { transport.cancellationPending }?.minus(at)
        val wait = (minOf(delay, cancellationDelay ?: delay).coerceAtLeast(0.005) * 1000).roundToLong()
        timer =
            Runnable {
                if (discovery?.expired(now()) == true) stopScan()
                when (val action = session.check(now(), pendingSince, writeBlockedAt)) {
                    TelemetryTransportAction.beginRetry -> begin()
                    TelemetryTransportAction.none -> {
                        if (writeBlockedAt != null) pending?.let(::send)
                        else if (
                            session.state.stage == TelemetryStage.ready &&
                                pendingSince == null &&
                                now() * 1000 >= nextPollAt
                        )
                            send(ControllerRequest.telemetry)
                    }
                    else -> handle(action)
                }
                schedule()
            }
                .also { handler.postDelayed(it, wait) }
    }

    fun connectedTo(id: String) = desired == id && state["status"] == "connected"

    fun disconnect() {
        stopScan(publishIdle = false)
        session.manualDisconnect(gatt != null)
        gatt?.let { connection ->
            runCatching { connection.disconnect() }
            release(connection)
        }
        session.state.cancellationGeneration?.let { session.disconnected(it, now()) }
        session.manualDisconnect(false)
        clearRequests()
        desired = null
        retries = 0
        recoveryError = null
        publish("idle")
        schedule()
    }

    fun diagnostics(): Payload =
        mapOf(
            "status" to state["status"],
            "requestedHz" to hz,
            "connectionAttempts" to attempts,
            "reconnects" to retries,
            "recentSampleHz" to null,
            "lastSampleAgeSeconds" to
                if (lastSample > 0) (SystemClock.elapsedRealtime() - lastSample) / 1000.0 else null,
            "lastGapSeconds" to null,
        )
}
