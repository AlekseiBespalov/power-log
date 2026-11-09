package app.powerlog.application.fakes

import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.KeepAliveEvents
import app.powerlog.application.port.RecordingKeepAlive
import app.powerlog.application.port.Reply

class FakeRecordingKeepAlive(private val post: (() -> Unit) -> Unit) : RecordingKeepAlive {
    data class Hold(val kind: HoldKind, val location: Boolean)

    private var events: KeepAliveEvents? = null
    private val active = mutableSetOf<HoldKind>()
    private val releases = mutableListOf<HoldKind>()

    val holds: FakeRequests<Hold, Unit> = FakeRequests(post) { Answer.Ok(Unit) }

    val held: Set<HoldKind>
        get() = active

    val released: List<HoldKind>
        get() = releases

    override fun attach(keepAliveEvents: KeepAliveEvents) {
        check(events == null) { "attach is called once" }
        events = keepAliveEvents
    }

    override fun hold(kind: HoldKind, location: Boolean, reply: Reply<Unit>) {
        attached()
        active += kind
        val refusable =
            Reply<Unit>(
                onOk = reply::ok,
                onFailed = { error ->
                    active -= kind
                    reply.failed(error)
                },
            )
        holds.take(Hold(kind, location), refusable)
    }

    override fun release(kind: HoldKind) {
        attached()
        active -= kind
        releases += kind
    }

    fun expire(kind: HoldKind) {
        val sink = attached()
        active -= kind
        post { sink.expiring(kind) }
    }

    private fun attached(): KeepAliveEvents = checkNotNull(events) { "attach comes before the first command" }
}
