package app.powerlog.runtime.web.files

import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import kotlin.js.Promise
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

internal external interface PickerDocument {
    fun createElement(name: String): dynamic
}

internal external interface PickerWindow {
    fun addEventListener(type: String, listener: (dynamic) -> Unit)

    fun removeEventListener(type: String, listener: (dynamic) -> Unit)

    fun setTimeout(handler: () -> Unit, timeout: Int): Int
}

internal class WebFilePicker(
    private val document: PickerDocument?,
    private val window: PickerWindow?,
    private val activation: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val graceMs: Int = FOCUS_GRACE_MS,
) : FilePicker {
    override fun pickCsv(reply: Reply<PickedFile>) {
        val document = document ?: return post { reply.failed(PortError(PortErrorKind.uiUnavailable)) }
        if (!activation()) return post { reply.failed(PortError(PortErrorKind.noUserActivation)) }
        val input = document.createElement("input")
        input.type = "file"
        input.accept = ".csv,text/csv"
        var answered = false
        lateinit var focused: (dynamic) -> Unit
        fun answer() {
            if (answered) return
            answered = true
            window?.removeEventListener("focus", focused)
            val file = input.files?.item(0)
            if (file == null) reply.failed(PortError(PortErrorKind.cancelled))
            else reply.ok(WebPickedFile(file.name as String, (file.size as Number).toLong(), file.stream()))
        }
        // A browser without the cancel event says nothing when its dialog is dismissed; the window's focus returns
        // first, and a chosen file's change follows within the grace.
        focused = { _: dynamic -> window?.setTimeout({ answer() }, graceMs) }
        input.addEventListener("change", { _: dynamic -> answer() })
        input.addEventListener("cancel", { _: dynamic -> answer() })
        window?.addEventListener("focus", focused)
        input.click()
    }

    companion object {
        const val FOCUS_GRACE_MS = 1_000
    }
}

private class WebPickedFile(override val name: String, override val sizeBytes: Long, stream: dynamic) : PickedFile {
    private val reader: dynamic = stream.getReader()
    private var pending: ByteArray? = null
    private var closed = false

    override fun read(maxBytes: Int, reply: Reply<ByteArray?>) {
        val held = pending
        if (held != null) return reply.ok(take(held, maxBytes))
        if (closed) return reply.failed(PortError(PortErrorKind.unreadable))
        (reader.read() as Promise<dynamic>).then<Unit>(
            onFulfilled = { chunk: dynamic ->
                if (chunk.done == true) reply.ok(null)
                else {
                    val bytes = chunk.value.unsafeCast<Uint8Array>()
                    reply.ok(
                        take(Int8Array(bytes.buffer, bytes.byteOffset, bytes.length).unsafeCast<ByteArray>(), maxBytes)
                    )
                }
            },
            onRejected = { _: Throwable -> reply.failed(PortError(PortErrorKind.unreadable)) },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        (reader.cancel() as Promise<dynamic>).catch {}
    }

    private fun take(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) {
            pending = null
            return bytes.copyOf()
        }
        pending = bytes.copyOfRange(maxBytes, bytes.size)
        return bytes.copyOfRange(0, maxBytes)
    }
}
