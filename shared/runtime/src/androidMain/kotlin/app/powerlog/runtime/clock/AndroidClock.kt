package app.powerlog.runtime.clock

import android.os.SystemClock
import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading

public class AndroidClock : Clock {
    override fun monoUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    override fun utcUs(): Long = System.currentTimeMillis() * 1_000L

    override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
}
