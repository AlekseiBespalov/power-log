@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.core.protocol

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

internal actual fun readFixtureFile(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Fixture unavailable: $path")
    try {
        check(fseek(file, 0, SEEK_END) == 0)
        val size = ftell(file)
        check(size in 0..16000000)
        check(fseek(file, 0, SEEK_SET) == 0)
        val bytes = ByteArray(size.toInt())
        if (bytes.isNotEmpty())
            bytes.usePinned {
                check(fread(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong() == bytes.size.toLong())
            }
        return bytes
    } finally {
        fclose(file)
    }
}
