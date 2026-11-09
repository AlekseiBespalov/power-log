package app.powerlog.application.fakes

import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock

class FakeTabLock(private val post: (() -> Unit) -> Unit) : TabLock {
    private val holders = mutableMapOf<String, TabLease>()
    private val waiters = mutableMapOf<String, ArrayDeque<Reply<TabLease?>>>()

    fun holder(name: String): TabLease? = holders[name]

    fun waiting(name: String): Int = waiters[name]?.size ?: 0

    override fun acquire(name: String, waitIfHeld: Boolean, reply: Reply<TabLease?>) {
        when {
            name !in holders -> grant(name, reply)
            waitIfHeld -> waiters.getOrPut(name) { ArrayDeque() }.addLast(reply)
            else -> post { reply.ok(null) }
        }
    }

    private fun grant(name: String, reply: Reply<TabLease?>) {
        val lease = Lease(name)
        holders[name] = lease
        post { reply.ok(lease) }
    }

    private inner class Lease(private val name: String) : TabLease {
        override fun release() {
            if (holders[name] !== this) return
            holders -= name
            val next = waiters[name]?.removeFirstOrNull() ?: return
            grant(name, next)
        }
    }
}
