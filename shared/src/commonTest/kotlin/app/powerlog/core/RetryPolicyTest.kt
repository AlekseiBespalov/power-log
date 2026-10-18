package app.powerlog.core

import app.powerlog.core.health.HealthBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryPolicyTest {
    private fun delays(policy: RetryPolicy, kind: FailureKind, count: Int): List<Long?> =
        (1..count).map { policy.next(it, kind).delayUs?.div(1_000_000) }

    @Test
    fun retryPolicyBackoffTable() {
        assertEquals(
            listOf<Long?>(5, 10, 20, 40, 80, 160, 300, 300),
            delays(RetryPolicy.inbox, FailureKind.transient, 8),
        )
        assertEquals(listOf<Long?>(3, 6, 12, 24, 48, 60, 60), delays(RetryPolicy.staging, FailureKind.transient, 7))
        assertEquals(300_000_000L, RetryPolicy.inbox.next(Int.MAX_VALUE, FailureKind.transient).delayUs)
    }

    @Test
    fun retryPolicySettlesPermanentAndParksUnknownAfterTenTries() {
        for (policy in RetryPolicy.entries) {
            assertEquals(RetryStep(RetryAction.settle, null), policy.next(1, FailureKind.permanent))
            for (failures in 1 until RetryPolicy.parkAfter) assertEquals(
                RetryAction.retry,
                policy.next(failures, FailureKind.unknown).action,
                policy.name,
            )
            assertEquals(RetryStep(RetryAction.park, null), policy.next(RetryPolicy.parkAfter, FailureKind.unknown))
            assertEquals(RetryAction.retry, policy.next(1_000_000, FailureKind.transient).action)
            assertFailsWith<IllegalArgumentException> { policy.next(0, FailureKind.transient) }
        }
    }

    @Test
    fun retryPolicyKeepsHealthRetryCadence() {
        for (failures in 1..12) {
            val step = RetryPolicy.health.next(failures, FailureKind.unknown)
            val decision = HealthBudget.retry(failures, false, null, 0)
            assertEquals(step.delayUs, decision.delayUs, "$failures")
            assertEquals(step.action == RetryAction.park, decision.failedRetryable, "$failures")
        }
        assertEquals(
            listOf<Long?>(2, 4, 8, 16, 32, 60, 60, 60, 60, null),
            delays(RetryPolicy.health, FailureKind.unknown, 10),
        )
    }
}
