package app.powerlog.runtime.dev

import app.powerlog.application.fakes.FakeBleCentral
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.Reply
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.runtime.compose.Ports
import app.powerlog.runtime.compose.RideBound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

class PhoneDevToolsTest {
    private val scheduler = TestCoroutineScheduler()
    private val clock = FakeClock()
    private val post: (() -> Unit) -> Unit = { it() }

    private fun real(ble: FakeBleCentral?) =
        Ports(
            permissions = FakePermissionGate(post),
            picker = FakeFilePicker(post),
            device = FakeDeviceInfo(),
            ble = ble,
            location = null,
            health = null,
            keepAlive = null,
            tabLock = null,
            persistence = null,
            rideCard = null,
            watch = null,
            scheduler = null,
            tones = null,
        )

    private var switchReads = 0

    private fun tools(bike: Boolean) =
        DevToolsFactory.create(
                {
                    switchReads++
                    DevSwitches(bike, false, false, null, null)
                },
                clock,
            )
            .also { (it as RideBound).bind(StandardTestDispatcher(scheduler)) }

    @Test
    fun withoutARealCentralTheDemoBikeAndAGrantingGateReplaceThemAndNothingElse() {
        val real = real(ble = null)
        val ports = tools(bike = false).ports(real)
        assertIs<DemoBike>(ports.ble)
        assertSame(real.picker, ports.picker)
        assertSame(real.device, ports.device)
        val answers = mutableListOf<PermissionStates>()
        ports.permissions.request(PermissionKind.entries.toSet(), Reply({ answers += it }, { error("$it") }))
        assertEquals(emptyList(), answers)
        scheduler.runCurrent()
        val states = answers.single()
        assertEquals(
            listOf(PermissionState.granted),
            listOf(states.bluetooth, states.location, states.notifications).distinct(),
        )
    }

    @Test
    fun theBikeSwitchReplacesARealCentralAndItsGateAtTheFirstCommand() {
        val real = real(FakeBleCentral(Platform.ios, clock, post))
        val ports = tools(bike = true).ports(real)
        assertEquals(0, switchReads)

        val ble = assertIs<ChosenBleCentral>(ports.ble)
        ble.attach(RecordedBle())
        ble.prepare()
        assertIs<DemoBike>(ble.chosen)
        assertEquals(1, switchReads)
        val answers = mutableListOf<PermissionStates>()
        ports.permissions.request(setOf(PermissionKind.bluetooth), Reply({ answers += it }, { error("$it") }))
        scheduler.runCurrent()
        assertEquals(PermissionState.granted, answers.single().bluetooth)
        assertEquals(1, switchReads)
    }

    @Test
    fun aRealCentralAndGateServeWhenTheBikeSwitchIsOff() {
        val central = FakeBleCentral(Platform.android, clock, post)
        val gate = FakePermissionGate(post)
        val real =
            real(central).let {
                Ports(gate, it.picker, it.device, it.ble, null, null, null, null, null, null, null, null, null)
            }
        val ports = tools(bike = false).ports(real)
        assertEquals(0, switchReads)

        val ble = assertIs<ChosenBleCentral>(ports.ble)
        val events = RecordedBle()
        ble.attach(events)
        ble.prepare()
        assertSame(central, ble.chosen)
        ports.permissions.status(Reply({}, { error("$it") }))
        assertEquals(1, switchReads)
        central.os.radio(RadioState.poweredOff)
        assertEquals(listOf(RadioState.poweredOff), events.radios)
    }

    @Test
    fun theExampleRidesAreTheGeneratorsOnTheRealDevicesPlatform() {
        val tools = tools(bike = false)
        tools.ports(real(FakeBleCentral(Platform.android, clock, post)))
        val rides = tools.exampleRides
        assertEquals(3, rides.rideIds.size)
        val create = rides.batch(rides.rideIds.first(), 0)?.mutations?.first()
        assertEquals(
            app.powerlog.domain.core.ride.Device.androidPhone,
            assertIs<app.powerlog.application.store.Mutation.CreateRide>(create).ride.recordedBy,
        )
    }

    @Test
    fun theFactoryCarriesTheMarkerTheReleaseScansLookFor() {
        assertEquals("power-log-dev-tools", DevToolsFactory.marker)
    }

    private class RecordedBle : BleEvents {
        val radios = mutableListOf<RadioState>()

        override fun radioChanged(state: RadioState) {
            radios += state
        }

        override fun advertised(advertisement: Advertisement) = Unit

        override fun searchEnded(error: PortError?) = Unit

        override fun linkUp(link: Long, bike: String) = Unit

        override fun linkFailed(link: Long, error: PortError) = Unit

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) = Unit

        override fun linkReady(link: Long, result: GattReady) = Unit

        override fun servicesInvalidated(link: Long) = Unit

        override fun canSend(link: Long) = Unit

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) = Unit

        override fun notificationFailed(link: Long, error: PortError) = Unit

        override fun writeFailed(link: Long, error: PortError) = Unit
    }
}
