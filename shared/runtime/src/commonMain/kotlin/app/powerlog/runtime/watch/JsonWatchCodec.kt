package app.powerlog.runtime.watch

import app.powerlog.application.watch.WatchCodec
import app.powerlog.application.watch.WatchDecodeResult
import app.powerlog.application.watch.WatchMessage
import app.powerlog.application.watch.WatchProtocol
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class JsonWatchCodec : WatchCodec {
    private val json: Json = Json {
        classDiscriminator = "kind"
        explicitNulls = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    override fun decode(bytes: ByteArray): WatchDecodeResult {
        val message =
            try {
                json.parseToJsonElement(bytes.decodeToString())
            } catch (_: SerializationException) {
                return WatchDecodeResult.Invalid
            }
        if (message !is JsonObject) return WatchDecodeResult.Invalid
        val version = message["v"] ?: return WatchDecodeResult.Invalid
        if (version !is JsonPrimitive || version.isString || version.content != "${WatchProtocol.version}")
            return WatchDecodeResult.VersionMismatch
        return try {
            WatchDecodeResult.Valid(json.decodeFromJsonElement(WatchMessage.serializer(), JsonObject(message - "v")))
        } catch (_: IllegalArgumentException) {
            WatchDecodeResult.Invalid
        }
    }

    override fun encode(message: WatchMessage): ByteArray {
        val body = json.encodeToString(WatchMessage.serializer(), message)
        return "{\"v\":${WatchProtocol.version},${body.substring(1)}".encodeToByteArray()
    }
}
