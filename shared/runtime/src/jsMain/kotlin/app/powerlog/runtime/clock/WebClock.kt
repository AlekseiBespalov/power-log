package app.powerlog.runtime.clock

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading
import kotlin.js.Date
import kotlin.math.floor

public class WebClock : Clock {
    override fun monoUs(): Long = floor(performance.now() * 1_000.0).toLong()

    override fun utcUs(): Long = Date.now().toLong() * 1_000L

    override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
}

private external interface Performance {
    fun now(): Double
}

private external val performance: Performance
