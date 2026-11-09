@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package app.powerlog.runtime.diagnostics

import kotlin.native.getStackTraceAddresses
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.posix.Dl_info
import platform.posix.dladdr

internal actual fun stackFrames(error: Throwable): StackFrames {
    val (image, load) = image()
    return StackFrames(error.getStackTraceAddresses().map(::hex), image, load)
}

private fun hex(address: Long): String = "0x" + address.toULong().toString(16)

private fun anchor() {}

// The LC_UUID and load address of the image holding the Kotlin code (the app's main executable, which links the
// static framework), which symbolicate-ios.sh matches against the archived dSYMs. mach_header_64 is 32 bytes and
// ncmds its fifth 32-bit word; LC_UUID (0x1b) carries 16 bytes after cmd and cmdsize:
// https://github.com/apple-oss-distributions/xnu/blob/main/EXTERNAL_HEADERS/mach-o/loader.h
private fun image(): Pair<String?, String?> = memScoped {
    val info = alloc<Dl_info>()
    if (dladdr(staticCFunction(::anchor), info.ptr) == 0) return null to null
    val base = info.dli_fbase?.rawValue ?: return null to null
    fun word(offset: Long): UInt = interpretCPointer<UIntVar>(base + offset)!!.pointed.value
    var offset = 32L
    repeat(word(16).toInt()) {
        if (word(offset) == 0x1bu) {
            val bytes = interpretCPointer<UByteVar>(base + offset + 8)!!
            val digits = (0 until 16).joinToString("") { bytes[it].toString(16).padStart(2, '0') }.uppercase()
            val uuid = listOf(0..7, 8..11, 12..15, 16..19, 20..31).joinToString("-") { digits.substring(it) }
            return uuid to hex(base.toLong())
        }
        offset += word(offset + 4).toLong()
    }
    null to hex(base.toLong())
}
