package app.powerlog.application.port

import app.powerlog.domain.recording.RideCardFacts

public interface RideCard {
    public fun attach(rideCardEvents: RideCardEvents)

    public fun publish(facts: RideCardFacts)

    public fun end()
}

public interface RideCardEvents {
    public fun failed(error: PortError)
}
