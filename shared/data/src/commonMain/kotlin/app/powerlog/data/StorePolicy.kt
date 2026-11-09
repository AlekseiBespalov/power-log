package app.powerlog.data

internal object StorePolicy {
    const val queueJobs: Int = 64
    const val busyTimeoutUs: Long = 1_000_000L
    const val noticeBuffer: Int = 64
    const val logRotateBytes: Int = 4 * 1024 * 1024
    const val vacuumPages: Int = 256
    const val journalSizeLimitBytes: Long = 8L * 1024 * 1024
    const val walAutocheckpointPages: Int = 1_000
    const val logLineBytes: Int = 4_096
}
