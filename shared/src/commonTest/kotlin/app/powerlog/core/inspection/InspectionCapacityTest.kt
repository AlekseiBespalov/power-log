package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.test.*

class InspectionCapacityTest {
    private fun point(n: Int) =
        InspectionCompactPoint(
            n.toLong(),
            n * 1_000_000L,
            Scalar.Real64(100.0),
            Continuity(clockEpoch = "$n"),
            "controller",
            n.toLong(),
        )

    private fun reducer(limit: Int = 4, owned: Boolean = false, start: Long = 0, end: Long = 20_000_000) =
        InspectionPlotReducer(
            "humanPowerW",
            AnalysisRange(start, end),
            1,
            pointLimit = limit,
            owned = owned,
            identity = { "$it" },
        )

    @Test
    fun appendOverflowIsTypedAndCannotReturnJoinedGaps() {
        val reducer = reducer(owned = true)
        assertFailsWith<InspectionCapacityException> { for (n in 0..6) reducer.appendCompact(point(n)) }
        assertFailsWith<InspectionCapacityException> { reducer.aggregate() }
    }

    @Test
    fun finalQueryOverflowIsTyped() {
        val reducer = reducer()
        for (n in 0..4) reducer.appendCompact(point(n))
        assertFailsWith<InspectionCapacityException> { reducer.result() }
    }

    @Test
    fun mergeOverflowIsTypedAndPreservesEveryRealGap() {
        val child = reducer(16, true)
        for (n in 0..6) child.appendCompact(point(n))
        val aggregate = child.aggregate()
        assertEquals((0..6).toList(), aggregate.points.map { it.segment })
        val parent = reducer()
        assertTrue(parent.canMerge(aggregate))
        assertFailsWith<InspectionCapacityException> { parent.merge(aggregate) }
        assertFailsWith<InspectionCapacityException> { parent.result() }
    }

    private fun invalidIndex(evidenceIndex: Long) {
        val input =
            object : InspectionNumericInput {
                override val count = 1
                override val evidenceCount = 1

                override fun word(index: Int, field: Int): Long =
                    when (field) {
                        2 -> 100.0.toBits()
                        4 -> evidenceIndex
                        6 -> Double.NaN.toBits()
                        else -> 0
                    }

                override fun continuity(index: Int) = listOf(Continuity())[index]

                override fun producer(index: Int) = listOf("controller")[index]
            }
        assertFailsWith<IllegalArgumentException> { InspectionNumericPages.plot(reducer(), input) }
        val query =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(0, 20_000_000),
                ActiveTimeline(20_000_000, emptyList()),
                identity = { "$it" },
            )
        assertFailsWith<IllegalArgumentException> { InspectionNumericPages.query(query, input) }
    }

    @Test fun negativeEvidenceIndexIsRejected() = invalidIndex(-1)

    @Test fun outOfRangeEvidenceIndexIsRejected() = invalidIndex(1)

    @Test fun overflowingEvidenceIndexIsRejectedBeforeNarrowing() = invalidIndex(4_294_967_296)
}
