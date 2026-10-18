package app.powerlog.bridge

import android.util.AtomicFile
import app.powerlog.core.analytics.AnalysisPin
import app.powerlog.core.analytics.CacheIdentity
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal class RideSummaryCache(private val directory: File?) {
    private fun file(pin: AnalysisPin) =
        AtomicFile(File(File(requireNotNull(directory), pin.rideId), CacheIdentity.filename(pin)))

    private fun digest(identity: String, summary: String) =
        MessageDigest.getInstance("SHA-256").digest("$identity\u0000$summary".toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    fun read(pin: AnalysisPin): Payload? = runCatching {
        val bytes =
            file(pin).openRead().use { input ->
                val buffer = ByteArray(1048577)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                require(count <= 1048576)
                String(buffer, 0, count, Charsets.UTF_8)
            }
        val record = JSONObject(bytes)
        val identity = record.getString("identity")
        val summary = record.getString("summary")
        require(identity == CacheIdentity.of(pin) && record.getString("digest") == digest(identity, summary))
        JSONObject(summary).map()
    }
        .getOrNull()

    fun write(pin: AnalysisPin, summary: Payload) {
        if (directory == null) return
        runCatching {
            val identity = CacheIdentity.of(pin)
            val encoded = encode(summary)
            val bytes =
                json(mapOf("identity" to identity, "summary" to encoded, "digest" to digest(identity, encoded)))
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            require(bytes.size <= 1048576)
            val target = file(pin)
            target.baseFile.parentFile!!.mkdirs()
            val output = target.startWrite()
            try {
                output.write(bytes)
                target.finishWrite(output)
            } catch (e: Exception) {
                target.failWrite(output)
                throw e
            }
            val suffix = "-d${pin.distanceSelection.replace(':', '-')}.json"
            target.baseFile.parentFile!!
                .listFiles()
                ?.filter { it != target.baseFile && it.name.endsWith(suffix) }
                ?.forEach { it.delete() }
        }
    }

    fun remove(id: String) {
        directory?.let { File(it, id).deleteRecursively() }
    }

    private fun encode(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> ->
                value.entries.joinToString(prefix = "{", postfix = "}") {
                    "${JSONObject.quote(it.key as String)}:${encode(it.value)}"
                }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
            else -> error("Unsupported summary value")
        }
}
