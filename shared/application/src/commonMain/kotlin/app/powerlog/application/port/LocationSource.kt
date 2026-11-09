package app.powerlog.application.port

import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal

public interface LocationSource {
    public fun attach(locationEvents: LocationEvents)

    public fun start()

    public fun pause()

    public fun resume()

    public fun stop()
}

public interface LocationEvents {
    public fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading)

    public fun providerChanged(state: LocationProviderState)
}

public enum class LocationProviderState {
    waiting,
    available,
    denied,
    restricted,
    servicesOff,
    unavailable,
}
