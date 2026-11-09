package app.powerlog.application.recording

public object CaptureBatchPolicy {
    public const val inboxEntries: Int = 480
    public const val maxEntries: Int = 64
    public const val dueEntries: Int = 8
    public const val dueAgeUs: Long = 1_000_000L
    public const val checkpointUs: Long = 5_000_000L
    public const val previewReadings: Int = 28_800
    public const val summaryUs: Long = 30_000_000L
}
