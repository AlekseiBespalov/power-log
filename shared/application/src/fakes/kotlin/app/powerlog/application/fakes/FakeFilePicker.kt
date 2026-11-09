package app.powerlog.application.fakes

import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply

class FakeFilePicker(private val post: (() -> Unit) -> Unit) : FilePicker {
    val picks: FakeRequests<Unit, PickedFile> = FakeRequests(post) { Answer.Failed(PortError(PortErrorKind.cancelled)) }

    override fun pickCsv(reply: Reply<PickedFile>) {
        picks.take(Unit, reply)
    }
}

class FakePickedFile(override val name: String, private val bytes: ByteArray, private val post: (() -> Unit) -> Unit) :
    PickedFile {
    private var offset = 0

    var revoked: Boolean = false

    var closed: Boolean = false
        private set

    override val sizeBytes: Long = bytes.size.toLong()

    override fun read(maxBytes: Int, reply: Reply<ByteArray?>) {
        require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
        if (revoked || closed) {
            post { reply.failed(PortError(PortErrorKind.unreadable)) }
            return
        }
        val end = minOf(bytes.size, offset + maxBytes)
        val chunk = if (offset == bytes.size) null else bytes.copyOfRange(offset, end)
        offset = end
        post { reply.ok(chunk) }
    }

    override fun close() {
        closed = true
    }
}
