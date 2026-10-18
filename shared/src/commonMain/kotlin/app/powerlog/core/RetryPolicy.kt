package app.powerlog.core

enum class FailureKind {
    permanent,
    transient,
    unknown,
}

enum class RetryAction {
    retry,
    settle,
    park,
}

data class RetryStep(val action: RetryAction, val delayUs: Long?)

enum class RetryPolicy(val baseUs: Long, val capUs: Long) {
    inbox(5_000_000, 300_000_000),
    staging(3_000_000, 60_000_000),
    health(2_000_000, 60_000_000);

    @Throws(IllegalArgumentException::class)
    fun next(failures: Int, kind: FailureKind): RetryStep {
        require(failures > 0)
        if (kind == FailureKind.permanent) return RetryStep(RetryAction.settle, null)
        if (kind == FailureKind.unknown && failures >= parkAfter) return RetryStep(RetryAction.park, null)
        var delay = baseUs
        var doublings = failures - 1
        while (doublings > 0 && delay < capUs) {
            delay *= 2
            doublings--
        }
        return RetryStep(RetryAction.retry, minOf(delay, capUs))
    }

    companion object {
        const val parkAfter = 10
    }
}
