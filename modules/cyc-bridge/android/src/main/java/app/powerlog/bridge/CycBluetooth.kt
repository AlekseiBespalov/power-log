@file:Suppress("MissingPermission", "DEPRECATION")

package app.powerlog.bridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import java.util.UUID

internal fun nextTelemetryPollMillis(previousDue: Long, sentAt: Long, hz: Int): Long {
    val period = 1000L / hz
    return if (previousDue == 0L || sentAt - previousDue >= period) sentAt + period
    else previousDue + period
}

/** All callbacks and timers are confined to the recorder's native looper. */
internal class CycBluetooth(
    private val context: Context,
    private val handler: Handler,
    private val emit: (String, Payload) -> Unit,
    private val sample: (Map<String, Double>, CycProtocol.Identity, String) -> Unit,
    private val recording: () -> Boolean,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val decoder = CycProtocol.Decoder()
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var identity: CycProtocol.Identity? = null
    private var desired: String? = null
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
    private var connectingCompletion: ((Throwable?) -> Unit)? = null
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
                    stopScan()
                    publish("error", "Bluetooth scan failed ($code). Try again.")
                }
            }
        }

    fun setHz(value: Int) {
        require(value in listOf(2, 4, 8))
        if (hz != value) nextPollAt = 0
        hz = value
    }

    fun startScan() {
        check(manager?.adapter?.isEnabled == true) { "Turn on Bluetooth to find your bike." }
        stopScan()
        devices.clear()
        scanning = true
        publish("scanning")
        manager.adapter.bluetoothLeScanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(CycProtocol.SERVICE))
                    .build()
            ),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanner,
        )
        handler.postDelayed(
            {
                if (scanning) {
                    stopScan()
                    publish("idle")
                }
            },
            15000,
        )
    }

    fun stopScan() {
        if (scanning) {
            runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanner) }
            scanning = false
        }
    }

    fun connect(id: String, frequency: Int, completion: (Throwable?) -> Unit) {
        if (recording() && desired != null && desired != id)
            error("Finish the current ride before connecting another bike.")
        setHz(frequency)
        if (desired == id && state["status"] == "connected") {
            completion(null)
            return
        }
        disconnect()
        desired = id
        retries = 0
        connectingCompletion = completion
        begin()
    }

    private fun publish(status: String, error: String? = null) {
        state =
            mapOf(
                "status" to status,
                "deviceId" to desired,
                "deviceName" to
                    desired?.let { runCatching { devices[it]?.name }.getOrNull() ?: "CYC bike" },
                "controllerModel" to identity?.model,
                "firmwareLabel" to identity?.firmware,
                "error" to error,
                "recoverableConnectionError" to (status == "reconnecting"),
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
        publish(if (retries == 0) "connecting" else "reconnecting")
        val adapter = manager?.adapter
        if (adapter?.isEnabled != true) {
            failed("Bluetooth is off.")
            return
        }
        val device = devices[id] ?: adapter.getRemoteDevice(id)
        deadline(15000, "Bike connection timed out.", attempt)
        gatt =
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    private fun event(connection: BluetoothGatt, body: () -> Unit) {
                        handler.post {
                            if (attempt == generation && gatt === connection) {
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
                            if (
                                status == BluetoothGatt.GATT_SUCCESS &&
                                    newState == BluetoothProfile.STATE_CONNECTED
                            ) {
                                // No competing MTU requests: the frame decoder accepts fragmented
                                // notifications.
                                deadline(15000, "Bike service discovery timed out.", attempt)
                                if (!connection.discoverServices())
                                    failed("Could not discover bike services.")
                            } else if (
                                newState == BluetoothProfile.STATE_DISCONNECTED ||
                                    status != BluetoothGatt.GATT_SUCCESS
                            )
                                failed("Bike disconnected ($status).", true)
                        }

                    override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) =
                        event(connection) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                failed("Bike service discovery failed ($status).")
                                return@event
                            }
                            val service =
                                connection.getService(UUID.fromString(CycProtocol.SERVICE))
                            writer = service?.getCharacteristic(UUID.fromString(CycProtocol.WRITE))
                            val notify =
                                service?.getCharacteristic(UUID.fromString(CycProtocol.NOTIFY))
                            val descriptor =
                                notify?.getDescriptor(
                                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                                )
                            if (writer == null || notify == null || descriptor == null) {
                                failed(
                                    "This bike does not provide the CYC telemetry service.",
                                    terminal = true,
                                )
                                return@event
                            }
                            deadline(
                                15000,
                                "Bike notifications timed out. Reconnect and try again.",
                                attempt,
                            )
                            if (!connection.setCharacteristicNotification(notify, true)) {
                                failed("Could not enable bike notifications.")
                                return@event
                            }
                            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val success =
                                if (Build.VERSION.SDK_INT >= 33)
                                    connection.writeDescriptor(descriptor, value) ==
                                        BluetoothStatusCodes.SUCCESS
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
                            if (status == BluetoothGatt.GATT_SUCCESS)
                                send(CycProtocol.Read.IDENTITY)
                            else failed("Bike notifications failed ($status).")
                        }

                    override fun onCharacteristicWrite(
                        connection: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) =
                        event(connection) {
                            if (status != BluetoothGatt.GATT_SUCCESS)
                                failed("Bike request failed ($status).")
                        }

                    override fun onCharacteristicChanged(
                        connection: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) =
                        event(connection) {
                            if (characteristic.uuid.toString() == CycProtocol.NOTIFY) receive(value)
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
        if (read == CycProtocol.Read.TELEMETRY)
            nextPollAt = nextTelemetryPollMillis(nextPollAt, pendingSince, hz)
        deadline(2500, "Bike response timed out.")
        val bytes = CycProtocol.request(read)
        val type =
            if (
                characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            )
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val success =
            if (Build.VERSION.SDK_INT >= 33)
                connection.writeCharacteristic(characteristic, bytes, type) ==
                    BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = type
                characteristic.value = bytes
                connection.writeCharacteristic(characteristic)
            }
        if (!success) failed("Could not read bike telemetry.")
    }

    private fun receive(bytes: ByteArray) {
        for (payload in decoder.feed(bytes)) {
            val command = payload.firstOrNull()?.toInt()?.and(255) ?: continue
            if (pending == CycProtocol.Read.IDENTITY && command in listOf(0, 111)) {
                try {
                    identity = CycProtocol.identity(payload)
                } catch (error: Exception) {
                    failed(error.message ?: "Unsupported controller", terminal = true)
                    return
                }
                epoch = UUID.randomUUID().toString()
                pending = null
                watchdog?.let(handler::removeCallbacks)
                send(CycProtocol.Read.TELEMETRY)
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
                val now = SystemClock.elapsedRealtime()
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 30000) retries = 0
                lastSample = now
                sampleCount++
                if (state["status"] != "connected") {
                    publish("connected")
                    connectingCompletion?.invoke(null)
                    connectingCompletion = null
                }
                sample(values, model, epoch)
                val wait = maxOf(0L, nextPollAt - SystemClock.elapsedRealtime())
                polling =
                    Runnable { send(CycProtocol.Read.TELEMETRY) }
                        .also { handler.postDelayed(it, wait) }
            }
        }
    }

    private fun closeGatt() {
        generation++
        watchdog?.let(handler::removeCallbacks)
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
        val immediate =
            peer && stableSince > 0 && SystemClock.elapsedRealtime() - stableSince >= 30000
        closeGatt()
        if (desired == null) return
        if (terminal || retries >= 5 && !recording()) {
            publish("error", message)
            desired = null
            connectingCompletion?.invoke(IllegalStateException(message))
            connectingCompletion = null
            return
        }
        publish("reconnecting", message)
        val delay =
            if (immediate) 0
            else listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)[retries.coerceAtMost(5)]
        retries++
        val attempt = generation
        retry =
            Runnable { if (generation == attempt && desired != null) begin() }
                .also { handler.postDelayed(it, delay) }
    }

    fun disconnect() {
        stopScan()
        retry?.let(handler::removeCallbacks)
        closeGatt()
        desired = null
        retries = 0
        connectingCompletion?.invoke(IllegalStateException("Connection cancelled."))
        connectingCompletion = null
        publish("idle")
    }

    fun diagnostics(): Payload =
        mapOf(
            "schemaVersion" to 1,
            "timestamp" to iso(),
            "status" to state["status"],
            "requestedHz" to hz,
            "sampleCount" to sampleCount,
            "connectionAttempts" to attempts,
            "reconnects" to retries,
            "requestTimeouts" to timeouts,
            "decoderDiscardedBytes" to decoder.discarded,
            "recentSampleHz" to null,
            "responseLatencyMs" to null,
            "lastSampleAgeSeconds" to
                if (lastSample > 0) (SystemClock.elapsedRealtime() - lastSample) / 1000.0 else null,
            "lastGapSeconds" to null,
            "background" to false,
            "lastDisconnect" to null,
        )
}
