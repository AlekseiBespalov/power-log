package app.powerlog.bridge

import android.bluetooth.*
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

internal fun telemetryFixture(name: String) =
    JSONObject(File("../../../tests/fixtures/contract/telemetry/$name.json").readText())

internal fun wireBytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun controllerFrame(payload: ByteArray): ByteArray {
    val crc = CycProtocol.crc(payload)
    return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
}

internal fun adapterSession(bluetooth: ControllerBluetooth) =
    ControllerBluetooth::class.java.getDeclaredField("session").apply { isAccessible = true }.get(bluetooth)
        as TelemetrySession

internal fun stageIdentity(bluetooth: ControllerBluetooth) {
    val session = adapterSession(bluetooth)
    val at = SystemClock.elapsedRealtime() / 1000.0
    if (session.state.stage == TelemetryStage.idle) check(session.begin(at, false))
    while (session.state.stage.ordinal < TelemetryStage.identity.ordinal) {
        session.advance(
            session.state.generation,
            session.state.stage,
            TelemetryStage.entries[session.state.stage.ordinal + 1],
            at,
        )
    }
}

internal fun readyAdapter(bluetooth: ControllerBluetooth, identity: ControllerIdentity) {
    stageIdentity(bluetooth)
    val session = adapterSession(bluetooth)
    val at = SystemClock.elapsedRealtime() / 1000.0
    val protocol = identity.controllerProtocol.split('.').map { it.toInt().toByte() }
    val payload =
        byteArrayOf(111, protocol[0], protocol[1]) +
            "${identity.controllerModel} ${identity.firmwareLabel}".toByteArray() +
            byteArrayOf(0)
    session.notifications(session.state.generation, ControllerRequest.identity, controllerFrame(payload), at, at)
    session.advance(session.state.generation, TelemetryStage.identity, TelemetryStage.ready, at)
}

internal fun setAdapterRequest(bluetooth: ControllerBluetooth, role: ControllerRequest, sentAt: Double) {
    val owner = ControllerRequestOwnership(adapterSession(bluetooth).state.generation, role, sentAt)
    for ((name, value) in
        listOf("pending" to role, "pendingSince" to sentAt, "requestOwnership" to owner)) ControllerBluetooth::class
        .java
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .set(bluetooth, value)
}

internal fun receiveAdapter(bluetooth: ControllerBluetooth, frame: ByteArray) {
    val owner =
        ControllerBluetooth::class
            .java
            .getDeclaredField("requestOwnership")
            .apply { isAccessible = true }
            .get(bluetooth)
    ControllerBluetooth::class
        .java
        .getDeclaredMethod(
            "receive",
            ByteArray::class.java,
            RecordingClockReading::class.java,
            ControllerRequestOwnership::class.java,
        )
        .apply { isAccessible = true }
        .invoke(bluetooth, frame, SystemRecordingClock.read(), owner)
}

internal fun completeCancellation(bluetooth: ControllerBluetooth, handler: Handler) {
    val gatt =
        ControllerBluetooth::class.java.getDeclaredField("gatt").apply { isAccessible = true }.get(bluetooth)
            as BluetoothGatt? ?: return
    shadowOf(gatt)
        .gattCallback
        .onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
    shadowOf(handler.looper).idle()
}

internal fun installControllerService(gatt: BluetoothGatt): BluetoothGattCharacteristic {
    val service =
        BluetoothGattService(UUID.fromString(ControllerGatt.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val writer =
        BluetoothGattCharacteristic(
            UUID.fromString(ControllerGatt.WRITE),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
    val notify =
        BluetoothGattCharacteristic(
            UUID.fromString(ControllerGatt.NOTIFY),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
    notify.addDescriptor(
        BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
    )
    service.addCharacteristic(writer)
    service.addCharacteristic(notify)
    shadowOf(gatt).addDiscoverableService(service)
    shadowOf(gatt).allowCharacteristicNotification(notify)
    return notify
}

internal class ControllerAdapterFixture(
    private val handler: Handler,
    sample: (TelemetryReading) -> Unit = {},
    recording: () -> Boolean = { true },
) : AutoCloseable {
    val bluetooth =
        ControllerBluetooth(
            org.robolectric.RuntimeEnvironment.getApplication(),
            handler,
            { _, _ -> },
            { reading, _, _ -> sample(reading) },
            recording,
        )
    val gatt: BluetoothGatt
    val notify: BluetoothGattCharacteristic

    init {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        bluetooth.connect("02:00:00:00:00:01", 4) {}
        gatt =
            ControllerBluetooth::class.java.getDeclaredField("gatt").apply { isAccessible = true }.get(bluetooth)
                as BluetoothGatt
        notify = installControllerService(gatt)
        shadowOf(gatt).notifyConnection(gatt.device.address)
        shadowOf(handler.looper).idle()
        shadowOf(gatt).gattCallback.onDescriptorWrite(gatt, notify.descriptors.single(), BluetoothGatt.GATT_SUCCESS)
        shadowOf(handler.looper).idle()
    }

    fun identity(
        payload: ByteArray =
            wireBytes(
                JSONObject(File("../../../tests/fixtures/protocol.json").readText())
                    .getJSONObject("identity")
                    .getString("payloadHex")
            )
    ) = notification(controllerFrame(payload))

    fun queueNotification(frame: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) shadowOf(gatt).gattCallback.onCharacteristicChanged(gatt, notify, frame)
        else {
            notify.value = frame
            shadowOf(gatt).gattCallback.onCharacteristicChanged(gatt, notify)
        }
    }

    fun notification(frame: ByteArray) {
        queueNotification(frame)
        shadowOf(handler.looper).idle()
    }

    override fun close() {
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        handler.removeCallbacksAndMessages(null)
    }
}
