@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.runtime

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual fun readFixtureBytes(path: String): ByteArray {
    val data = requireNotNull(NSData.dataWithContentsOfFile(path)) { "Fixture unavailable: $path" }
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}

internal actual val wireBytesAreExact: Boolean = false
