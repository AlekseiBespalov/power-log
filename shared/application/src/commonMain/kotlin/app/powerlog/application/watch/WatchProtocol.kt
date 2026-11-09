package app.powerlog.application.watch

public object WatchProtocol {
    public const val version: Int = 1
    public const val maxMessageBytes: Int = 60_000
    public const val maxQuantities: Int = 32
    public const val maxRoutePoints: Int = 32
    public const val maxLaps: Int = 256
    public const val maxHealthSamples: Int = 64
    public const val rideIntervalUs: Long = 2_000_000L
    public const val routineMinIntervalUs: Long = 1_000_000L
    public const val commandExpiryUs: Long = 5_000_000L
    public const val resendUs: Long = 1_000_000L
    public const val replayUs: Long = 60_000_000L
}
