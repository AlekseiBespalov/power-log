package app.powerlog.runtime.dev

import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.telemetry.cyc.CycProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

class DemoBikeTest {
    private class Events : BleEvents {
        val seen = mutableListOf<String>()
        val notifications = mutableListOf<Pair<ByteArray, ClockReading>>()
        val advertisements = mutableListOf<Advertisement>()
        var ready: GattReady? = null

        override fun radioChanged(state: RadioState) {
            seen += "radioChanged $state"
        }

        override fun advertised(advertisement: Advertisement) {
            seen += "advertised ${advertisement.bike}"
            advertisements += advertisement
        }

        override fun searchEnded(error: PortError?) {
            seen += "searchEnded $error"
        }

        override fun linkUp(link: Long, bike: String) {
            seen += "linkUp $link $bike"
        }

        override fun linkFailed(link: Long, error: PortError) {
            seen += "linkFailed $link ${error.kind}"
        }

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) {
            seen += "linkDown $link $error $systemReconnecting"
        }

        override fun linkReady(link: Long, result: GattReady) {
            seen += "linkReady $link ${result.outcome}"
            ready = result
        }

        override fun servicesInvalidated(link: Long) {
            seen += "servicesInvalidated $link"
        }

        override fun canSend(link: Long) {
            seen += "canSend $link"
        }

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) {
            seen += "notified $link"
            notifications += bytes to at
        }

        override fun notificationFailed(link: Long, error: PortError) {
            seen += "notificationFailed $link"
        }

        override fun writeFailed(link: Long, error: PortError) {
            seen += "writeFailed $link"
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val clock = FakeClock(monoUs = 1_000)
    private val events = Events()
    private val bike = DemoBike(clock, StandardTestDispatcher(scheduler)).also { it.attach(events) }
    private val profile =
        TransportProfile(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            emptyList(),
        )

    private fun frame(bytes: ByteArray): ControllerFrame =
        ControllerFrame::class.java.getConstructor(ByteArray::class.java).newInstance(bytes)

    private fun connected(): Long {
        val link = bike.connect(DemoBike.BIKE, profile, adopt = false)
        bike.ready(link, inventory = false)
        scheduler.runCurrent()
        events.seen.clear()
        return link
    }

    private fun payload(frame: ByteArray): ByteArray {
        val payload = frame.copyOfRange(2, frame.size - 3)
        val crc = ((frame[frame.size - 3].toInt() and 0xff) shl 8) or (frame[frame.size - 2].toInt() and 0xff)
        assertEquals(2, frame.first().toInt())
        assertEquals(payload.size, frame[1].toInt() and 0xff)
        assertEquals(3, frame.last().toInt())
        assertEquals(CycProtocol.crc(payload), crc)
        return payload
    }

    @Test
    fun aSearchAdvertisesTheDemoBikeWithTheProfilesServiceAfterTheCallReturns() {
        assertEquals(RadioState.poweredOn, bike.radio())
        bike.startSearch(listOf(profile), inventory = false)
        assertEquals(emptyList(), events.seen)
        scheduler.runCurrent()
        val advertisement = events.advertisements.single()
        assertEquals(DemoBike.BIKE, advertisement.bike)
        assertEquals(listOf(profile.service), advertisement.services)
        assertEquals(clock.read(), advertisement.at)
    }

    @Test
    fun aLinkComesUpAndIsReadyWithWriteWithoutResponse() {
        val link = bike.connect(DemoBike.BIKE, profile, adopt = false)
        bike.ready(link, inventory = true)
        assertEquals(emptyList(), events.seen)
        scheduler.runCurrent()
        assertEquals(listOf("linkUp $link ${DemoBike.BIKE}", "linkReady $link ready"), events.seen)
        val ready = events.ready!!
        assertEquals(GattResult.ready, ready.outcome)
        assertTrue(ready.maxWriteBytes!! >= DemoFrames.telemetryRequest.size)
        assertEquals(profile.service, ready.inventory!!.single().uuid)
    }

    @Test
    fun identityAndTelemetryRequestsGetTheRecordedFrames20MsLater() {
        val link = connected()
        assertEquals(SendResult.accepted, bike.send(link, frame(DemoFrames.identityRequest)))
        scheduler.advanceTimeBy(19)
        scheduler.runCurrent()
        assertEquals(emptyList(), events.seen)
        clock.advance(20_000)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        val (identity, at) = events.notifications.single()
        assertContentEquals(DemoFrames.identity, identity)
        assertEquals(clock.read(), at)
        assertEquals("X6", CycProtocol.identity(payload(identity)).controllerModel)

        repeat(DemoFrames.telemetry.size * 2) { bike.send(link, frame(DemoFrames.telemetryRequest)) }
        scheduler.advanceTimeBy(DemoBike.REPLY_DELAY_MS + 1)
        val telemetry = events.notifications.drop(1).map { it.first }
        assertEquals(DemoFrames.telemetry.size * 2, telemetry.size)
        for ((index, reply) in telemetry.withIndex()) {
            assertContentEquals(DemoFrames.telemetry[index % DemoFrames.telemetry.size], reply)
            CycProtocol.telemetry(payload(reply))
        }
    }

    @Test
    fun otherBytesAreNeverAnswered() {
        val link = connected()
        assertEquals(SendResult.accepted, bike.send(link, frame(byteArrayOf(2, 1, 4, 0x40, 0x84.toByte(), 3))))
        scheduler.advanceTimeBy(1_000)
        assertEquals(emptyList(), events.seen)
        assertEquals(SendResult.notConnected, bike.send(link + 1, frame(DemoFrames.identityRequest)))
    }

    @Test
    fun cancelEndsTheLinkOnceAndDropsItsPendingReply() {
        val link = connected()
        bike.send(link, frame(DemoFrames.identityRequest))
        bike.cancel(link)
        bike.cancel(link)
        scheduler.advanceTimeBy(DemoBike.REPLY_DELAY_MS + 1)
        assertEquals(listOf("linkDown $link null false"), events.seen)
        assertEquals(false, bike.release(link))
        assertEquals(SendResult.notConnected, bike.send(link, frame(DemoFrames.identityRequest)))
    }

    @Test
    fun anotherBikeIsUnknown() {
        val link = bike.connect("another-bike", profile, adopt = false)
        scheduler.runCurrent()
        assertEquals(listOf("linkFailed $link ${PortErrorKind.unknownBike}"), events.seen)
    }
}
