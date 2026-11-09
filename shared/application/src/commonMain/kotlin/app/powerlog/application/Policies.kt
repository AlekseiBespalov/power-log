package app.powerlog.application

public object CommitPolicy {
    public const val busyRetryUs: Long = 1_000_000L
}

public object PagePolicy {
    public const val rows: Int = 4_096
}

public object YieldPolicy {
    public const val rows: Int = 256
}
