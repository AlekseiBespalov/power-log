package app.powerlog.application.goldens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun parseJson(text: String, name: String): JsonElement {
    val parsed = runCatching { Json.parseToJsonElement(text) }
    return parsed.getOrElse { refuse("$name is not JSON: ${it.message}") }
}

internal class Fields(private val where: String, json: JsonElement) {
    private val fields = json as? JsonObject ?: refuse("$where is not an object")

    operator fun get(key: String): JsonElement = fields[key] ?: refuse("$where has no $key")

    fun requireKeys(expected: Set<String>) {
        val unknown = fields.keys - expected
        val missing = expected - fields.keys
        if (unknown.isNotEmpty()) refuse("$where holds what no stored column holds: ${unknown.sorted()}")
        if (missing.isNotEmpty()) refuse("$where lacks ${missing.sorted()}")
    }

    fun obj(key: String): Fields = objOrNull(key) ?: refuse("$where.$key is null")

    fun objOrNull(key: String): Fields? = this[key].takeUnless { it is JsonNull }?.let { Fields("$where.$key", it) }

    fun objects(key: String): List<Fields> =
        array(key).mapIndexed { index, item -> Fields("$where.$key[$index]", item) }

    fun texts(key: String): List<String> =
        array(key).map { textOf(it) ?: refuse("$where.$key holds a value that is not a string") }

    fun text(key: String): String = textOrNull(key) ?: refuse("$where.$key is null")

    fun textOrNull(key: String): String? {
        val value = this[key]
        if (value is JsonNull) return null
        return textOf(value) ?: refuse("$where.$key is not a string")
    }

    fun long(key: String): Long = longOrNull(key) ?: refuse("$where.$key is null")

    fun longOrNull(key: String): Long? =
        number(key)?.let { it.toLongOrNull() ?: refuse("$where.$key is not an integer") }

    fun int(key: String): Int = intOrNull(key) ?: refuse("$where.$key is null")

    fun intOrNull(key: String): Int? =
        longOrNull(key)?.let {
            if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else refuse("$where.$key is too large")
        }

    fun double(key: String): Double = doubleOrNull(key) ?: refuse("$where.$key is null")

    fun doubleOrNull(key: String): Double? = number(key)?.toDouble()

    fun bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.booleanOrNull
            ?: refuse("$where.$key is not a boolean")

    fun flag(key: String): Boolean =
        when (long(key)) {
            0L -> false
            1L -> true
            else -> refuse("$where.$key is neither 0 nor 1")
        }

    private fun array(key: String): JsonArray = this[key] as? JsonArray ?: refuse("$where.$key is not an array")

    private fun number(key: String): String? =
        when (val value = this[key]) {
            JsonNull -> null
            is JsonPrimitive ->
                if (value.isString || value.booleanOrNull != null) refuse("$where.$key is not a number")
                else value.content
            else -> refuse("$where.$key is not a number")
        }

    private fun textOf(value: JsonElement): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
}
