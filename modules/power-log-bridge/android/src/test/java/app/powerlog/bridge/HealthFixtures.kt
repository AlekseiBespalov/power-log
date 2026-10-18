package app.powerlog.bridge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun healthFixture(name: String): JSONObject {
    val file =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tests/fixtures/contract/health/$name.json") }
            .first { it.isFile }
    val root = JSONObject(file.readText())
    val shared = root.optJSONObject("shared") ?: JSONObject()
    fun expand(value: Any?, index: Int = 0): Any? =
        when (value) {
            is JSONObject ->
                when {
                    value.has("\$ref") -> expand(shared.get(value.getString("\$ref")), index)
                    value.has("\$repeat") ->
                        value.getJSONObject("\$repeat").let { spec ->
                            JSONArray().apply {
                                repeat(spec.getInt("count")) {
                                    put(expand(spec.get("template"), it))
                                }
                            }
                        }
                    value.has("\$linear") ->
                        value.getJSONObject("\$linear").let { spec ->
                            spec.optString("prefix") + (spec.getLong("start") + spec.getLong("step") * index).toString()
                        }
                    else ->
                        JSONObject().apply {
                            for (key in value.keys()) put(key, expand(value.get(key), index))
                        }
                }
            is JSONArray ->
                JSONArray().apply {
                    for (i in 0 until value.length()) put(expand(value.get(i), index))
                }
            else -> value
        }
    return expand(root) as JSONObject
}
