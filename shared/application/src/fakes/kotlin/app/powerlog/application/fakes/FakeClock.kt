package app.powerlog.application.fakes

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading

class FakeClock(monoUs: Long = 0, utcUs: Long = START_UTC_US) : Clock {
    private var mono = monoUs
    private var utc = utcUs

    override fun monoUs(): Long = mono

    override fun utcUs(): Long = utc

    override fun read(): ClockReading = ClockReading(mono, utc)

    fun advance(us: Long) {
        require(us >= 0) { "the monotonic clock never goes back: $us" }
        mono += us
        utc += us
    }

    fun advanceTo(monoUs: Long) {
        advance(monoUs - mono)
    }

    fun setUtc(utcUs: Long) {
        utc = utcUs
    }

    companion object {
        const val START_UTC_US: Long = 1_767_225_600_000_000L
    }
}
