package app.powerlog.application.fakes

import app.powerlog.application.port.Reply
import app.powerlog.application.port.StoragePersistence
import app.powerlog.domain.settings.PersistenceState

class FakeStoragePersistence(
    private val post: (() -> Unit) -> Unit,
    var state: PersistenceState = PersistenceState.notPersisted,
    var grants: Boolean = true,
) : StoragePersistence {
    var requests: Int = 0
        private set

    override fun state(reply: Reply<PersistenceState>) {
        val now = state
        post { reply.ok(now) }
    }

    override fun request(reply: Reply<PersistenceState>) {
        requests++
        if (state == PersistenceState.notPersisted && grants) state = PersistenceState.persisted
        val now = state
        post { reply.ok(now) }
    }
}
