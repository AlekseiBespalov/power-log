package app.powerlog.application.health

public object HealthWorkPolicy {
    public const val portCallBudgetUs: Long = 10_000_000L
    public const val pendingOutcomeSettleUs: Long = 3_600_000_000L
    public const val appleQuantitiesPerCall: Int = 32
    public const val healthConnectRecordsPerPage: Int = 128
    public const val healthConnectPagesPerRun: Int = 4
    public const val workerAttempts: Int = 3
    public const val rateLimitRetryUs: Long = 60_000_000L
    public const val rateLimitAttempts: Int = 3
    public val readRateLimitWaitsUs: List<Long> = listOf(60_000_000L, 900_000_000L)
    public const val continueAfterUs: Long = 1_000_000L
}
