package app.powerlog.core.health

import app.powerlog.core.FailureKind
import app.powerlog.core.RetryAction
import app.powerlog.core.RetryPolicy
import app.powerlog.core.analytics.FixedPoint

enum class CallBucket {
    read,
    write,
}

enum class ExecutionMode {
    foreground,
    background,
}

data class PublicationBudget(val provider: Provider, val calls: List<BudgetCall>, val nextEligibleUs: Long?)

data class BudgetCall(val atUs: Long, val bucket: CallBucket, val mode: ExecutionMode, val bytes: Long)

data class BudgetAdmission(val allowed: Boolean, val resumeAtUs: Long?)

data class RetryDecision(val delayUs: Long?, val failedRetryable: Boolean)

object HealthBudget {
    const val shortWindowUs = 900000000L
    const val dayUs = 86400000000L

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun admit(
        calls: Array<BudgetCall>,
        bucket: CallBucket,
        mode: ExecutionMode,
        bytes: Long,
        nowUs: Long,
    ): BudgetAdmission {
        require(calls.size <= 10000 && bytes >= 0 && calls.all { it.atUs <= nowUs && it.bytes >= 0 })
        val same = calls.filter { it.bucket == bucket }.sortedBy { it.atUs }
        val short = same.filter { FixedPoint.subtract(nowUs, it.atUs) < shortWindowUs }
        val daily = same.filter { FixedPoint.subtract(nowUs, it.atUs) < dayUs }
        var resume: Long? = null
        fun wait(until: Long) {
            resume = maxOf(resume ?: until, until)
        }
        // Health Connect's rolling quotas: 1,000 foreground or 300 background calls per 15 minutes, 5,000 calls a
        // day and 35 MB of background writes per 15 minutes.
        val limit = if (mode == ExecutionMode.foreground) 1000 else 300
        if (short.size >= limit) wait(FixedPoint.add(short[short.size - limit].atUs, shortWindowUs))
        if (daily.size >= 5000) wait(FixedPoint.add(daily[daily.size - 5000].atUs, dayUs))
        if (mode == ExecutionMode.background && bucket == CallBucket.write) {
            val background = short.filter { it.mode == ExecutionMode.background }
            var total = background.fold(bytes) { sum, call -> FixedPoint.add(sum, call.bytes) }
            if (bytes > 35000000) return BudgetAdmission(false, null)
            for (call in background) {
                if (total <= 35000000) break
                total -= call.bytes
                wait(FixedPoint.add(call.atUs, shortWindowUs))
            }
        }
        return BudgetAdmission(resume == null, resume)
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun prune(calls: Array<BudgetCall>, nowUs: Long): Array<BudgetCall> =
        calls
            .map { it.copy(atUs = minOf(it.atUs, nowUs)) }
            .filter { FixedPoint.subtract(nowUs, it.atUs) < dayUs }
            .sortedBy { it.atUs }
            .toTypedArray()

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun retry(consecutiveFailures: Int, quota: Boolean, retryAdviceUs: Long?, nowUs: Long): RetryDecision {
        require(consecutiveFailures > 0)
        if (quota)
            return RetryDecision(
                maxOf(
                    if (consecutiveFailures == 1) 60000000L else 900000000L,
                    retryAdviceUs?.let { maxOf(0, FixedPoint.subtract(it, nowUs)) } ?: 0,
                ),
                false,
            )
        val step = RetryPolicy.health.next(consecutiveFailures, FailureKind.unknown)
        return RetryDecision(step.delayUs, step.action == RetryAction.park)
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun wakeCalls(ready: Int, used: Int, startedAtUs: Long, nowUs: Long): Int {
        require(ready >= 0 && used >= 0)
        return if (FixedPoint.subtract(nowUs, startedAtUs) >= 10000000) 0 else minOf(ready, maxOf(0, 4 - used))
    }
}
