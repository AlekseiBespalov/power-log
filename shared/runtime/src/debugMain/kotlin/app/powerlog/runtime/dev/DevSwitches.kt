package app.powerlog.runtime.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

public class DevSwitches(
    public val bike: Boolean,
    public val longRide: Boolean,
    public val slice: Boolean,
    public val crashAfterUs: Long?,
    public val killAfterUs: Long?,
) {
    public companion object {
        private val timers = listOf("crashAfterSeconds", "killAfterSeconds")

        public fun fromSeconds(
            bike: Boolean,
            longRide: Boolean,
            slice: Boolean,
            crashAfterSeconds: Double?,
            killAfterSeconds: Double?,
        ): DevSwitches = DevSwitches(bike, longRide, slice, micros(crashAfterSeconds), micros(killAfterSeconds))

        public fun parse(json: String): DevSwitches {
            val fields = document(json)
            fun flag(name: String) = (fields[name] as? JsonPrimitive)?.booleanOrNull ?: false
            fun seconds(name: String) = (fields[name] as? JsonPrimitive)?.doubleOrNull
            return fromSeconds(
                flag("bike"),
                flag("longRide"),
                flag("slice"),
                seconds("crashAfterSeconds"),
                seconds("killAfterSeconds"),
            )
        }

        public fun withoutTimers(json: String): String =
            JsonObject(document(json).filterKeys { it !in timers } + timers.associateWith { JsonNull }).toString()

        private fun document(json: String): Map<String, JsonElement> =
            try {
                Json.parseToJsonElement(json).jsonObject
            } catch (_: IllegalArgumentException) {
                emptyMap()
            }

        private fun micros(seconds: Double?): Long? =
            seconds?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1_000_000.0).toLong() }
    }
}
