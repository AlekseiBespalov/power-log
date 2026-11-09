package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.RideCard
import app.powerlog.application.port.RideCardEvents
import app.powerlog.domain.recording.RideCardFacts

class FakeRideCard(private val post: (() -> Unit) -> Unit) : RideCard {
    private var events: RideCardEvents? = null
    private val all = mutableListOf<RideCardFacts>()

    val published: List<RideCardFacts>
        get() = all

    var shown: RideCardFacts? = null
        private set

    var ends: Int = 0
        private set

    override fun attach(rideCardEvents: RideCardEvents) {
        check(events == null) { "attach is called once" }
        events = rideCardEvents
    }

    override fun publish(facts: RideCardFacts) {
        attached()
        all += facts
        shown = facts
    }

    override fun end() {
        attached()
        ends++
        shown = null
    }

    fun refuse(error: PortError) {
        val sink = attached()
        post { sink.failed(error) }
    }

    private fun attached(): RideCardEvents = checkNotNull(events) { "attach comes before the first command" }
}
