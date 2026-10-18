package app.powerlog.core.analytics

internal actual fun readFixtureFile(path: String): String = java.io.File(path).readText()
