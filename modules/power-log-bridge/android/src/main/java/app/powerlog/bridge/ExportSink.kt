package app.powerlog.bridge

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import expo.modules.kotlin.Promise
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executor
import java.util.zip.CRC32
import java.util.zip.Deflater

internal class ExportSink(
    private val root: File,
    private val work: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val share: (File) -> String,
) {
    private class Staged(val id: String, val file: File, val output: RandomAccessFile) {
        var length = 0L
        var deflater: Deflater? = null
        var stage = 0L
        val crc = CRC32()
    }

    private val files = HashMap<String, Staged>()
    private val buffer = ByteArray(65536)
    private val publication = Any()
    @Volatile private var stopped = false

    fun open(kind: String, context: Map<String, Any?>, promise: Promise) {
        if (kind !in KINDS || context["exportedAt"] !is String || context["platform"] != "android") {
            promise.reject(ExportException("unsupported", "The export file request is invalid."))
            return
        }
        job(promise) {
            if (stopped) throw ExportException("cancelled", "This export was cancelled.")
            sweep()
            root.mkdirs()
            val id = UUID.randomUUID().toString()
            val file = File(root, ".powerlog-export-$id.part")
            if (!file.createNewFile()) throw ExportException("sink", "Power Log could not create the export file.")
            synchronized(active) { active.add(file.name) }
            try {
                files[id] = Staged(id, file, RandomAccessFile(file, "rw"))
            } catch (error: Exception) {
                synchronized(active) { active.remove(file.name) }
                file.delete()
                throw error
            }
            id
        }
    }

    fun write(id: String, bytes: ByteArray, promise: Promise) =
        job(promise) {
            val staged = file(id)
            val deflater = staged.deflater
            if (deflater == null) append(staged, bytes, bytes.size)
            else {
                staged.crc.update(bytes)
                deflater.setInput(bytes)
                while (!deflater.needsInput()) drain(staged, deflater)
            }
            null
        }

    fun writeAt(id: String, offset: Double, bytes: ByteArray, promise: Promise) =
        job(promise) {
            val staged = file(id)
            val limit = if (staged.deflater == null) staged.length else staged.stage
            if (offset % 1.0 != 0.0 || offset < 0 || offset + bytes.size > limit)
                throw ExportException("sink", "The export patch lies outside the written file.")
            staged.output.seek(offset.toLong())
            staged.output.write(bytes)
            staged.output.seek(staged.length)
            null
        }

    fun beginDeflate(id: String, promise: Promise) =
        job(promise) {
            val staged = file(id)
            if (staged.deflater != null) throw ExportException("sink", "A compressed export entry is already open.")
            staged.deflater = Deflater(5, true)
            staged.crc.reset()
            staged.stage = staged.length
            null
        }

    fun endDeflate(id: String, promise: Promise) =
        job(promise) {
            val staged = file(id)
            val deflater = staged.deflater ?: throw ExportException("sink", "No compressed export entry is open.")
            deflater.finish()
            while (!deflater.finished()) {
                if (drain(staged, deflater) == 0 && !deflater.finished())
                    throw ExportException("sink", "Power Log could not compress the export.")
            }
            val result =
                mapOf(
                    "crc32" to staged.crc.value,
                    "inputBytes" to deflater.bytesRead,
                    "outputBytes" to staged.length - staged.stage,
                )
            deflater.end()
            staged.deflater = null
            result
        }

    fun commit(id: String, name: String, promise: Promise) =
        job(promise) {
            val staged = file(id)
            if (staged.deflater != null)
                throw ExportException("sink", "Finish the compressed export entry before saving the file.")
            if (
                name.isBlank() ||
                    name.startsWith(".") ||
                    name.any { it == '/' || it == '\\' || it.isISOControl() } ||
                    name.toByteArray().size > 255
            )
                throw ExportException("sink", "The export file name is invalid.")
            staged.output.fd.sync()
            staged.output.close()
            val directory = File(root, staged.id)
            val target = File(directory, name)
            // abortAll marks teardown under this lock, so a queued commit discards instead of publishing after it.
            synchronized(publication) {
                if (stopped) {
                    discard(id)
                    throw ExportException("cancelled", "This export was cancelled.")
                }
                if (!directory.mkdir() || !staged.file.renameTo(target))
                    throw ExportException("sink", "Power Log could not save the export file.")
            }
            files.remove(id)
            synchronized(active) { active.remove(staged.file.name) }
            mapOf("uri" to share(target))
        }

    fun abort(id: String, promise: Promise) =
        job(promise) {
            discard(id)
            null
        }

    fun abortAll() {
        synchronized(publication) { stopped = true }
        work.execute { files.keys.toList().forEach { runCatching { discard(it) } } }
    }

    fun cleanup() = work.execute {
        try {
            sweep()
        } catch (error: Exception) {
            Log.w("PowerLog", "Export cleanup failed.", error)
        }
    }

    private fun job(promise: Promise, body: () -> Any?) = work.execute {
        try {
            promise.resolve(body())
        } catch (error: Exception) {
            promise.reject(exportFailure(error, "sink", "Power Log could not write the export file."))
        }
    }

    private fun file(id: String) = files[id] ?: throw ExportException("sink", "This export file is no longer open.")

    private fun append(staged: Staged, bytes: ByteArray, count: Int) {
        staged.output.write(bytes, 0, count)
        staged.length += count
    }

    private fun drain(staged: Staged, deflater: Deflater): Int {
        val count = deflater.deflate(buffer)
        if (count > 0) append(staged, buffer, count)
        return count
    }

    private fun discard(id: String) {
        val staged = files.remove(id) ?: return
        synchronized(active) { active.remove(staged.file.name) }
        staged.deflater?.end()
        try {
            staged.output.close()
        } finally {
            staged.file.delete()
        }
    }

    private fun sweep() {
        val now = clock()
        val entries = root.listFiles() ?: return
        val open = synchronized(active) { active.toSet() }
        for (entry in entries) {
            if (STAGING.matches(entry.name)) {
                if (entry.name !in open) entry.delete()
            } else if (COMMITTED.matches(entry.name) && entry.isDirectory && now - entry.lastModified() > RETENTION)
                entry.deleteRecursively()
        }
    }

    companion object {
        const val RETENTION = 24 * 60 * 60 * 1000L
        private val KINDS = setOf("fit", "zip", "csv")
        private val STAGING = Regex("\\.powerlog-export-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.part")
        private val COMMITTED = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val active = HashSet<String>()

        fun create(context: Context, work: Executor = ExportSource.work) =
            ExportSink(File(context.filesDir, "exports"), work) { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.powerlog.files", file).toString()
            }
    }
}
