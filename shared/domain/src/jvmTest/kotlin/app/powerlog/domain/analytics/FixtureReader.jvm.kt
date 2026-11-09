package app.powerlog.domain.analytics

internal actual fun readFixtureFile(path: String): String = java.io.File(path).readText()
