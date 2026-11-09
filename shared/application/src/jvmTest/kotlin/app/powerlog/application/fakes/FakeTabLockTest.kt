package app.powerlog.application.fakes

import app.powerlog.application.port.TabLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class FakeTabLockTest {
    private val queue = FakePortQueue()
    private val lock = FakeTabLock(queue::post)

    @Test
    fun oneTabGetsTheLeaseAndTheOtherNull() {
        val a = ReplyProbe<TabLease?>()
        val b = ReplyProbe<TabLease?>()

        lock.acquire("power-log-recording", waitIfHeld = false, reply = a.reply)
        lock.acquire("power-log-recording", waitIfHeld = false, reply = b.reply)
        queue.drain()

        assertNotNull(a.values.single())
        assertEquals(listOf<TabLease?>(null), b.values)
    }

    @Test
    fun aWaitingTabGetsTheLeaseOnceTheHolderReleases() {
        val a = ReplyProbe<TabLease?>()
        val b = ReplyProbe<TabLease?>()
        lock.acquire("power-log-recording", waitIfHeld = false, reply = a.reply)
        lock.acquire("power-log-recording", waitIfHeld = true, reply = b.reply)
        queue.drain()
        assertFalse(b.settled)
        assertEquals(1, lock.waiting("power-log-recording"))

        val lease = a.values.single()!!
        lease.release()
        lease.release()
        queue.drain()

        val next = b.values.single()
        assertNotNull(next)
        assertSame(next, lock.holder("power-log-recording"))
    }

    @Test
    fun locksOfOtherNamesAreIndependent() {
        val a = ReplyProbe<TabLease?>()
        val b = ReplyProbe<TabLease?>()

        lock.acquire("one", waitIfHeld = false, reply = a.reply)
        lock.acquire("two", waitIfHeld = false, reply = b.reply)
        queue.drain()

        assertNotNull(b.values.single())
        assertNull(lock.holder("three"))
    }
}
