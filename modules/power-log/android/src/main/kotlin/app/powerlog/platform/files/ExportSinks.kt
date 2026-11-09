package app.powerlog.platform.files

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.Deflater

class SinkException(val code: String, message: String) : Exception(message)

typealias SinkReply = (Result<Any?>) -> Unit

class ExportSinks(
    private val root: File,
    private val work: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uri: (File) -> String,
) {
    private class Staged(val id: String, val kind: String, val file: File, val output: RandomAccessFile) {
        var length = 0L
        var deflater: Deflater? = null
        var stage = 0L
        val crc = CRC32()
    }

    private val files = HashMap<String, Staged>()
    private val buffer = ByteArray(65536)
    private val publication = Any()
    @Volatile private var stopped = false

    fun open(kind: String, reply: SinkReply) =
        job(reply) {
            if (kind !in MIME_TYPES) throw invalid("There is no export file kind $kind.")
            if (stopped) throw SinkException("failed", "The export was cancelled.")
            sweep()
            root.mkdirs()
            val id = UUID.randomUUID().toString()
            val file = File(root, ".powerlog-export-$id.part")
            if (!file.createNewFile()) throw IOException("Power Log could not create the export file.")
            synchronized(active) { active.add(file.name) }
            try {
                files[id] = Staged(id, kind, file, RandomAccessFile(file, "rw"))
            } catch (error: Exception) {
                synchronized(active) { active.remove(file.name) }
                file.delete()
                throw error
            }
            id
        }

    fun write(id: String, bytes: ByteArray, reply: SinkReply) =
        job(reply) {
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

    fun writeAt(id: String, offset: Double, bytes: ByteArray, reply: SinkReply) =
        job(reply) {
            val staged = file(id)
            val limit = if (staged.deflater == null) staged.length else staged.stage
            if (offset % 1.0 != 0.0 || offset < 0 || offset + bytes.size > limit)
                throw invalid("The export patch lies outside the written file.")
            staged.output.seek(offset.toLong())
            staged.output.write(bytes)
            staged.output.seek(staged.length)
            null
        }

    fun beginDeflate(id: String, reply: SinkReply) =
        job(reply) {
            val staged = file(id)
            if (staged.deflater != null) throw invalid("A compressed export entry is already open.")
            staged.deflater = Deflater(5, true)
            staged.crc.reset()
            staged.stage = staged.length
            null
        }

    fun endDeflate(id: String, reply: SinkReply) =
        job(reply) {
            val staged = file(id)
            val deflater = staged.deflater ?: throw invalid("No compressed export entry is open.")
            deflater.finish()
            while (!deflater.finished()) {
                if (drain(staged, deflater) == 0 && !deflater.finished())
                    throw SinkException("failed", "Power Log could not compress the export.")
            }
            val result =
                mapOf(
                    "crc32" to staged.crc.value.toDouble(),
                    "inputBytes" to deflater.bytesRead.toDouble(),
                    "outputBytes" to (staged.length - staged.stage).toDouble(),
                )
            deflater.end()
            staged.deflater = null
            result
        }

    fun commit(id: String, name: String, reply: SinkReply) =
        job(reply) {
            val staged = file(id)
            if (staged.deflater != null) throw invalid("Finish the compressed export entry before saving the file.")
            staged.output.fd.sync()
            staged.output.close()
            val directory = File(root, staged.id)
            val target = File(directory, name)
            // abortAll marks teardown under this lock, so a queued commit discards instead of publishing after it.
            synchronized(publication) {
                if (stopped) {
                    discard(id)
                    throw SinkException("failed", "The export was cancelled.")
                }
                if (!directory.mkdir() || !staged.file.renameTo(target))
                    throw IOException("Power Log could not save the export file.")
            }
            files.remove(id)
            synchronized(active) { active.remove(staged.file.name) }
            mapOf(
                "uri" to uri(target),
                "name" to name,
                "mimeType" to MIME_TYPES.getValue(staged.kind),
                "sizeBytes" to target.length().toDouble(),
            )
        }

    fun abort(id: String, reply: SinkReply) =
        job(reply) {
            discard(id)
            null
        }

    fun purge(kind: String, reply: SinkReply) =
        job(reply) {
            if (kind !in MIME_TYPES) throw invalid("There is no export file kind $kind.")
            for (directory in root.listFiles().orEmpty()) {
                if (!COMMITTED.matches(directory.name) || !directory.isDirectory) continue
                directory.listFiles().orEmpty().filter { it.name.endsWith(".$kind") }.forEach(File::delete)
                if (directory.list().isNullOrEmpty()) directory.delete()
            }
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

    private fun job(reply: SinkReply, body: () -> Any?) = work.execute {
        val answer =
            try {
                Result.success(body())
            } catch (error: Exception) {
                Result.failure(rejection(error))
            }
        reply(answer)
    }

    private fun file(id: String) = files[id] ?: throw invalid("This export file is no longer open.")

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
        private val MIME_TYPES =
            mapOf(
                "fit" to "application/octet-stream",
                "csv" to "text/csv",
                "zip" to "application/zip",
                "json" to "application/octet-stream",
            )
        private val STAGING = Regex("\\.powerlog-export-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.part")
        private val COMMITTED = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val active = HashSet<String>()

        fun create(context: Context, work: Executor = Executors.newSingleThreadExecutor()): ExportSinks =
            ExportSinks(File(context.filesDir, "exports"), work) { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.powerlog.files", file).toString()
            }

        private fun invalid(message: String) = SinkException("invalid", message)

        internal fun rejection(error: Exception): SinkException =
            when {
                error is SinkException -> error
                error is IOException && outOfSpace(error) ->
                    SinkException("full", "There isn't enough space for this export.")
                error is IOException -> SinkException("io", "Power Log couldn't write the export file.")
                else -> SinkException("failed", "Power Log couldn't write the export file.")
            }

        private fun outOfSpace(error: Throwable): Boolean =
            generateSequence(error) { it.cause }
                .any {
                    (it as? ErrnoException)?.errno == OsConstants.ENOSPC || it.message?.contains("ENOSPC") == true
                }
    }
}
