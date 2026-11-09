package app.powerlog.application.port

import app.powerlog.domain.settings.PersistenceState

public interface StoragePersistence {
    public fun state(reply: Reply<PersistenceState>)

    public fun request(reply: Reply<PersistenceState>)
}
