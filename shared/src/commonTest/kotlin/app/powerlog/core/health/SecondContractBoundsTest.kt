package app.powerlog.core.health

import app.powerlog.core.analytics.*
import kotlin.test.*

class SecondContractBoundsTest {
    private fun reading(n: Int, source: MeasurementSource = MeasurementSource(Source.controller, Device.controller)) =
        SecondReading(
            Origin(
                null,
                null,
                null,
                "11111111-1111-4111-8111-${n.toString().padStart(12, '0')}",
                Source.controller,
                n.toLong(),
            ),
            "2026-01-01T00:00:00Z",
            n * 1000L,
            n * 1000L,
            source,
            0,
            Continuity(),
            Scalar.Real64(100.0),
            null,
            false,
        )

    @Test
    fun oversizedSecondReturnsInvalid() {
        val outcome =
            SecondKernel.aggregate(SecondBounds(0, 0, 1_000_000), HealthKind.riderPower, Array(129) { reading(it) })
        assertEquals(SecondOutcome.Invalid(HealthReason.invalid), outcome)
    }

    @Test
    fun backwardBudgetClockClampsAndRetainsCallsConservatively() {
        val calls =
            arrayOf(
                BudgetCall(20, CallBucket.write, ExecutionMode.background, 1),
                BudgetCall(5, CallBucket.write, ExecutionMode.background, 2),
                BudgetCall(30, CallBucket.read, ExecutionMode.foreground, 3),
            )
        val normalized = HealthBudget.prune(calls, 10)
        assertEquals(listOf(5L, 10L, 10L), normalized.map { it.atUs })
        assertTrue(HealthBudget.admit(normalized, CallBucket.write, ExecutionMode.background, 1, 10).allowed)
        val full = HealthBudget.prune(Array(300) { calls.first() }, 10)
        assertEquals(
            BudgetAdmission(false, 10 + HealthBudget.shortWindowUs),
            HealthBudget.admit(full, CallBucket.write, ExecutionMode.background, 1, 10),
        )
        assertEquals(300, HealthBudget.prune(full, HealthBudget.shortWindowUs + 9).size)
        assertTrue(
            HealthBudget.admit(full, CallBucket.write, ExecutionMode.background, 1, HealthBudget.shortWindowUs + 10)
                .allowed
        )
        assertFailsWith<IllegalArgumentException> {
            HealthBudget.admit(
                HealthBudget.prune(arrayOf(calls[0].copy(bytes = -1)), 10),
                CallBucket.write,
                ExecutionMode.background,
                0,
                10,
            )
        }
    }
}
