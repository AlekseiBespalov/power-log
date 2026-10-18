package app.powerlog.core.analytics

@JsModule("node:fs")
private external object FixtureFs {
    fun readFileSync(path: String, encoding: String): String
}

internal actual fun readFixtureFile(path: String): String = FixtureFs.readFileSync(path, "utf8")
