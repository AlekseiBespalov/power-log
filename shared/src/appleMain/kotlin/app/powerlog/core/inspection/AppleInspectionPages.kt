@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.core.inspection

import app.powerlog.core.analytics.Continuity
import kotlinx.cinterop.*
import platform.Foundation.NSData

private class AppleInspectionInput(val data: NSData, val evidence: List<Continuity>, val producers: List<String>) :
    InspectionNumericInput {
    override val evidenceCount = evidence.size
    override val count = (data.length / 64uL).toInt()
    private val words = data.bytes?.reinterpret<LongVar>()

    init {
        require(data.length % 64uL == 0uL && count in 0..256 && evidence.size == producers.size)
    }

    override fun word(index: Int, field: Int): Long = words!![index * 8 + field]

    override fun continuity(index: Int): Continuity = evidence[index]

    override fun producer(index: Int): String = producers[index]
}

object AppleInspectionPages {
    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun plot(reducer: InspectionPlotReducer, data: NSData, evidence: List<Continuity>, producers: List<String>) {
        InspectionNumericPages.plot(reducer, AppleInspectionInput(data, evidence, producers))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class, InspectionCapacityException::class)
    fun summary(
        summary: InspectionPlotSummary,
        data: NSData,
        evidence: List<Continuity>,
        producers: List<String>,
        identity: (Long) -> String,
    ) {
        InspectionNumericPages.summary(summary, AppleInspectionInput(data, evidence, producers), identity)
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun query(reducer: InspectionQuery, data: NSData, evidence: List<Continuity>, producers: List<String>) {
        InspectionNumericPages.query(reducer, AppleInspectionInput(data, evidence, producers))
    }
}
