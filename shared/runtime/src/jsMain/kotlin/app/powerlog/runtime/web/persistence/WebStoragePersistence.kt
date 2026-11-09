package app.powerlog.runtime.web.persistence

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.StoragePersistence
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.settings.PersistenceState
import kotlin.js.Promise

internal class WebStoragePersistence(private val storage: StorageManager?) : StoragePersistence {
    override fun state(reply: Reply<PersistenceState>) {
        ask(reply) { it.persisted }
    }

    override fun request(reply: Reply<PersistenceState>) {
        ask(reply) { it.persist }
    }

    private fun ask(reply: Reply<PersistenceState>, method: (StorageManager) -> (() -> Promise<Boolean>)?) {
        val storage = storage
        val call = storage?.let(method)
        if (storage == null || call == null || jsTypeOf(call) != "function") {
            Promise.resolve(Unit).then { reply.ok(PersistenceState.unsupported) }
            return
        }
        try {
            call
                .asDynamic()
                .call(storage)
                .unsafeCast<Promise<Boolean>>()
                .then(
                    onFulfilled = { persisted ->
                        reply.ok(if (persisted) PersistenceState.persisted else PersistenceState.notPersisted)
                    },
                    onRejected = { error -> reply.failed(failure(error)) },
                )
        } catch (error: dynamic) {
            Promise.resolve(Unit).then { reply.failed(failure(error)) }
        }
    }

    private fun failure(error: dynamic): PortError {
        val name: dynamic = error?.name
        val code = if (jsTypeOf(name) == "string") PlatformCode("DOMException", name.unsafeCast<String>()) else null
        return PortError(PortErrorKind.failed, code)
    }
}

internal external interface StorageManager {
    val persisted: (() -> Promise<Boolean>)?
    val persist: (() -> Promise<Boolean>)?
}
