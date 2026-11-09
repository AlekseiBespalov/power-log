package app.powerlog.application.port

public interface TabLock {
    public fun acquire(name: String, waitIfHeld: Boolean, reply: Reply<TabLease?>)
}

public interface TabLease {
    public fun release()
}
