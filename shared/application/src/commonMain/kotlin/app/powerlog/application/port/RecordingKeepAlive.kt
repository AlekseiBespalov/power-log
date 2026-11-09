package app.powerlog.application.port

public interface RecordingKeepAlive {
    public fun attach(keepAliveEvents: KeepAliveEvents)

    public fun hold(kind: HoldKind, location: Boolean, reply: Reply<Unit>)

    public fun release(kind: HoldKind)
}

public enum class HoldKind {
    ride,
    reconnect,
}

public interface KeepAliveEvents {
    public fun expiring(kind: HoldKind)
}
