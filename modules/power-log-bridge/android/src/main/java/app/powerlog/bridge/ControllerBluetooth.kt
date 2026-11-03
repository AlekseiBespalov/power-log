@file:Suppress("MissingPermission", "DEPRECATION")

package app.powerlog.bridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import app.powerlog.core.presentation.Failures
import app.powerlog.core.presentation.RideFailure
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
    val diagnosticLog = TelemetryDiagnosticLog(java.io.File(context.noBackupFilesDir, "diagnostics"))
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val session = TelemetrySession(CycControllerDecoder())
    private val bluetoothTrace = BluetoothTrace()
    private var gatt: BluetoothGatt? = null
    private var established = false
    private var writer: BluetoothGattCharacteristic? = null
    private var desired: String? = null
    private var selected: BluetoothDevice? = null
    private val knownControllers = mutableMapOf<String, ControllerIdentity>()
    private var recoveryError: String? = null
    private var connectionError: String? = null
    private var connectionErrorCode: String? = null
    private var storageError: String? = null
    val deviceId: String?
        get() = desired

    private var epoch = UUID.randomUUID().toString()
    private var retries = 0
    private var lastSample = 0L
    private var stableSince: Double? = null
    private var pending: ControllerRequest? = null
    private var pendingSince: Double? = null
    private var unansweredSince: Double? = null
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

    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val diagnosticStarted = now()
    private var metrics = TelemetryDiagnosticMetrics(diagnosticStarted)
    private var background = false
    private var maxQueueDelaySeconds = 0.0
    private var maxStorageWriteMs = 0.0

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.post {
                        val adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        when (adapterState) {
                            BluetoothAdapter.STATE_OFF -> "poweredOff"
                            BluetoothAdapter.STATE_ON -> "poweredOn"
                            else -> null
                        }?.let { note(TelemetryDiagnosticEvent.bluetoothState, fields = mapOf("bluetoothState" to it)) }
                        when (adapterState) {
                            BluetoothAdapter.STATE_TURNING_OFF,
                            BluetoothAdapter.STATE_OFF -> {
                                stopScan(publishIdle = false)
                                session.radioUnavailable()
                                gatt?.let(::release)
                                clearRequests()
                                if (desired != null) {
                                    recoveryError = RideFailure.bluetoothOff.androidText
                                    publish("reconnecting", recoveryError)
                                }
                                schedule()
                            }
                            BluetoothAdapter.STATE_ON ->
                                if (desired != null && gatt == null) {
                                    if (recoveryError != null) recoveryError = RideFailure.bikeReconnecting.androidText
                                    begin()
                                }
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        note(TelemetryDiagnosticEvent.engineStarted)
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
        trace("capture_started") { "platform=android; status=${state["status"]}; requests=111,50" }
    }

    fun stopBluetoothTrace() = bluetoothTrace.stop()

    fun clearBluetoothTrace() = bluetoothTrace.clear()

    fun readBluetoothTrace(): String = bluetoothTrace.exportJson(now())

    private inline fun trace(event: String, bytes: ByteArray? = null, detail: () -> String) {
        if (bluetoothTrace.status(now()).capturing) bluetoothTrace.record(now(), event, detail(), bytes)
    }

    private fun note(
        event: TelemetryDiagnosticEvent,
        reason: TelemetryDiagnosticReason? = null,
        fields: Payload = emptyMap(),
    ) {
        val at = now()
        val data =
            mutableMapOf(
                "status" to state["status"],
                "requestedHz" to hz,
                "sampleCount" to metrics.sampleCount,
                "connectionAttempts" to metrics.connectionAttempts,
                "reconnects" to metrics.reconnects,
                "requestTimeouts" to metrics.requestTimeouts,
                "decoderDiscardedBytes" to session.discardedBytes,
                "linkSamples" to metrics.linkSamples,
                "background" to background,
                "connectionGeneration" to session.state.generation,
                "transportStage" to session.state.stage.name,
                "connectionSeconds" to metrics.connectionSeconds(at),
                "lastSampleAgeSeconds" to metrics.sampleAge(at),
                "request" to pending?.takeIf { pendingSince != null }?.name,
                "requestAgeSeconds" to pendingSince?.let { maxOf(0.0, at - it) },
                "writeBlockedSeconds" to writeBlockedAt?.let { maxOf(0.0, at - it) },
                "reason" to reason?.wire,
            )
        data.putAll(fields)
        trace(event.wire) {
            "stage=${session.state.stage.name}; reason=${reason?.wire ?: "none"}; " +
                "rejection=${fields["rejection"] ?: "none"}; error=${fields["errorCode"] ?: 0}"
        }
        diagnosticLog.append(event, maxOf(0.0, at - diagnosticStarted), data)
    }

    private fun summarizeIfDue() {
        val at = now()
        if (desired == null || at < metrics.nextSummary) return
        val fields =
            mutableMapOf<String, Any?>(
                "pendingBytes" to session.pendingBytes,
                "queueDelaySeconds" to maxQueueDelaySeconds,
                "storageWriteMs" to maxStorageWriteMs,
                "recentSampleHz" to metrics.recentSampleHz(at),
                "cpuSeconds" to android.os.Process.getElapsedCpuTime() / 1000.0,
                "interactive" to power?.isInteractive,
                "powerSave" to power?.isPowerSaveMode,
                "deviceIdle" to power?.isDeviceIdleMode,
                "thermalStatus" to if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
            )
        fields.putAll(metrics.latency())
        note(TelemetryDiagnosticEvent.telemetrySummary, fields = fields)
        metrics.resetWindow(at)
        maxQueueDelaySeconds = 0.0
        maxStorageWriteMs = 0.0
    }

    fun setBackground(value: Boolean) {
        if (background == value) return
        background = value
        note(TelemetryDiagnosticEvent.backgroundChanged)
        repace()
    }

    fun recordingResult(failure: String?) {
        note(
            TelemetryDiagnosticEvent.recordingError,
            TelemetryDiagnosticReason.storageFailure,
            mapOf("recordingFailed" to (failure != null)),
        )
        storageError = failure?.let { Failures.present(it).androidText }
        publish(state.str("status"), connectionError, connectionErrorCode)
    }

    private fun requestDisconnect(reason: TelemetryDiagnosticReason) {
        metrics.requestedCancellation(reason, now())
        note(
            TelemetryDiagnosticEvent.disconnectRequested,
            metrics.cancellationReason ?: reason,
            mapOf("initiator" to "app"),
        )
    }

    fun setHz(value: Int) {
        BridgeInputs.sampleHz(value)
        hz = value
        repace()
    }

    private fun pollIntervalMillis() =
        (TelemetryTiming.pollIntervalSeconds(hz.toDouble(), background, recording()) * 1000).roundToLong()

    private fun repace() {
        nextPollAt =
            maxOf(
                SystemClock.elapsedRealtime(),
                ((pendingSince ?: lastSample.takeIf { it > 0 }?.div(1000.0) ?: now()) * 1000).roundToLong() +
                    pollIntervalMillis(),
            )
        schedule()
    }

    private fun bluetoothFailure(): RideFailure? {
        val adapter = manager?.adapter ?: return RideFailure.bluetoothUnsupported
        return RideFailure.bluetoothOff.takeUnless { adapter.isEnabled }
    }

    fun startScan() {
        bluetoothFailure()?.let { throw RideFailureException(it) }
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
                        trace("advertisement", result.scanRecord?.bytes) {
                            "name=${result.scanRecord?.deviceName}; rssi=${result.rssi}; services=${result.scanRecord?.serviceUuids}"
                        }
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
                        note(
                            TelemetryDiagnosticEvent.transportError,
                            TelemetryDiagnosticReason.platformError,
                            mapOf("errorCode" to code),
                        )
                        publish("error", RideFailure.bluetoothSearchFailed)
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

    private fun expireScan() {
        val found = devices.isNotEmpty()
        stopScan(publishIdle = found)
        if (!found && state["status"] == "scanning") publish("error", RideFailure.bikeNotFound)
    }

    fun validateConnect(id: String, frequency: Int) {
        BridgeInputs.sampleHz(frequency)
        require(BluetoothAdapter.checkBluetoothAddress(id)) { "Invalid local Bluetooth device identifier." }
        check(desired == null) { "Disconnect the current controller before connecting again." }
        if (session.state.cancellationPending) throw RideFailureException(RideFailure.bluetoothNotReady)
        bluetoothFailure()?.let { throw RideFailureException(it) }
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
        metrics = TelemetryDiagnosticMetrics(now())
        note(TelemetryDiagnosticEvent.connectRequested)
        begin()
        completion(null)
    }

    private fun publish(status: String, failure: RideFailure) = publish(status, failure.androidText, failure.name)

    private fun publish(status: String, error: String? = null, errorCode: String? = null) {
        connectionError = error
        connectionErrorCode = errorCode
        val identity = selected?.address?.let { knownControllers[it] }
        state =
            mapOf(
                "status" to status,
                "deviceId" to selected?.address,
                "deviceName" to selected?.let { runCatching { it.name }.getOrNull() ?: "Bike" },
                "controllerModel" to identity?.controllerModel,
                "firmwareLabel" to identity?.firmwareLabel,
                "controllerProtocol" to identity?.controllerProtocol,
                "error" to (storageError ?: error),
                "errorCode" to errorCode?.takeIf { storageError == null && error != null },
                "recoverableConnectionError" to (status == "reconnecting" && storageError == null && error != null),
            )
        emit("onState", state)
    }

    private fun begin() {
        if (desired == null || !session.begin(now(), false)) return
        val attempt = session.state.generation
        metrics.beginAttempt(now())
        clearRequests()
        stableSince = null
        publish(if (retries == 0) "connecting" else "reconnecting", recoveryError)
        note(TelemetryDiagnosticEvent.connectionAttempt, fields = mapOf("systemReconnect" to false))
        try {
            val adapter = manager?.adapter
            if (adapter?.isEnabled != true) {
                failed(TelemetryDiagnosticReason.bluetoothUnavailable, failure = RideFailure.bluetoothOff)
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
                                    failed(RideFailure.bluetoothDenied, TelemetryDiagnosticReason.bluetoothUnavailable)
                                } catch (_: Exception) {
                                    failed(TelemetryDiagnosticReason.platformError)
                                }
                                schedule()
                            }
                        }

                        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                handler.post {
                                    if (gatt !== connection) return@post
                                    val lost =
                                        if (established) TelemetryDiagnosticReason.linkDisconnected
                                        else TelemetryDiagnosticReason.connectionFailed
                                    note(
                                        if (established) TelemetryDiagnosticEvent.disconnected
                                        else TelemetryDiagnosticEvent.connectionFailed,
                                        fields = metrics.disconnected(now(), status, lost),
                                    )
                                    if (session.state.cancellationPending) {
                                        session.disconnected(attempt, now())
                                        if (!session.state.cancellationPending) {
                                            release(connection)
                                            if (session.state.cancellationFailed && desired != null)
                                                handle(TelemetryTransportAction.cancellationTimeout)
                                            else schedule()
                                        }
                                    } else if (attempt == session.state.generation) {
                                        failed(lost, peer = true, error = gattError(status))
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
                                        requestDisconnect(TelemetryDiagnosticReason.pendingReconnect)
                                        runCatching { connection.disconnect() }
                                        schedule()
                                    }
                                }
                                event(connection) {
                                    if (status != BluetoothGatt.GATT_SUCCESS)
                                        failed(TelemetryDiagnosticReason.connectionFailed, error = gattError(status))
                                    else if (
                                        newState == BluetoothProfile.STATE_CONNECTED &&
                                            advance(attempt, TelemetryStage.connecting, TelemetryStage.services)
                                    ) {
                                        established = true
                                        metrics.connected(now())
                                        note(TelemetryDiagnosticEvent.linkConnected)
                                        if (!connection.discoverServices())
                                            failed(TelemetryDiagnosticReason.serviceDiscovery)
                                    }
                                }
                            }
                        }

                        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) =
                            event(connection) {
                                for (service in connection.services) {
                                    trace("service") { service.uuid.toString() }
                                    for (characteristic in service.characteristics) {
                                        trace("characteristic") {
                                            "service=${service.uuid}; uuid=${characteristic.uuid}; properties=${characteristic.properties}"
                                        }
                                    }
                                }
                                if (session.state.stage != TelemetryStage.services) return@event
                                if (status != BluetoothGatt.GATT_SUCCESS) {
                                    failed(TelemetryDiagnosticReason.serviceDiscovery, error = gattError(status))
                                    return@event
                                }
                                if (!advance(attempt, TelemetryStage.services, TelemetryStage.characteristics))
                                    return@event
                                note(TelemetryDiagnosticEvent.transportStage)
                                val service = connection.getService(UUID.fromString(ControllerGatt.SERVICE))
                                writer = service?.getCharacteristic(UUID.fromString(ControllerGatt.WRITE))
                                val notify = service?.getCharacteristic(UUID.fromString(ControllerGatt.NOTIFY))
                                val descriptor =
                                    notify?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                if (writer == null || notify == null || descriptor == null) {
                                    val reason =
                                        if (service == null) TelemetryDiagnosticReason.serviceDiscovery
                                        else TelemetryDiagnosticReason.characteristicDiscovery
                                    if (
                                        TelemetryReconnectPolicy.retriesDiscovery(false, recording(), knownController())
                                    )
                                        failed(reason)
                                    else failed(RideFailure.controllerUnsupported, reason)
                                    return@event
                                }
                                if (!advance(attempt, TelemetryStage.characteristics, TelemetryStage.notifications))
                                    return@event
                                note(TelemetryDiagnosticEvent.transportStage)
                                if (!connection.setCharacteristicNotification(notify, true)) {
                                    failed(TelemetryDiagnosticReason.notificationSetup)
                                    return@event
                                }
                                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                val result =
                                    if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, value)
                                    else {
                                        descriptor.value = value
                                        if (connection.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS
                                        else BluetoothStatusCodes.ERROR_UNKNOWN
                                    }
                                nativeWritePending = result == BluetoothStatusCodes.SUCCESS
                                if (!nativeWritePending)
                                    failed(
                                        TelemetryDiagnosticReason.notificationSetup,
                                        error = bluetoothStatusError(result),
                                    )
                            }

                        override fun onDescriptorWrite(
                            connection: BluetoothGatt,
                            descriptor: BluetoothGattDescriptor,
                            status: Int,
                        ) =
                            event(connection) {
                                if (session.state.stage != TelemetryStage.notifications) return@event
                                nativeWritePending = false
                                if (status != BluetoothGatt.GATT_SUCCESS)
                                    failed(TelemetryDiagnosticReason.notificationSetup, error = gattError(status))
                                else if (advance(attempt, TelemetryStage.notifications, TelemetryStage.identity)) {
                                    note(TelemetryDiagnosticEvent.transportStage)
                                    send(ControllerRequest.identity)
                                }
                            }

                        override fun onCharacteristicWrite(
                            connection: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int,
                        ) =
                            event(connection) {
                                if (characteristic.uuid.toString() != ControllerGatt.WRITE) return@event
                                nativeWritePending = false
                                if (status != BluetoothGatt.GATT_SUCCESS)
                                    failed(TelemetryDiagnosticReason.writeFailed, error = gattError(status))
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
            failed(RideFailure.bluetoothDenied, TelemetryDiagnosticReason.bluetoothUnavailable)
        } catch (_: Exception) {
            failed(TelemetryDiagnosticReason.platformError)
        }
        schedule()
    }

    private fun advance(generation: Long, expected: TelemetryStage, next: TelemetryStage): Boolean {
        val action = session.advance(generation, expected, next, now())
        handle(action)
        return action == TelemetryTransportAction.advanced
    }

    private fun knownController() = selected?.address?.let { it in knownControllers } == true

    private fun handle(action: TelemetryTransportAction): Boolean {
        if (action != TelemetryTransportAction.none)
            trace("transport") { "action=${action.name}; stage=${session.state.stage.name}" }
        if (
            action == TelemetryTransportAction.responseTimeout &&
                pending == ControllerRequest.identity &&
                !TelemetryReconnectPolicy.retriesIdentity(retries + 1, recording(), knownController())
        ) {
            failed(RideFailure.controllerNoReply, TelemetryDiagnosticReason.responseTimeout)
            return true
        }
        val reason =
            when (action) {
                TelemetryTransportAction.attemptTimeout -> TelemetryDiagnosticReason.connectionDeadline
                TelemetryTransportAction.responseTimeout -> TelemetryDiagnosticReason.responseTimeout
                TelemetryTransportAction.replyExpired -> TelemetryDiagnosticReason.expiredReply
                TelemetryTransportAction.writeTimeout -> TelemetryDiagnosticReason.writeReadyTimeout
                TelemetryTransportAction.cancellationTimeout -> TelemetryDiagnosticReason.cancellationDeadline
                else -> return false
            }
        if (action == TelemetryTransportAction.cancellationTimeout) {
            note(TelemetryDiagnosticEvent.transportError, reason)
            gatt?.let(::release)
            session.state.cancellationGeneration?.let { session.disconnected(it, now()) }
            session.manualDisconnect(false)
            clearRequests()
            if (desired != null) begin() else publish("idle")
        } else failed(reason)
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
            trace("write", bytes) { "request=${read.name}; characteristic=${characteristic.uuid}; writeType=$type" }
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
                if (unansweredSince == null) unansweredSince = sentAt
                writeBlockedAt = null
                if (read == ControllerRequest.telemetry) nextPollAt = sentAtMillis + pollIntervalMillis()
            } else if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                if (writeBlockedAt == null) writeBlockedAt = sentAt
            } else failed(TelemetryDiagnosticReason.writeFailed, error = bluetoothStatusError(result))
        } catch (_: SecurityException) {
            failed(RideFailure.bluetoothDenied, TelemetryDiagnosticReason.bluetoothUnavailable)
        } catch (_: Exception) {
            failed(TelemetryDiagnosticReason.platformError)
        }
        schedule()
    }

    private fun resend() {
        val request = pending ?: return
        note(TelemetryDiagnosticEvent.requestResent)
        requestOwnership = null
        pending = null
        pendingSince = null
        send(request)
    }

    private fun receive(bytes: ByteArray, receipt: RecordingClockReading, owner: ControllerRequestOwnership?) {
        trace("notification", bytes) {
            "characteristic=${ControllerGatt.NOTIFY}; stage=${session.state.stage.name}; request=${owner?.role?.name}"
        }
        if (owner == null || owner !== requestOwnership) return
        val receivedAt = receipt.monotonicMillis
        val decision = session.notifications(owner.generation, owner.role, bytes, owner.sentAt, receivedAt / 1000.0)
        trace("decoder") {
            "action=${decision.action.name}; discardedBytes=${decision.discardedBytes}; pendingBytes=${decision.pendingBytes}"
        }
        for (reply in decision.replies.filterIsInstance<ControllerRejectedReply>()) trace("rejected") {
            "request=${reply.kind.name}; reason=${reply.reason}"
        }
        val rejection = decision.replies.filterIsInstance<ControllerRejectedReply>().firstOrNull()
        rejection?.let { note(TelemetryDiagnosticEvent.transportError, fields = mapOf("rejection" to it.reason)) }
        if (decision.action == TelemetryTransportAction.replyRejected && rejection != null) {
            failed(
                Failures.controllerRejection(rejection.kind),
                if (rejection.kind == ControllerRequest.identity) TelemetryDiagnosticReason.identityRejected
                else TelemetryDiagnosticReason.telemetryRejected,
            )
            return
        }
        if (handle(decision.action)) return
        if (decision.action != TelemetryTransportAction.replyAccepted) return
        requestOwnership = null
        pending = null
        pendingSince = null
        unansweredSince = null
        when (val reply = decision.replies.single()) {
            is ControllerIdentityReply -> {
                selected?.address?.let { knownControllers[it] = reply.identity }
                if (!advance(session.state.generation, TelemetryStage.identity, TelemetryStage.ready)) return
                epoch = UUID.randomUUID().toString()
                publish(state.str("status"), recoveryError)
                note(TelemetryDiagnosticEvent.identityVerified)
                send(ControllerRequest.telemetry)
            }
            is ControllerReadingReply -> {
                val at = receivedAt / 1000.0
                if (stableSince == null) stableSince = at
                if (TelemetryReconnectPolicy.restartsBackoff(at - checkNotNull(stableSince))) retries = 0
                lastSample = receivedAt
                if (state["status"] != "connected") {
                    recoveryError = null
                    publish("connected")
                }
                metrics.receivedSample(at, at - owner.sentAt)?.let {
                    note(TelemetryDiagnosticEvent.telemetryGap, fields = mapOf("gapSeconds" to it))
                }
                val storageStarted = SystemClock.elapsedRealtimeNanos()
                sample(reply.reading, epoch, receipt)
                maxStorageWriteMs =
                    maxOf(maxStorageWriteMs, (SystemClock.elapsedRealtimeNanos() - storageStarted) / 1_000_000.0)
                summarizeIfDue()
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
        unansweredSince = null
        writeBlockedAt = null
        writer = null
        nextPollAt = 0L
    }

    private fun release(connection: BluetoothGatt) {
        if (gatt === connection) {
            gatt = null
            established = false
            metrics.closed()
        }
        runCatching { connection.close() }
    }

    private fun failed(failure: RideFailure, reason: TelemetryDiagnosticReason) =
        failed(reason, terminal = true, failure = failure)

    private fun failed(
        reason: TelemetryDiagnosticReason,
        peer: Boolean = false,
        terminal: Boolean = false,
        error: Payload = emptyMap(),
        failure: RideFailure = RideFailure.bikeUnavailable,
    ) {
        val at = now()
        if (reason == TelemetryDiagnosticReason.responseTimeout || reason == TelemetryDiagnosticReason.expiredReply)
            metrics.timedOut()
        note(TelemetryDiagnosticEvent.transportError, reason, error)
        if (gatt != null && !peer) requestDisconnect(reason)
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
            publish("error", failure)
            note(
                TelemetryDiagnosticEvent.sessionStopped,
                if (plan == null && !terminal) TelemetryDiagnosticReason.retryLimit else reason,
            )
        } else {
            session.scheduleRetry(plan, at)
            retries = (retries + 1).coerceAtMost(6)
            recoveryError =
                (if (reason == TelemetryDiagnosticReason.bluetoothUnavailable) failure
                    else RideFailure.bikeReconnecting)
                    .androidText
            metrics.scheduledReconnect()
            publish("reconnecting", recoveryError)
            note(
                TelemetryDiagnosticEvent.reconnectScheduled,
                reason,
                mapOf("retryAttempt" to retries, "retryDelaySeconds" to plan.delaySeconds),
            )
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
                TelemetryTiming.responseDeadline(transport.stage, pendingSince, unansweredSince),
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
        val due = at + wait / 1000.0
        timer =
            Runnable {
                maxQueueDelaySeconds = maxOf(maxQueueDelaySeconds, now() - due)
                summarizeIfDue()
                if (discovery?.expired(now()) == true) expireScan()
                when (val action = session.check(now(), pendingSince, writeBlockedAt, false, unansweredSince)) {
                    TelemetryTransportAction.beginRetry -> begin()
                    TelemetryTransportAction.resendRequest -> resend()
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
        val active = desired != null || gatt != null
        stopScan(publishIdle = false)
        if (gatt != null) requestDisconnect(TelemetryDiagnosticReason.manualDisconnect)
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
        if (active) note(TelemetryDiagnosticEvent.sessionStopped, TelemetryDiagnosticReason.manualDisconnect)
        schedule()
    }

    fun diagnostics(): Payload =
        mapOf(
            "status" to state["status"],
            "requestedHz" to hz,
            "connectionAttempts" to metrics.connectionAttempts,
            "reconnects" to metrics.reconnects,
            "recentSampleHz" to metrics.recentSampleHz(now()),
            "lastSampleAgeSeconds" to metrics.sampleAge(now()),
            "lastGapSeconds" to metrics.lastGapSeconds,
        )
}
