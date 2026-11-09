package app.powerlog.runtime.bridge

import kotlinx.serialization.json.JsonObject

internal interface ApiSection {
    val names: Set<String>

    suspend fun call(op: String, input: JsonObject): String?

    suspend fun next(stream: String, params: JsonObject, after: Long?, key: String): String?
}
