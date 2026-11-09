@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.runtime.clock

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading
import platform.posix.CLOCK_MONOTONIC
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime_nsec_np

// Darwin's CLOCK_MONOTONIC keeps counting while the device sleeps, unlike CLOCK_UPTIME_RAW and systemUptime.
public class AppleClock : Clock {
    override fun monoUs(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000u).toLong()

    override fun utcUs(): Long = (clock_gettime_nsec_np(CLOCK_REALTIME.toUInt()) / 1_000u).toLong()

    override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
}
