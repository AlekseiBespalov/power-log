package app.powerlog.runtime

internal actual fun readFixtureBytes(path: String): ByteArray = java.io.File(path).readBytes()

internal actual val wireBytesAreExact: Boolean = true
