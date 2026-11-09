package app.powerlog.platform.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import app.powerlog.application.port.FilePicker
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.platform.ui.AndroidUiContext
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class AndroidFilePicker(
    private val context: Context,
    private val ui: AndroidUiContext,
    private val handler: Handler,
    private val reads: Executor = Executors.newSingleThreadExecutor(),
) : FilePicker {

    override fun pickCsv(reply: Reply<PickedFile>) {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values"))
        ui.startForResult(intent) { resultCode, data ->
            val uri = data?.data
            when {
                resultCode == null -> failed(reply, PortErrorKind.uiUnavailable)
                resultCode != Activity.RESULT_OK || uri == null -> failed(reply, PortErrorKind.cancelled)
                else -> reads.execute { open(uri, reply) }
            }
        }
    }

    private fun open(uri: Uri, reply: Reply<PickedFile>) {
        val picked =
            try {
                val resolver = context.contentResolver
                val (name, size) =
                    resolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) null
                            else {
                                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                val name = if (nameColumn < 0) null else cursor.getString(nameColumn)
                                val size =
                                    if (sizeColumn < 0 || cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn)
                                name to size
                            }
                        } ?: (null to -1L)
                val stream = resolver.openInputStream(uri) ?: return failed(reply, PortErrorKind.unreadable)
                Picked(name ?: uri.lastPathSegment ?: "import.csv", size, stream)
            } catch (_: Exception) {
                return failed(reply, PortErrorKind.unreadable)
            }
        handler.post { reply.ok(picked) }
    }

    private fun failed(reply: Reply<*>, kind: PortErrorKind) {
        handler.post { reply.failed(PortError(kind)) }
    }

    private inner class Picked(
        override val name: String,
        override val sizeBytes: Long,
        private val stream: InputStream,
    ) : PickedFile {
        @Volatile private var closed = false

        override fun read(maxBytes: Int, reply: Reply<ByteArray?>) {
            reads.execute {
                val chunk =
                    try {
                        if (closed) throw IllegalStateException("closed")
                        val buffer = ByteArray(maxBytes)
                        val count = stream.read(buffer)
                        if (count < 0) null else buffer.copyOf(count)
                    } catch (_: Exception) {
                        return@execute failed(reply, PortErrorKind.unreadable)
                    }
                handler.post { reply.ok(chunk) }
            }
        }

        override fun close() {
            closed = true
            reads.execute { runCatching { stream.close() } }
        }
    }
}
