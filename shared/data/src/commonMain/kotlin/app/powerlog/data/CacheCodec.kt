package app.powerlog.data

import app.powerlog.domain.charts.PlotAggregate
import app.powerlog.domain.core.analysis.summary.RideSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal object CacheCodec {
    private val json = Json { allowSpecialFloatingPointValues = true }

    fun summary(summary: RideSummary): ByteArray = encode(RideSummary.serializer(), summary)

    fun summary(bytes: ByteArray): RideSummary = decode(RideSummary.serializer(), bytes)

    fun tile(aggregate: PlotAggregate): ByteArray = encode(PlotAggregate.serializer(), aggregate)

    fun tile(bytes: ByteArray): PlotAggregate = decode(PlotAggregate.serializer(), bytes)

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
}
