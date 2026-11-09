package app.powerlog.data

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class ProtectedDirectoryTest {
    @Test
    fun aDirectoryThatAlreadyExistsTakesTheProtectionClass() {
        val files = NSFileManager.defaultManager
        val path = NSTemporaryDirectory() + "power-log-protected-" + NSUUID().UUIDString
        files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        try {
            protectedDirectory(path)
            // The iOS simulator applies no data protection and reads the class back as null; a device session checks
            // it.
            if (Platform.osFamily == OsFamily.MACOSX) {
                assertEquals(
                    NSFileProtectionCompleteUntilFirstUserAuthentication,
                    files.attributesOfItemAtPath(path, error = null)?.get(NSFileProtectionKey),
                )
            }
        } finally {
            files.removeItemAtPath(path, error = null)
        }
    }
}
