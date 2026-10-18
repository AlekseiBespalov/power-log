@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.powerlog.core.protocol

import kotlin.js.JsModule

@JsModule("node:fs")
private external object Files {
    fun readFileSync(path: String, encoding: String): String
}

internal actual fun readFixtureFile(path: String): ByteArray {
    val data = Files.readFileSync(path, "base64")
    return kotlin.io.encoding.Base64.decode(data)
}
