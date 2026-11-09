package app.powerlog.application.port

import app.powerlog.domain.core.time.ClockReading

public interface Clock {
    public fun monoUs(): Long

    public fun utcUs(): Long

    public fun read(): ClockReading
}
