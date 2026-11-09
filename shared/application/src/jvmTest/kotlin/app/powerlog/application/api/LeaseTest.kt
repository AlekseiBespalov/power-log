package app.powerlog.application.api

import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class LeaseTest {
    @Test
    fun aLeaseDeliveredAfterTheWaiterLeftIsReleased() = runTest {
        var pending: Reply<TabLease?>? = null
        val lock =
            object : TabLock {
                override fun acquire(name: String, waitIfHeld: Boolean, reply: Reply<TabLease?>) {
                    pending = reply
                }
            }
        var released = 0
        val waiter = launch { lock.lease("power-log-recording", waitIfHeld = true) }
        testScheduler.runCurrent()
        waiter.cancel()
        testScheduler.runCurrent()

        requireNotNull(pending)
            .ok(
                object : TabLease {
                    override fun release() {
                        released++
                    }
                }
            )
        assertEquals(1, released)
    }
}
