package app.powerlog.core.inspection

import app.powerlog.core.analytics.*

internal interface InspectionNumericInput {
    val count: Int
    val evidenceCount: Int

    fun word(index: Int, field: Int): Long

    fun continuity(index: Int): Continuity

    fun producer(index: Int): String
}

internal object InspectionNumericPages {
    private fun point(input: InspectionNumericInput, index: Int): InspectionCompactPoint {
        val flags = input.word(index, 3)
        require(flags in 0..15 && flags and 3 != 3L)
        val evidenceIndex = input.word(index, 4)
        require(evidenceIndex >= 0 && evidenceIndex < input.evidenceCount.toLong())
        val evidence = evidenceIndex.toInt()
        return InspectionCompactPoint(
            input.word(index, 0),
            input.word(index, 1),
            when (flags and 3) {
                0L -> Scalar.Real64(Double.fromBits(input.word(index, 2)))
                1L -> Scalar.Integer64(input.word(index, 2))
                else -> null
            },
            input.continuity(evidence),
            input.producer(evidence),
            input.word(index, 5),
            Double.fromBits(input.word(index, 6)).takeUnless { it.isNaN() }?.let { Scalar.Real64(it) },
            flags and 4 != 0L,
        )
    }

    fun plot(reducer: InspectionPlotReducer, input: InspectionNumericInput) {
        require(input.count in 0..256)
        for (i in 0 until input.count) {
            val point = point(input, i)
            if (input.word(i, 3) and 8 != 0L)
                reducer.appendCompactDistance(point, input.word(i, 7).toInt(), point.reference)
            else reducer.appendCompact(point)
        }
    }

    fun summary(summary: InspectionPlotSummary, input: InspectionNumericInput, identity: (Long) -> String) {
        require(input.count in 0..256)
        for (i in 0 until input.count) {
            val point = point(input, i)
            if (input.word(i, 3) and 8 != 0L)
                summary.appendCompactDistance(point, input.word(i, 7).toInt(), point.reference)
            else summary.appendCompact(point, identity)
        }
    }

    fun query(reducer: InspectionQuery, input: InspectionNumericInput) {
        require(input.count in 0..256)
        for (i in 0 until input.count) reducer.appendCompact(point(input, i))
    }
}
