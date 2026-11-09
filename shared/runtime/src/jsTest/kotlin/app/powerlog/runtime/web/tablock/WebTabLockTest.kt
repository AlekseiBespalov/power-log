package app.powerlog.runtime.web.tablock

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class WebTabLockTest {
    private val locks: LockManager = js("navigator.locks").unsafeCast<LockManager>()

    @Test
    fun oneTabHoldsTheLeaseAnotherIsRefusedAndAWaitingOneGetsItOnRelease() = runTest {
        val name = "power-log-tablock-${js("Math.random()")}"
        val lease = acquire(WebTabLock(locks), name, waitIfHeld = false).await()
        assertNotNull(lease)
        assertNull(acquire(WebTabLock(locks), name, waitIfHeld = false).await())

        val waiting = acquire(WebTabLock(locks), name, waitIfHeld = true)
        lease.release()
        val next = waiting.await()

        assertNotNull(next)
        next.release()
        assertNotNull(acquire(WebTabLock(locks), name, waitIfHeld = true).await()).release()
    }

    @Test
    fun anInvalidLockNameFailsTheReplyAfterTheCallReturns() = runTest {
        var returned = false
        val failure = Promise { resolve, reject ->
            WebTabLock(locks)
                .acquire(
                    "-reserved",
                    waitIfHeld = false,
                    Reply(
                        onOk = { reject(AssertionError("a reserved name was granted")) },
                        onFailed = { error: PortError -> resolve(returned to error) },
                    ),
                )
            returned = true
        }
        val (afterReturn, error) = failure.await()

        assertTrue(afterReturn)
        assertEquals(PortErrorKind.failed, error.kind)
        assertEquals("DOMException", error.platform?.domain)
    }

    private fun acquire(lock: WebTabLock, name: String, waitIfHeld: Boolean): Promise<TabLease?> =
        Promise { resolve, reject ->
            lock.acquire(name, waitIfHeld, Reply(onOk = resolve, onFailed = { reject(AssertionError(it.toString())) }))
        }
}
