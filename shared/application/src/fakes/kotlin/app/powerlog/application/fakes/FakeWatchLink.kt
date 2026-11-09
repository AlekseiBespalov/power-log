package app.powerlog.application.fakes

import app.powerlog.application.port.Clock
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.Responder
import app.powerlog.application.port.WatchLink
import app.powerlog.application.port.WatchLinkAvailability
import app.powerlog.application.port.WatchLinkEvents

class FakeWatchLink(private val clock: Clock, private val post: (() -> Unit) -> Unit) : WatchLink {
    class Recorder : Responder {
        private val all = mutableListOf<ByteArray>()

        val replies: List<ByteArray>
            get() = all

        override fun reply(message: ByteArray) {
            all += message.copyOf()
        }
    }

    private var events: WatchLinkEvents? = null
    private var current = WatchLinkAvailability(installed = false, reachable = false)

    val sends: FakeRequests<ByteArray, ByteArray> = FakeRequests(post) { Answer.Held }

    val launches: FakeRequests<Boolean, Unit> = FakeRequests(post) { Answer.Ok(Unit) }

    override fun attach(watchLinkEvents: WatchLinkEvents) {
        check(events == null) { "attach is called once" }
        events = watchLinkEvents
    }

    override fun availability(): WatchLinkAvailability = current

    override fun send(message: ByteArray, reply: Reply<ByteArray>) {
        attached()
        if (!current.reachable) post { reply.failed(PortError(PortErrorKind.notReachable)) }
        else sends.take(message.copyOf(), reply)
    }

    override fun launchWatchApp(indoor: Boolean, reply: Reply<Unit>) {
        attached()
        launches.take(indoor, reply)
    }

    fun setAvailability(next: WatchLinkAvailability) {
        val sink = attached()
        current = next
        post { sink.availabilityChanged(next) }
    }

    fun receive(message: ByteArray): Recorder {
        val sink = attached()
        val at = clock.read()
        val copy = message.copyOf()
        val responder = Recorder()
        post { sink.received(copy, responder, at) }
        return responder
    }

    private fun attached(): WatchLinkEvents = checkNotNull(events) { "attach comes before the first command" }
}
