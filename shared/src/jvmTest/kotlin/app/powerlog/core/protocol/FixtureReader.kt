package app.powerlog.core.protocol

import java.io.File

internal actual fun readFixtureFile(path: String): ByteArray = File(path).readBytes()
