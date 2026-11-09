package app.powerlog.application.connection

import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals

class HeldBleEventsTest {
    @Test
    fun eventsBeforeTheConnectionStartsArriveInOrderWhenItStarts() {
        val seen = mutableListOf<String>()
        val held =
            HeldBleEvents(
                object : BleEvents {
                    override fun radioChanged(state: RadioState) {
                        seen += "radio $state"
                    }

                    override fun advertised(advertisement: Advertisement) = Unit

                    override fun searchEnded(error: PortError?) = Unit

                    override fun linkUp(link: Long, bike: String) {
                        seen += "linkUp $link"
                    }

                    override fun linkFailed(link: Long, error: PortError) = Unit

                    override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) {
                        seen += "linkDown $link"
                    }

                    override fun linkReady(link: Long, result: GattReady) = Unit

                    override fun servicesInvalidated(link: Long) = Unit

                    override fun canSend(link: Long) = Unit

                    override fun notified(link: Long, bytes: ByteArray, at: ClockReading) = Unit

                    override fun notificationFailed(link: Long, error: PortError) = Unit

                    override fun writeFailed(link: Long, error: PortError) = Unit
                }
            )
        held.radioChanged(RadioState.poweredOn)
        held.linkDown(7, null, false)
        assertEquals(emptyList(), seen)

        held.release()
        held.linkUp(8, "D1")
        assertEquals(listOf("radio poweredOn", "linkDown 7", "linkUp 8"), seen)
    }
}
