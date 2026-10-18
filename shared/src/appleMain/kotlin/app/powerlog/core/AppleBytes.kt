@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.powerlog.core

import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

object AppleBytes {
    fun bytes(data: NSData): ByteArray {
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    fun data(bytes: ByteArray): NSData =
        if (bytes.isEmpty()) NSData()
        else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
}
