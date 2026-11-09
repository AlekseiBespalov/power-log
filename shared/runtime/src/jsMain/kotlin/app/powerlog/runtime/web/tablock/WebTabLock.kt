package app.powerlog.runtime.web.tablock

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock
import app.powerlog.domain.core.failure.PlatformCode
import kotlin.js.Promise

internal class WebTabLock(private val locks: LockManager) : TabLock {
    override fun acquire(name: String, waitIfHeld: Boolean, reply: Reply<TabLease?>) {
        val options: dynamic = js("({})")
        options.ifAvailable = !waitIfHeld
        try {
            locks
                .request(name, options) { lock ->
                    if (lock == null) {
                        reply.ok(null)
                        Promise.resolve(Unit)
                    } else Promise { release, _ -> reply.ok(Lease { release(Unit) }) }
                }
                .then(onFulfilled = {}, onRejected = { error -> reply.failed(failure(error)) })
        } catch (error: dynamic) {
            Promise.resolve(Unit).then { reply.failed(failure(error)) }
        }
    }

    private class Lease(private val end: () -> Unit) : TabLease {
        override fun release() {
            end()
        }
    }

    private fun failure(error: dynamic): PortError {
        val name: dynamic = error?.name
        val code = if (jsTypeOf(name) == "string") PlatformCode("DOMException", name.unsafeCast<String>()) else null
        return PortError(PortErrorKind.failed, code)
    }
}

internal external interface LockManager {
    fun request(name: String, options: dynamic, callback: (lock: dynamic) -> Promise<Unit>): Promise<Unit>
}
