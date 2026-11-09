package app.powerlog.application.connection

import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.time.ClockReading

internal class HeldBleEvents(private val target: BleEvents, private val wake: () -> Unit = {}) : BleEvents {
    private var held: MutableList<(BleEvents) -> Unit>? = mutableListOf()

    fun release() {
        val queued = held ?: return
        held = null
        for (event in queued) event(target)
    }

    private fun deliver(wakes: Boolean = true, event: (BleEvents) -> Unit) {
        if (wakes) wake()
        val queued = held
        if (queued != null) queued += event else event(target)
    }

    override fun radioChanged(state: RadioState) = deliver { it.radioChanged(state) }

    override fun advertised(advertisement: Advertisement) = deliver { it.advertised(advertisement) }

    override fun searchEnded(error: PortError?) = deliver { it.searchEnded(error) }

    override fun linkUp(link: Long, bike: String) = deliver { it.linkUp(link, bike) }

    override fun linkFailed(link: Long, error: PortError) = deliver { it.linkFailed(link, error) }

    override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) = deliver {
        it.linkDown(link, error, systemReconnecting)
    }

    override fun linkReady(link: Long, result: GattReady) = deliver { it.linkReady(link, result) }

    override fun servicesInvalidated(link: Long) = deliver { it.servicesInvalidated(link) }

    override fun canSend(link: Long) = deliver { it.canSend(link) }

    override fun notified(link: Long, bytes: ByteArray, at: ClockReading) =
        deliver(wakes = false) { it.notified(link, bytes, at) }

    override fun notificationFailed(link: Long, error: PortError) = deliver { it.notificationFailed(link, error) }

    override fun writeFailed(link: Long, error: PortError) = deliver { it.writeFailed(link, error) }
}
