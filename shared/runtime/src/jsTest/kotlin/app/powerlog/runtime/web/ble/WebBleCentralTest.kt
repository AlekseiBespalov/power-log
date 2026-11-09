package app.powerlog.runtime.web.ble

import app.powerlog.application.contracts.BleOsArgs
import app.powerlog.application.contracts.BleScenarioRunner
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.runtime.CONTRACT_FIXTURES
import app.powerlog.runtime.contractFixture
import app.powerlog.runtime.readFixtureBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WebBleCentralTest {
    private val document = contractFixture("ports/ble-central.json").decodeToString()
    private val protocol = readFixtureBytes("$CONTRACT_FIXTURES/../protocol.json").decodeToString()

    @Test
    fun everyWebScenarioPassesThroughAFakeNavigatorBluetooth() {
        val runner = BleScenarioRunner(document)
        val results = runner.run("web") { WebBleHarness(runner.profiles.values.toList(), protocol) }

        assertEquals(emptyList(), results.filter { it.failure != null }.map { "${it.scenario}: ${it.failure}" })
        assertEquals(runner.scenarios.count { "web" in it.platforms }, results.size)
    }

    @Test
    fun theChooserFiltersByEveryProfilesServiceAndNamePrefixAndMayOpenEveryService() {
        val uart = TransportProfile("s1", "n1", "w1", listOf("CYCMOTOR"))
        val other = TransportProfile("s2", "n2", "w2", emptyList())
        val harness = WebBleHarness(listOf(uart, other), protocol)
        harness.central.attach(Silent)

        harness.central.startSearch(listOf(uart, other), inventory = false)

        val options = harness.chooserRequests.single()
        assertEquals(
            """[{"services":["s1"]},{"namePrefix":"CYCMOTOR"},{"services":["s2"]}]""",
            JSON.stringify(options.filters),
        )
        assertEquals("""["s1","s2"]""", JSON.stringify(options.optionalServices))
    }

    @Test
    fun aChosenDeviceWithoutANameAdvertisesNoName() {
        val uart = TransportProfile("s1", "n1", "w1", emptyList())
        val harness = WebBleHarness(listOf(uart), protocol)
        val seen = mutableListOf<Advertisement>()
        harness.central.attach(
            object : BleEvents by Silent {
                override fun advertised(advertisement: Advertisement) {
                    seen += advertisement
                }
            }
        )

        harness.central.startSearch(listOf(uart), inventory = false)
        harness.os("discovered", discovered)
        harness.drain()

        assertNull(seen.single().name)
        assertEquals(listOf("s1"), seen.single().services)
    }

    @Test
    fun aBluetoothObjectWithoutEventMethodsStillAttachesAndSearches() {
        val bluetooth: dynamic = js("({})")
        val requested = mutableListOf<dynamic>()
        bluetooth.requestDevice = { options: dynamic ->
            requested.add(options)
            js("new Promise(function () {})")
        }
        val central = WebBleCentral(bluetooth.unsafeCast<Bluetooth>(), FakeClock(), { it() })

        central.attach(Silent)
        central.startSearch(listOf(TransportProfile("s1", "n1", "w1", emptyList())), inventory = false)

        assertEquals(1, requested.size)
        assertEquals(RadioState.poweredOn, central.radio())
    }

    @Test
    fun aNotificationTheOsDeliversInsideTheWriteArrivesAfterSendReturns() {
        val uart = BleScenarioRunner(document).profiles.getValue("uart")
        val harness = WebBleHarness(listOf(uart), protocol)
        var sending = false
        val arrived = mutableListOf<Boolean>()
        harness.central.attach(
            object : BleEvents by Silent {
                override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {
                    arrived += sending
                }
            }
        )
        harness.os("remembered", bike("D1"))
        val link = harness.central.connect("D1", uart, adopt = false)
        harness.os("connected", bike("D1"))
        harness.drain()
        harness.central.ready(link, inventory = false)
        harness.drain()
        harness.answerInsideWrites(byteArrayOf(1, 2, 3))

        sending = true
        harness.central.send(link, harness.frame(ControllerRequest.identity))
        sending = false
        harness.drain()

        assertEquals(listOf(false), arrived)
    }

    private fun bike(name: String) = BleOsArgs(JsonObject(mapOf("bike" to JsonPrimitive(name))))

    private val discovered =
        BleOsArgs(Json.parseToJsonElement("""{"bike": "D1", "name": null, "services": [], "atMonoUs": 5}""").jsonObject)

    private object Silent : BleEvents {
        override fun radioChanged(state: RadioState) {}

        override fun advertised(advertisement: Advertisement) {}

        override fun searchEnded(error: PortError?) {}

        override fun linkUp(link: Long, bike: String) {}

        override fun linkFailed(link: Long, error: PortError) {}

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) {}

        override fun linkReady(link: Long, result: GattReady) {}

        override fun servicesInvalidated(link: Long) {}

        override fun canSend(link: Long) {}

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {}

        override fun notificationFailed(link: Long, error: PortError) {}

        override fun writeFailed(link: Long, error: PortError) {}
    }
}
