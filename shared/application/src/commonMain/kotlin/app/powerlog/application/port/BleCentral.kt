package app.powerlog.application.port

import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.time.ClockReading
import kotlinx.serialization.Serializable

public interface BleCentral {
    public fun attach(bleEvents: BleEvents)

    public fun prepare()

    public fun radio(): RadioState

    public fun startSearch(profiles: List<TransportProfile>, inventory: Boolean)

    public fun stopSearch()

    public fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long

    public fun ready(link: Long, inventory: Boolean)

    public fun send(link: Long, frame: ControllerFrame): SendResult

    public fun cancel(link: Long)

    public fun release(link: Long): Boolean
}

@Serializable
public enum class RadioState {
    unknown,
    resetting,
    unsupported,
    denied,
    poweredOff,
    poweredOn,
}

public enum class SendResult {
    accepted,
    blocked,
    tooLong,
    notConnected,
}

public interface BleEvents {
    public fun radioChanged(state: RadioState)

    public fun advertised(advertisement: Advertisement)

    public fun searchEnded(error: PortError?)

    public fun linkUp(link: Long, bike: String)

    public fun linkFailed(link: Long, error: PortError)

    public fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean)

    public fun linkReady(link: Long, result: GattReady)

    public fun servicesInvalidated(link: Long)

    public fun canSend(link: Long)

    public fun notified(link: Long, bytes: ByteArray, at: ClockReading)

    public fun notificationFailed(link: Long, error: PortError)

    public fun writeFailed(link: Long, error: PortError)
}

public data class Advertisement(
    public val bike: String,
    public val name: String?,
    public val rssi: Int?,
    public val services: List<String>,
    public val manufacturerData: ByteArray?,
    public val at: ClockReading,
)

public data class GattReady(
    public val outcome: GattResult,
    public val maxWriteBytes: Int?,
    public val inventory: List<GattService>?,
    public val error: PortError?,
)

public data class GattService(public val uuid: String, public val characteristics: List<GattCharacteristic>)

// properties: the GATT characteristic properties bitmask, Bluetooth Core Specification Vol 3 Part G §3.3.1.1
public data class GattCharacteristic(public val uuid: String, public val properties: Int)
