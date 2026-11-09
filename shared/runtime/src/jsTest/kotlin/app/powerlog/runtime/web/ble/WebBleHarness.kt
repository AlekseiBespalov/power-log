package app.powerlog.runtime.web.ble

import app.powerlog.application.contracts.BleOsArgs
import app.powerlog.application.contracts.BleOsCall
import app.powerlog.application.contracts.BleScenarioHarness
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.time.ClockReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// "remembered" means a bike chosen earlier in this page, so the harness runs that chooser round itself and keeps its
// events from the scenario; "writeBusy" means a GATT operation of the adapter's own in flight.
internal class WebBleHarness(private val profiles: List<TransportProfile>, protocolJson: String) : BleScenarioHarness {
    private val clock = FakeClock()
    private val queue = ArrayDeque<() -> Unit>()
    private val os = FakeWebBluetooth(profiles, queue::addLast)
    private val adapter = WebBleCentral(os.bluetooth.unsafeCast<Bluetooth>(), clock, queue::addLast)
    private val requests = Json.parseToJsonElement(protocolJson).jsonObject.getValue("requests").jsonObject
    private var hidden = false
    private var lastLink: Long? = null

    val chooserRequests: List<dynamic>
        get() = os.requests

    fun answerInsideWrites(bytes: ByteArray) {
        os.answerInsideWrites = bytes
    }

    override val osCalls: List<BleOsCall>
        get() = os.calls

    override val central: BleCentral =
        object : BleCentral by adapter {
            override fun attach(bleEvents: BleEvents) = adapter.attach(Gate(bleEvents))

            override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long =
                adapter.connect(bike, profile, adopt).also { lastLink = it }
        }

    override fun os(occurrence: String, args: BleOsArgs) {
        when (occurrence) {
            "radio" -> os.availability(RadioState.valueOf(args.text("state")) == RadioState.poweredOn)
            "remembered" -> remember(args.text("bike"))
            "discovered" -> {
                clock.advanceTo(args.long("atMonoUs"))
                os.choose(args.text("bike"), args.textOrNull("name"))
            }
            "chooserCancelled" -> os.rejectChooser("NotFoundError")
            "noActivation" -> os.rejectChooser("SecurityError")
            "connected" -> os.connected(args.text("bike"))
            "connectFailed" -> os.connectFailed(args.text("bike"))
            "disconnected" -> os.disconnected(args.text("bike"))
            "database" -> os.database(args.text("bike"), args.gattServices("services"), args.flag("notifyFails"))
            "notify" -> {
                clock.advanceTo(args.long("atMonoUs"))
                os.notify(args.bytes("bytes"))
            }
            "notifyError" -> os.notifyError()
            "writeBusy" -> lastLink?.let { adapter.send(it, frame(ControllerRequest.telemetry)) }
            "writeReady" -> os.settleWrites(null)
            "writeError" -> os.settleWrites("NetworkError")
            else -> error("web has no OS occurrence $occurrence")
        }
    }

    override fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun frame(request: ControllerRequest): ControllerFrame {
        val name =
            when (request) {
                ControllerRequest.identity -> "identity"
                ControllerRequest.telemetry -> "selective"
            }
        val bytes = requests.getValue(name).jsonPrimitive.content.hexToByteArray()
        val frameClass = ControllerFrame::class.js
        return js("new frameClass(bytes)").unsafeCast<ControllerFrame>()
    }

    private fun remember(bike: String) {
        hidden = true
        try {
            adapter.startSearch(profiles, inventory = false)
            os.choose(bike, null)
            drain()
        } finally {
            hidden = false
        }
    }

    private inner class Gate(private val sink: BleEvents) : BleEvents {
        private fun pass(event: BleEvents.() -> Unit) {
            if (!hidden) sink.event()
        }

        override fun radioChanged(state: RadioState) = pass { radioChanged(state) }

        override fun advertised(advertisement: Advertisement) = pass { advertised(advertisement) }

        override fun searchEnded(error: PortError?) = pass { searchEnded(error) }

        override fun linkUp(link: Long, bike: String) = pass { linkUp(link, bike) }

        override fun linkFailed(link: Long, error: PortError) = pass { linkFailed(link, error) }

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) = pass {
            linkDown(link, error, systemReconnecting)
        }

        override fun linkReady(link: Long, result: GattReady) = pass { linkReady(link, result) }

        override fun servicesInvalidated(link: Long) = pass { servicesInvalidated(link) }

        override fun canSend(link: Long) = pass { canSend(link) }

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) = pass { notified(link, bytes, at) }

        override fun notificationFailed(link: Long, error: PortError) = pass { notificationFailed(link, error) }

        override fun writeFailed(link: Long, error: PortError) = pass { writeFailed(link, error) }
    }
}
