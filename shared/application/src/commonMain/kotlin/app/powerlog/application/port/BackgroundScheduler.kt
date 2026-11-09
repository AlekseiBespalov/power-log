package app.powerlog.application.port

public interface BackgroundScheduler {
    public fun schedule(rideId: String, notBeforeUtcUs: Long?, policy: ScheduleKind)

    public fun cancel(rideId: String)
}

public enum class ScheduleKind {
    keep,
    replace,
    afterCurrent,
}
