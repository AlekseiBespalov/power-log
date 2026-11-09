@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.powerlog.runtime

// A static import of node:fs would stop this test build from loading in Chromium (core:browser).
private val files: dynamic
    get() = js("globalThis.process.getBuiltinModule('node:fs')")

internal actual fun readFixtureBytes(path: String): ByteArray =
    kotlin.io.encoding.Base64.decode(files.readFileSync(path, "base64") as String)

internal actual val wireBytesAreExact: Boolean = false
