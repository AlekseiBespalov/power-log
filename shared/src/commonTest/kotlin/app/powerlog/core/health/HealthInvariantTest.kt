package app.powerlog.core.health

import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.*
import kotlin.test.*

class HealthInvariantTest : HealthFixtureTest() {
    private fun reading(
        time: Long,
        value: Double?,
        collection: Long = time,
        continuity: Continuity = Continuity(),
        active: Int? = 0,
    ) =
        SecondReading(
            Origin(null, null, null, "fixture-$time", "controller", time + 1),
            UTC,
            time,
            collection,
            MeasurementSource("controller", "controller"),
            active,
            continuity,
            value?.let { Scalar.Real64(it) },
            null,
            false,
        )

    @Test
    fun healthSecondContinuityGuards() {
        val bounds = SecondBounds(0, 0, 1000000)
        for (pair in
            listOf(
                arrayOf(reading(0, 100.0), reading(1000000, 100.0, 0)),
                arrayOf(reading(0, 100.0), reading(1000000, 100.0, 3000000)),
                arrayOf(reading(0, 100.0), reading(1000000, 100.0, continuity = Continuity(clockEpoch = "changed"))),
                arrayOf(reading(0, 100.0), reading(1000000, 100.0, active = null)),
                arrayOf(reading(0, 5000.00001), reading(1000000, 5000.00001)),
            )) yes(SecondKernel.aggregate(bounds, HealthKind.riderPower, pair) is SecondOutcome.Absent)
        val actualEnd =
            SecondKernel.aggregate(bounds, HealthKind.riderPower, arrayOf(reading(0, 0.0), reading(1000000, 0.0)))
                as SecondOutcome.Sample
        eq(1L, actualEnd.sample.validOriginalCount)
        eq(0L, actualEnd.sample.integralQUs)
        eq(0.0, actualEnd.sample.value)
        val noTail =
            SecondKernel.aggregate(bounds, HealthKind.riderPower, arrayOf(reading(0, 100.0), reading(500000, 100.0)))
                as SecondOutcome.Sample
        eq(500000L, noTail.sample.coveredUs)
        fails<ArithmeticException> { SecondBuckets.index(Long.MIN_VALUE, Long.MAX_VALUE) }
        fails<ArithmeticException> { SecondBuckets.bounds(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1) }
        val overflow =
            SecondKernel.aggregate(
                bounds,
                HealthKind.riderPower,
                arrayOf(reading(0, 100.0, Long.MIN_VALUE), reading(1000000, 100.0, Long.MAX_VALUE)),
            )
        yes(overflow is SecondOutcome.NumericOverflow)
    }
}
