package app.powerlog.bridge

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

internal fun analyticsFixture(name: String): JSONObject {
    val file =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tests/fixtures/contract/analytics/$name.json") }
            .first { it.isFile }
    val root = JSONObject(file.readText())
    val shared = root.optJSONObject("shared") ?: JSONObject()
    fun overlay(a: Any?, b: Any?): Any? =
        when {
            a is JSONObject && b is JSONObject ->
                JSONObject(a.toString()).apply {
                    for (key in b.keys()) put(key, overlay(a.opt(key), b.get(key)))
                }
            a is JSONArray && b is JSONArray && a.length() == b.length() ->
                JSONArray().apply { for (i in 0 until a.length()) put(overlay(a.get(i), b.get(i))) }
            else -> b
        }
    fun expand(value: Any?, index: Int = 0): Any? =
        when (value) {
            is JSONArray ->
                JSONArray().apply {
                    for (i in 0 until value.length()) put(expand(value.get(i), index))
                }
            is JSONObject ->
                when {
                    value.has("\$concat") ->
                        JSONArray().apply {
                            val parts = value.getJSONArray("\$concat")
                            for (i in 0 until parts.length()) {
                                val rows = expand(parts.get(i), index) as JSONArray
                                for (j in 0 until rows.length()) put(rows.get(j))
                            }
                        }
                    value.has("\$entries") ->
                        JSONObject().apply {
                            val entries = expand(value.get("\$entries"), index) as JSONArray
                            for (i in 0 until entries.length()) {
                                val entry = entries.getJSONArray(i)
                                put(entry.getString(0), entry.get(1))
                            }
                        }
                    value.has("\$ref") ->
                        expand(shared.get(value.getString("\$ref")), index).let {
                            if (value.has("override")) overlay(it, expand(value.get("override"), index)) else it
                        }
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
                            val n = spec.getDouble("start") + spec.getDouble("step") * index
                            if (spec.has("prefix")) spec.getString("prefix") + n.toLong() + spec.optString("suffix")
                            else n
                        }
                    value.has("\$values") -> (expand(value.get("\$values"), index) as JSONArray).get(index)
                    value.has("\$cycle") ->
                        value.getJSONArray("\$cycle").let {
                            expand(it.get(index % it.length()), index)
                        }
                    value.has("\$time") ->
                        value.getJSONObject("\$time").let { spec ->
                            LocalDate.parse(spec.getString("date"))
                                .atStartOfDay()
                                .plusNanos((spec.getLong("startMs") + spec.getLong("stepMs") * index) * 1000000)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
                        }
                    else ->
                        JSONObject().apply {
                            for (key in value.keys()) if (key != "shared") put(key, expand(value.get(key), index))
                        }
                }
            else -> value
        }
    return expand(root) as JSONObject
}
