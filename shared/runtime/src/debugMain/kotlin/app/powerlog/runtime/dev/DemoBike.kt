package app.powerlog.runtime.dev

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DemoBike(private val clock: Clock, ride: CoroutineDispatcher) : BleCentral {
    private class Link(val id: Long, val profile: TransportProfile)

    private val scope = CoroutineScope(SupervisorJob() + ride)
    private var events: BleEvents? = null
    private var searching = false
    private var link: Link? = null
    private var nextLink = 1L
    private var nextTelemetry = 0

    override fun attach(bleEvents: BleEvents) {
        events = bleEvents
    }

    override fun prepare() {}

    override fun radio(): RadioState = RadioState.poweredOn

    override fun startSearch(profiles: List<TransportProfile>, inventory: Boolean) {
        val profile = profiles.firstOrNull() ?: return
        searching = true
        post {
            if (searching) advertised(Advertisement(BIKE, NAME, RSSI, listOf(profile.service), null, clock.read()))
        }
    }

    override fun stopSearch() {
        searching = false
    }

    override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long {
        val id = nextLink++
        if (bike != BIKE) {
            post { linkFailed(id, PortError(PortErrorKind.unknownBike)) }
            return id
        }
        link = Link(id, profile)
        post { if (link?.id == id) linkUp(id, bike) }
        return id
    }

    override fun ready(link: Long, inventory: Boolean) {
        val live = this.link?.takeIf { it.id == link } ?: return
        val services =
            if (!inventory) null
            else
                listOf(
                    GattService(
                        live.profile.service,
                        listOf(
                            GattCharacteristic(live.profile.notify, NOTIFY),
                            GattCharacteristic(live.profile.write, WRITE_WITHOUT_RESPONSE),
                        ),
                    )
                )
        post {
            if (this@DemoBike.link?.id == link) linkReady(link, GattReady(GattResult.ready, MAX_WRITE, services, null))
        }
    }

    override fun send(link: Long, frame: ControllerFrame): SendResult {
        if (this.link?.id != link) return SendResult.notConnected
        val reply =
            when {
                frame.bytes.contentEquals(DemoFrames.identityRequest) -> DemoFrames.identity
                frame.bytes.contentEquals(DemoFrames.telemetryRequest) ->
                    DemoFrames.telemetry[nextTelemetry++ % DemoFrames.telemetry.size]
                else -> null
            }
        if (reply != null)
            scope.launch {
                delay(REPLY_DELAY_MS)
                if (this@DemoBike.link?.id == link) events?.notified(link, reply.copyOf(), clock.read())
            }
        return SendResult.accepted
    }

    override fun cancel(link: Long) {
        if (this.link?.id != link) return
        this.link = null
        post { linkDown(link, null, false) }
    }

    override fun release(link: Long): Boolean = false

    private fun post(event: BleEvents.() -> Unit) {
        scope.launch { events?.event() }
    }

    internal companion object {
        const val BIKE: String = "power-log-demo-bike"
        const val NAME: String = "Power Log demo bike"
        const val RSSI: Int = -48
        const val REPLY_DELAY_MS: Long = 20L
        const val MAX_WRITE: Int = 182
        // GATT characteristic properties (Bluetooth Core Specification Vol 3 Part G §3.3.1.1): notify, and
        // write without response.
        const val NOTIFY: Int = 0x10
        const val WRITE_WITHOUT_RESPONSE: Int = 0x04
    }
}
