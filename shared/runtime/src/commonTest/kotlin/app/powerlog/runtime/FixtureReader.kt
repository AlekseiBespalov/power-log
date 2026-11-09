package app.powerlog.runtime

internal expect fun readFixtureBytes(path: String): ByteArray

internal fun contractFixture(name: String): ByteArray = readFixtureBytes("$CONTRACT_FIXTURES/$name")

internal expect val wireBytesAreExact: Boolean
