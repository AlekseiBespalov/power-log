package app.powerlog.kit

internal actual fun readFixtureFile(path: String): String = java.io.File(path).readText()
