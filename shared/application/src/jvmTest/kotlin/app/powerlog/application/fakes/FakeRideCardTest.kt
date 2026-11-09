package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RideCardEvents
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RideTimer
import app.powerlog.domain.core.ride.TimerState
import app.powerlog.domain.recording.RideCardFacts
import app.powerlog.domain.recording.RideCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeRideCardTest {
    private val queue = FakePortQueue()
    private val card = FakeRideCard(queue::post)
    private val failures = mutableListOf<PortError>()

    init {
        card.attach(
            object : RideCardEvents {
                override fun failed(error: PortError) {
                    failures += error
                }
            }
        )
    }

    @Test
    fun theNewestFactsAreShownUntilEnd() {
        val running = facts(RideCardState.running)
        val interrupted = facts(RideCardState.interrupted)

        card.publish(running)
        card.publish(interrupted)
        assertEquals(interrupted, card.shown)
        card.end()

        assertEquals(listOf(running, interrupted), card.published)
        assertNull(card.shown)
        assertEquals(1, card.ends)
    }

    @Test
    fun aRefusalArrivesAsFailed() {
        card.refuse(PortError(PortErrorKind.failed))
        assertTrue(failures.isEmpty())
        queue.drain()

        assertEquals(listOf(PortError(PortErrorKind.failed)), failures)
    }

    private fun facts(state: RideCardState) =
        RideCardFacts(
            rideId = "00000000-0000-4000-8000-000000000001",
            token = "token",
            state = state,
            timer = RideTimer(TimerState.entries.first(), baseUs = 0, originUtcUs = null),
            powerW = null,
            heartRateBpm = null,
            bikeFreshUntilUtcUs = null,
            heartRateFreshUntilUtcUs = null,
            controls = listOf(RideAction.entries.first()),
        )
}
