package app.powerlog.application.port

import app.powerlog.domain.core.time.ClockReading

public interface WatchLink {
    public fun attach(watchLinkEvents: WatchLinkEvents)

    public fun availability(): WatchLinkAvailability

    public fun send(message: ByteArray, reply: Reply<ByteArray>)

    public fun launchWatchApp(indoor: Boolean, reply: Reply<Unit>)
}

public interface WatchLinkEvents {
    public fun availabilityChanged(availability: WatchLinkAvailability)

    public fun received(message: ByteArray, respond: Responder, at: ClockReading)
}

public interface Responder {
    public fun reply(message: ByteArray)
}

public data class WatchLinkAvailability(public val installed: Boolean, public val reachable: Boolean)
