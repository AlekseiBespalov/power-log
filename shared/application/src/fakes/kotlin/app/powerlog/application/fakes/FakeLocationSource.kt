package app.powerlog.application.fakes

import app.powerlog.application.port.Clock
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.LocationSource
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal

class FakeLocationSource(private val clock: Clock, private val post: (() -> Unit) -> Unit) : LocationSource {
    enum class Mode {
        stopped,
        running,
        paused,
    }

    private var events: LocationEvents? = null
    private val log = mutableListOf<String>()

    val commands: List<String>
        get() = log

    var mode: Mode = Mode.stopped
        private set

    override fun attach(locationEvents: LocationEvents) {
        check(events == null) { "attach is called once" }
        events = locationEvents
    }

    override fun start() = command("start", Mode.running)

    override fun pause() = command("pause", Mode.paused)

    override fun resume() = command("resume", Mode.running)

    override fun stop() = command("stop", Mode.stopped)

    fun fixes(batch: List<LocationOriginal>) {
        val sink = attached()
        val at: ClockReading = clock.read()
        val copy = batch.toList()
        post { sink.fixesReceived(copy, at) }
    }

    fun provider(state: LocationProviderState) {
        val sink = attached()
        post { sink.providerChanged(state) }
    }

    private fun command(name: String, next: Mode) {
        attached()
        log += name
        mode = next
    }

    private fun attached(): LocationEvents = checkNotNull(events) { "attach comes before the first command" }
}
