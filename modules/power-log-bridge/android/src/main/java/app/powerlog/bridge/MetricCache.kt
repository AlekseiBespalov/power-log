package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.inspection.*
import java.security.MessageDigest
import org.json.JSONObject

internal object MetricCache {
    private fun digest(state: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(state)
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                append(hex[(byte.toInt() ushr 4) and 15])
                append(hex[byte.toInt() and 15])
            }
        }
    }

    private fun envelope(text: String): Payload =
        mapOf("state" to text, "digest" to digest(text.toByteArray(Charsets.UTF_8)))

    private fun text(payload: JSONObject): String =
        payload.getString("state").also {
            require(digest(it.toByteArray(Charsets.UTF_8)) == payload.getString("digest"))
        }

    private fun bytes(payload: JSONObject): ByteArray =
        payload.getString("state").encodeToByteArray(throwOnInvalidSequence = true).also {
            require(digest(it) == payload.getString("digest"))
        }

    fun encode(value: MetricCheckpoint): Payload = envelope(InspectionCacheCodec.statistics(value))

    fun encodePlot(value: InspectionPlotAggregate): Payload = envelope(InspectionCacheCodec.plot(value))

    fun decode(payload: JSONObject, metric: String, range: AnalysisRange): MetricCheckpoint =
        InspectionCacheCodec.readStatistics(text(payload.getJSONObject("checkpoint"))).also {
            require(it.metric == metric && it.range == range && it.bucket)
        }

    fun decodePlot(payload: JSONObject, metric: String, range: AnalysisRange): InspectionPlotAggregate =
        InspectionCacheCodec.readPlot(
                bytes(payload.getJSONObject("plot")),
                java.lang.Double::parseDouble,
            )
            .also {
                require(it.metric == metric && it.range == range)
            }
}
