package app.powerlog.bridge

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import org.json.JSONArray
import org.json.JSONObject

internal typealias Payload = Map<String, Any?>

internal fun json(value: Payload) = JSONObject(value)

internal fun JSONObject.map(): Payload = keys().asSequence().associateWith { unwrap(get(it)) }

private fun unwrap(value: Any?): Any? =
    when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.map()
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> value
    }

internal fun Payload.num(key: String, default: Double = 0.0) =
    (this[key] as? Number)?.toDouble() ?: default

internal fun Payload.str(key: String, default: String = "") = this[key] as? String ?: default

internal fun Payload.flag(key: String, default: Boolean = false) = this[key] as? Boolean ?: default

private val utc = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

internal fun iso(millis: Long = System.currentTimeMillis()): String =
    utc.format(Instant.ofEpochMilli(millis))
