package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.test.*

class InspectionPageReductionTest {
    @Test
    fun compactBucketsKeepExactTiesBarriersAndPagePartitions() {
        fun value(index: Int): Scalar? =
            when (index) {
                257 -> null
                390 -> Scalar.Integer64(9007199254740993)
                391 -> Scalar.Integer64(9007199254740992)
                400 -> Scalar.Integer64(-9007199254740993)
                else -> Scalar.Real64((100 + index % 7).toDouble())
            }
        fun continuity(index: Int) = Continuity(clockEpoch = if (index < 385) "first" else "second")
        fun reducer() =
            InspectionPlotReducer("humanPowerW", AnalysisRange(0, 64125000), 1, owned = true, identity = { "$it" })
        val original = reducer()
        for (i in 0..512) original.appendCompact(
            InspectionCompactPoint(
                reference = i.toLong(),
                timeUs = i * 125000L,
                value = value(i),
                continuity = continuity(i),
                producer = "controller",
                sequence = i.toLong(),
            )
        )
        val expected = original.aggregate()
        assertEquals(
            listOf(0L, 6L, 256L, 258L, 259L, 384L, 385L, 390L, 400L, 512L),
            expected.points.map { it.point.reference?.reference },
        )
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 2), expected.points.map { it.segment })
        for (size in listOf(1, 17, 128, 256)) {
            val candidate = reducer()
            var first = 0
            while (first < 513) {
                val start = first
                val count = minOf(size, 513 - first)
                InspectionNumericPages.plot(
                    candidate,
                    object : InspectionNumericInput {
                        override val evidenceCount = 2
                        override val count = count

                        override fun word(index: Int, field: Int): Long {
                            val i = start + index
                            val scalar = value(i)
                            return when (field) {
                                0,
                                5 -> i.toLong()
                                1 -> i * 125000L
                                2 -> if (scalar is Scalar.Integer64) scalar.value else (scalar?.real() ?: 0.0).toBits()
                                3 ->
                                    when (scalar) {
                                        null -> 2L
                                        is Scalar.Integer64 -> 1L
                                        else -> 0L
                                    }
                                4 -> if (i < 385) 0 else 1
                                6 -> Double.NaN.toBits()
                                else -> 0L
                            }
                        }

                        override fun continuity(index: Int) =
                            Continuity(clockEpoch = if (index == 0) "first" else "second")

                        override fun producer(index: Int) = "controller"
                    },
                )
                first += count
            }
            val actual = candidate.aggregate()
            assertEquals(expected.points, actual.points)
            assertEquals(expected.firstRawUs, actual.firstRawUs)
            assertEquals(expected.lastRawUs, actual.lastRawUs)
            assertEquals(expected.leadingBarrier, actual.leadingBarrier)
            assertEquals(expected.trailingBarrier, actual.trailingBarrier)
            assertEquals(expected.completeSegments, actual.completeSegments)
            assertEquals(expected.range, actual.range)
            assertEquals(expected.metric, actual.metric)
        }
    }
}
