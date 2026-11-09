package app.powerlog.application.fakes

import app.powerlog.application.contracts.FakeBleHarness
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeBleCentralTest {
    private val queue = FakePortQueue()
    private val clock = FakeClock()
    private val events = mutableListOf<String>()
    private val notified = mutableListOf<ByteArray>()
    private val uart =
        TransportProfile(
            service = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            notify = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            write = "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            namePrefixes = emptyList(),
        )
    private val other = uart.copy(service = "0000fff0-0000-1000-8000-00805f9b34fb")

    @Test
    fun aCommandBeforeAttachIsADefect() {
        val central = FakeBleCentral(Platform.android, clock, queue::post)

        assertFailsWith<IllegalStateException> { central.prepare() }
    }

    @Test
    fun theSearchNamesEveryProfilesServiceAndStopsOnce() {
        val central = central(Platform.android)

        central.startSearch(listOf(uart, other), inventory = false)
        central.stopSearch()
        central.stopSearch()

        assertEquals(listOf("scan ${uart.service},${other.service}", "stopScan"), central.osCalls)
    }

    @Test
    fun aConnectWithTheRadioOffMakesNoOsCall() {
        val central = central(Platform.android)
        central.os.radio(RadioState.poweredOff)

        central.connect("D1", uart, adopt = false)
        queue.drain()

        assertEquals(emptyList(), central.osCalls)
        assertEquals("linkFailed 1 poweredOff", events.last())
    }

    @Test
    fun adoptingOnTheIphoneCancelsTheOtherRestoredPeripherals() {
        val central = central(Platform.ios)
        central.prepare()
        central.os.restored("D1", FakeBleCentral.Restored.connected)
        central.os.restored("D2", FakeBleCentral.Restored.connecting)
        central.os.radio(RadioState.poweredOn)

        central.connect("D1", uart, adopt = true)
        queue.drain()

        assertEquals(listOf("prepare", "cancel restored D2"), central.osCalls)
        assertEquals("linkUp 1 D1", events.last())
    }

    @Test
    fun theIphoneCancelsAConnectionNoLinkAskedFor() {
        val central = central(Platform.ios)

        central.os.connected("D7")

        assertEquals(listOf("cancel unexpected D7"), central.osCalls)
    }

    @Test
    fun anAndroidReleaseClosesTheClientBeforeTheNextConnect() {
        val central = live(Platform.android)

        central.cancel(1)
        assertTrue(central.release(1))
        central.os.disconnected("D1")
        central.connect("D1", uart, adopt = false)
        queue.drain()

        assertEquals(listOf("prepare", "connect D1", "disconnect D1", "close D1", "connect D1"), central.osCalls)
        assertEquals(listOf("linkUp 1 D1", "linkDown 1 null false"), events)
    }

    @Test
    fun aSecondLinkWhileOneExistsIsADefect() {
        val central = live(Platform.ios)

        assertFailsWith<IllegalStateException> { central.connect("D1", uart, adopt = false) }
        central.cancel(1)
        assertFailsWith<IllegalStateException> { central.connect("D1", uart, adopt = false) }
    }

    @Test
    fun anAndroidConnectWhileACancelledLinkIsOpenIsNotReady() {
        val central = live(Platform.android)
        central.cancel(1)

        val refused = central.connect("D1", uart, adopt = false)
        queue.drain()

        assertEquals(2L, refused)
        assertEquals("linkFailed 2 notReady", events.last())
    }

    @Test
    fun notifiedBytesAreACopyStampedWhenTheOsDeliversThem() {
        val central = live(Platform.ios)
        central.ready(1, inventory = false)
        queue.drain()
        val bytes = byteArrayOf(1, 2, 3)
        clock.advanceTo(1_500)

        central.os.notify(bytes)
        bytes[0] = 9
        clock.advanceTo(2_000)
        queue.drain()

        assertContentEquals(byteArrayOf(1, 2, 3), notified.single())
        assertEquals("notified 1 at 1500", events.last())
    }

    @Test
    fun acceptedFramesAreRecordedAsWritten() {
        val central = live(Platform.web)
        central.ready(1, inventory = false)
        queue.drain()

        assertEquals(SendResult.accepted, central.send(1, FakeBleHarness.frameOf(ControllerRequest.identity)))

        assertContentEquals(FakeBleHarness.bytesOf(ControllerRequest.identity), central.writes.single())
    }

    @Test
    fun theDefaultDatabaseIsReadyWithTheDefaultWriteLimit() {
        val central = live(Platform.android)

        central.ready(1, inventory = true)
        queue.drain()

        assertEquals("linkReady 1 ready ${FakeBleCentral.DEFAULT_MAX_WRITE_BYTES} 1", events.last())
    }

    private fun live(platform: Platform): FakeBleCentral {
        val central = central(platform)
        central.prepare()
        central.os.radio(RadioState.poweredOn)
        central.os.remembered("D1")
        central.connect("D1", uart, adopt = false)
        central.os.connected("D1")
        queue.drain()
        events.removeAll { it.startsWith("radioChanged") }
        return central
    }

    private fun central(platform: Platform): FakeBleCentral =
        FakeBleCentral(platform, clock, queue::post).also { it.attach(Recorder()) }

    private inner class Recorder : BleEvents {
        override fun radioChanged(state: RadioState) {
            events += "radioChanged $state"
        }

        override fun advertised(advertisement: Advertisement) {
            events += "advertised ${advertisement.bike}"
        }

        override fun searchEnded(error: PortError?) {
            events += "searchEnded ${error?.kind}"
        }

        override fun linkUp(link: Long, bike: String) {
            events += "linkUp $link $bike"
        }

        override fun linkFailed(link: Long, error: PortError) {
            events += "linkFailed $link ${error.kind}"
        }

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) {
            events += "linkDown $link ${error?.kind} $systemReconnecting"
        }

        override fun linkReady(link: Long, result: GattReady) {
            events += "linkReady $link ${result.outcome} ${result.maxWriteBytes} ${result.inventory?.size}"
        }

        override fun servicesInvalidated(link: Long) {
            events += "servicesInvalidated $link"
        }

        override fun canSend(link: Long) {
            events += "canSend $link"
        }

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {
            notified += bytes
            events += "notified $link at ${at.monoUs}"
        }

        override fun notificationFailed(link: Long, error: PortError) {
            events += "notificationFailed $link"
        }

        override fun writeFailed(link: Long, error: PortError) {
            events += "writeFailed $link"
        }
    }
}
