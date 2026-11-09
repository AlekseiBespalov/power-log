package app.powerlog.data

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

internal class JavaLogFiles(resolve: () -> File) : LogFiles {
    constructor(directory: File) : this({ directory })

    private val directory by lazy(resolve)

    override fun size(name: String): Long = File(directory, name).length()

    override fun append(name: String, bytes: ByteArray) {
        directory.mkdirs()
        FileOutputStream(File(directory, name), true).use { it.write(bytes) }
    }

    override fun read(name: String, offset: Long, length: Int): ByteArray {
        val file = File(directory, name)
        if (!file.exists()) return ByteArray(0)
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bytes, read, length - read)
                if (count < 0) break
                read += count
            }
            if (read == length) bytes else bytes.copyOf(read)
        }
    }

    override fun truncate(name: String, length: Long) {
        RandomAccessFile(File(directory, name), "rw").use { it.setLength(length) }
    }

    override fun replace(source: String, target: String) {
        val moved = File(directory, source).renameTo(File(directory, target))
        if (!moved) throw IllegalStateException("cannot move $source")
    }
}
