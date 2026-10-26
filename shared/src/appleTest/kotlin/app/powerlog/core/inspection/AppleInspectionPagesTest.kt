@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.test.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

class AppleInspectionPagesTest {
    @Test
    fun summaryPagesMatchPlotPages() {
        val words =
            listOf(
                    longArrayOf(1, 0, 100.0.toBits(), 0, 0, 0, Double.NaN.toBits(), 0),
                    longArrayOf(2, 1000000, 0, 2, 0, 1, Double.NaN.toBits(), 0),
                    longArrayOf(3, 2000000, 120.0.toBits(), 0, 0, 2, Double.NaN.toBits(), 0),
                    longArrayOf(4, 2000000, 90, 1, 0, 3, Double.NaN.toBits(), 0),
                )
                .flatMap { it.asList() }
                .toLongArray()
        val data = words.usePinned { NSData.create(bytes = it.addressOf(0), length = (words.size * 8).toULong()) }
        val evidence = listOf(Continuity())
        val producers = listOf("controller")
        val identity: (Long) -> String = { "o$it" }
        fun reducer() =
            InspectionPlotReducer(
                "heartRateBpm",
                AnalysisRange(0, 4000000),
                4,
                identity = identity,
                alignedSlotUs = 1000000,
            )
        val summary = InspectionPlotSummary("heartRateBpm")
        AppleInspectionPages.summary(summary, data, evidence, producers, identity)
        val direct = reducer()
        AppleInspectionPages.plot(direct, data, evidence, producers)
        val merged = reducer()
        summary.mergeInto(merged)
        assertEquals(direct.result(), merged.result())
        assertEquals(4L, summary.rawInputs)
    }
}
