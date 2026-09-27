@file:Suppress("MissingPermission", "DEPRECATION")

package app.powerlog.bridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import java.util.UUID

internal fun nextTelemetryPollMillis(previousDue: Long, sentAt: Long, hz: Int): Long {
    val period = 1000L / hz
    return if (previousDue == 0L || sentAt - previousDue >= period) sentAt + period else previousDue + period
}

/** All callbacks and timers are confined to the recorder's native looper. */
internal class CycBluetooth(
    private val context: Context,
    private val handler: Handler,
    private val emit: (String, Payload) -> Unit,
    private val sample: (Map<String, Double>, CycProtocol.Identity, String, Long, String) -> Unit,
    private val recording: () -> Boolean,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val decoder = CycProtocol.Decoder()
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var identity: CycProtocol.Identity? = null
    private var desired: String? = null
    private var selected: BluetoothDevice? = null
    private val knownControllers = mutableMapOf<String, CycProtocol.Identity>()
    private var recoveryError: String? = null
    val deviceId: String?
        get() = desired

    private var epoch = UUID.randomUUID().toString()
    private var generation = 0
    private var retries = 0
    private var lastSample = 0L
    private var stableSince = 0L
    private var pending: CycProtocol.Read? = null
    private var pendingSince = 0L
    private var nextPollAt = 0L
    private var connectionWatchdog: Runnable? = null
    private var connectionDeadline: Long? = null
    private var scanDeadline: Runnable? = null
    private var watchdog: Runnable? = null
    private var polling: Runnable? = null
    private var retry: Runnable? = null
    private var scanning = false
    var hz = 2
        private set

    var state: Payload = mapOf("status" to "idle")
        private set

    var sampleCount = 0L
        private set

    private var attempts = 0
    private var timeouts = 0
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val scanner =
        object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                handler.post {
                    if (!scanning) return@post
                    val device = result.device
                    devices[device.address] = device
                    emit(
                        "onDevice",
                        mapOf(
                            "id" to device.address,
                            "name" to
                                (runCatching { device.name }.getOrNull()
                                    ?: result.scanRecord?.deviceName
                                    ?: "CYC bike"),
                            "rssi" to result.rssi,
                        ),
                    )
                }
            }

            override fun onScanFailed(code: Int) {
                handler.post {
                    stopScan(publishIdle = false)
                    publish("error", "Bluetooth scan failed ($code). Try again.")
                }
            }
        }

    fun setHz(value: Int) {
        BridgeInputs.sampleHz(value)
        if (hz != value) nextPollAt = 0
        hz = value
    }

    fun startScan() {
        check(manager?.adapter?.isEnabled == true) { "Turn on Bluetooth to find your bike." }
        stopScan(publishIdle = false)
        devices.clear()
        scanning = true
        publish("scanning")
        manager.adapter.bluetoothLeScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(CycProtocol.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanner,
        )
        scanDeadline = Runnable { stopScan() }.also { handler.postDelayed(it, 20000) }
    }

    fun stopScan(publishIdle: Boolean = true) {
        scanDeadline?.let(handler::removeCallbacks)
        scanDeadline = null
        if (scanning) {
            runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanner) }
            scanning = false
        }
        if (publishIdle && state["status"] == "scanning") publish("idle")
    }

    fun validateConnect(id: String, frequency: Int) {
        BridgeInputs.sampleHz(frequency)
        require(BluetoothAdapter.checkBluetoothAddress(id)) { "Invalid local Bluetooth device identifier." }
        check(desired == null) { "Disconnect the current controller before connecting again." }
        check(manager?.adapter?.isEnabled == true) {
            "Bluetooth must be powered on; scan first to request permission."
        }
    }

    fun connect(id: String, frequency: Int, completion: (Throwable?) -> Unit) {
        validateConnect(id, frequency)
        if (!recording()) setHz(frequency)
        stopScan(publishIdle = false)
        selected = devices[id] ?: manager!!.adapter.getRemoteDevice(id)
        desired = id
        retries = 0
        recoveryError = null
        begin()
        completion(null)
    }

    private fun publish(status: String, error: String? = null) {
        state =
            mapOf(
                "status" to status,
                "deviceId" to selected?.address,
                "deviceName" to selected?.let { runCatching { it.name }.getOrNull() ?: "CYC bike" },
                "controllerModel" to selected?.address?.let { knownControllers[it]?.model },
                "firmwareLabel" to selected?.address?.let { knownControllers[it]?.firmware },
                "error" to error,
                "recoverableConnectionError" to (status == "reconnecting" && error != null),
            )
        emit("onState", state)
    }

    private fun begin() {
        try {
            openGatt()
        } catch (_: SecurityException) {
            failed("Allow Nearby devices to reconnect your bike.", terminal = true)
        } catch (_: Exception) {
            failed("Bluetooth could not connect. Try again.")
        }
    }

    private fun openGatt() {
        val id = desired ?: return
        generation++
        val attempt = generation
        attempts++
        decoder.reset()
        identity = null
        pending = null
        writer = null
        stableSince = 0
        publish(if (retries == 0) "connecting" else "reconnecting", recoveryError)
        val adapter = manager?.adapter
        if (adapter?.isEnabled != true) {
            failed("Bluetooth is off.")
            return
        }
        val device = devices[id] ?: adapter.getRemoteDevice(id)
        connectionDeadline = SystemClock.elapsedRealtime() + 20000
        connectionWatchdog =
            Runnable { if (attempt == generation) expireConnection() }
                .also {
                    handler.postDelayed(it, 20000)
                }
        gatt =
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    private fun event(connection: BluetoothGatt, body: () -> Unit) {
                        handler.post {
                            if (attempt == generation && gatt === connection) {
                                if (expireConnection()) return@post
                                try {
                                    body()
                                } catch (_: SecurityException) {
                                    failed(
                                        "Allow Nearby devices to reconnect your bike.",
                                        terminal = true,
                                    )
                                } catch (_: Exception) {
                                    failed("Bluetooth could not read the bike. Reconnecting…")
                                }
                            }
                        }
                    }

                    override fun onConnectionStateChange(
                        connection: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) =
                        event(connection) {
                            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                                // No competing MTU requests: the frame decoder accepts fragmented
                                // notifications.
                                if (!connection.discoverServices()) failed("Could not discover bike services.")
                            } else if (
                                newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS
                            )
                                failed("Bike disconnected ($status).", true)
                        }

                    override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) =
                        event(connection) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                failed("Bike service discovery failed ($status).")
                                return@event
                            }
                            val service = connection.getService(UUID.fromString(CycProtocol.SERVICE))
                            writer = service?.getCharacteristic(UUID.fromString(CycProtocol.WRITE))
                            val notify = service?.getCharacteristic(UUID.fromString(CycProtocol.NOTIFY))
                            val descriptor =
                                notify?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (writer == null || notify == null || descriptor == null) {
                                failed(
                                    "This bike does not provide the CYC telemetry service.",
                                    terminal = true,
                                )
                                return@event
                            }
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
                            if (!success) failed("Could not subscribe to bike notifications.")
                        }

                    override fun onDescriptorWrite(
                        connection: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int,
                    ) =
                        event(connection) {
                            if (status == BluetoothGatt.GATT_SUCCESS) send(CycProtocol.Read.IDENTITY)
                            else failed("Bike notifications failed ($status).")
                        }

                    override fun onCharacteristicWrite(
                        connection: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) =
                        event(connection) {
                            if (status != BluetoothGatt.GATT_SUCCESS) failed("Bike request failed ($status).")
                        }

                    override fun onCharacteristicChanged(
                        connection: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        val receivedAt = SystemClock.elapsedRealtime()
                        val timestamp = iso()
                        event(connection) {
                            if (characteristic.uuid.toString() == CycProtocol.NOTIFY)
                                receive(value, receivedAt, timestamp)
                        }
                    }

                    override fun onCharacteristicChanged(
                        connection: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        if (Build.VERSION.SDK_INT < 33)
                            onCharacteristicChanged(
                                connection,
                                characteristic,
                                characteristic.value.copyOf(),
                            )
                    }
                },
                BluetoothDevice.TRANSPORT_LE,
            )
    }

    private fun expireConnection(): Boolean {
        val deadline = connectionDeadline ?: return false
        if (SystemClock.elapsedRealtime() < deadline) return false
        timeouts++
        failed("CYC connection or identity handshake timed out.")
        return true
    }

    private fun deadline(millis: Long, message: String, attempt: Int = generation) {
        watchdog?.let(handler::removeCallbacks)
        watchdog =
            Runnable {
                if (attempt == generation) {
                    timeouts++
                    failed(message)
                }
            }
                .also { handler.postDelayed(it, millis) }
    }

    private fun send(read: CycProtocol.Read) {
        try {
            writeRead(read)
        } catch (_: SecurityException) {
            failed("Allow Nearby devices to reconnect your bike.", terminal = true)
        } catch (_: Exception) {
            failed("Bluetooth could not read the bike. Reconnecting…")
        }
    }

    private fun writeRead(read: CycProtocol.Read) {
        val connection = gatt ?: return
        val characteristic = writer ?: return
        if (pending != null) return
        pending = read
        pendingSince = SystemClock.elapsedRealtime()
        if (read == CycProtocol.Read.TELEMETRY) nextPollAt = nextTelemetryPollMillis(nextPollAt, pendingSince, hz)
        deadline(2500, "Bike response timed out.")
        val bytes = CycProtocol.request(read)
        val type =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success =
            if (Build.VERSION.SDK_INT >= 33)
                connection.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = type
                characteristic.value = bytes
                connection.writeCharacteristic(characteristic)
            }
        if (!success) failed("Could not read bike telemetry.")
    }

    private fun receive(bytes: ByteArray, receivedAt: Long, timestamp: String) {
        if (expireConnection()) return
        var identified = false
        for (payload in decoder.feed(bytes)) {
            val command = payload.firstOrNull()?.toInt()?.and(255) ?: continue
            if (pending != null && receivedAt - pendingSince > 2500) {
                timeouts++
                failed("Bike response arrived after its freshness deadline.")
                return
            }
            if (pending == CycProtocol.Read.IDENTITY && command in listOf(0, 111)) {
                try {
                    identity = CycProtocol.identity(payload)
                } catch (error: Exception) {
                    failed(error.message ?: "Unsupported controller", terminal = true)
                    return
                }
                selected?.address?.let { knownControllers[it] = checkNotNull(identity) }
                connectionWatchdog?.let(handler::removeCallbacks)
                connectionDeadline = null
                publish(state.str("status"), recoveryError)
                epoch = UUID.randomUUID().toString()
                pending = null
                watchdog?.let(handler::removeCallbacks)
                identified = true
            } else if (pending == CycProtocol.Read.TELEMETRY && command == 50) {
                val model = identity ?: continue
                val values =
                    try {
                        CycProtocol.telemetry(payload, model)
                    } catch (_: Exception) {
                        continue
                    }
                pending = null
                watchdog?.let(handler::removeCallbacks)
                if (stableSince == 0L) stableSince = receivedAt
                if (receivedAt - stableSince >= 30000) retries = 0
                lastSample = receivedAt
                sampleCount++
                if (state["status"] != "connected") {
                    recoveryError = null
                    publish("connected")
                }
                sample(values, model, epoch, receivedAt, timestamp)
                val wait = maxOf(0L, nextPollAt - SystemClock.elapsedRealtime())
                polling = Runnable { send(CycProtocol.Read.TELEMETRY) }.also { handler.postDelayed(it, wait) }
            }
        }
        // Pre-existing frames in this notification cannot answer a request sent after identity.
        if (identified) send(CycProtocol.Read.TELEMETRY)
    }

    private fun closeGatt() {
        generation++
        watchdog?.let(handler::removeCallbacks)
        connectionWatchdog?.let(handler::removeCallbacks)
        connectionDeadline = null
        polling?.let(handler::removeCallbacks)
        pending = null
        nextPollAt = 0
        val old = gatt
        gatt = null
        writer = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private fun failed(message: String, peer: Boolean = false, terminal: Boolean = false) {
        val immediate = retries == 0 && peer && stableSince > 0 && SystemClock.elapsedRealtime() - stableSince >= 30000
        closeGatt()
        if (desired == null) return
        if (terminal || retries >= 5 && !recording()) {
            recoveryError = null
            publish("error", if (terminal) message else "Reconnect limit reached. $message")
            desired = null
            return
        }
        recoveryError = message
        publish("reconnecting", message)
        val delay = if (immediate) 0 else listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)[retries.coerceAtMost(5)]
        retries = (retries + 1).coerceAtMost(6)
        val attempt = generation
        retry =
            Runnable { if (generation == attempt && desired != null) begin() }.also { handler.postDelayed(it, delay) }
    }

    fun connectedTo(id: String) = desired == id && state["status"] == "connected"

    fun disconnect() {
        stopScan(publishIdle = false)
        retry?.let(handler::removeCallbacks)
        closeGatt()
        desired = null
        retries = 0
        recoveryError = null
        publish("idle")
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
