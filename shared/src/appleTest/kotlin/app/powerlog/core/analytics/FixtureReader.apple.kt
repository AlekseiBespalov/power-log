@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.core.analytics

import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

internal actual fun readFixtureFile(path: String): String =
    requireNotNull(NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null))
