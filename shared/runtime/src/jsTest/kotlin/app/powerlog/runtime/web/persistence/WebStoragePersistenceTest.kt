package app.powerlog.runtime.web.persistence

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.domain.settings.PersistenceState
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class WebStoragePersistenceTest {
    private fun storage(persisted: dynamic, persist: dynamic): StorageManager {
        val storage: dynamic = js("({})")
        storage.persisted = persisted
        storage.persist = persist
        return storage.unsafeCast<StorageManager>()
    }

    private fun answering(value: Boolean): dynamic = { Promise.resolve(value) }

    private fun rejecting(name: String): dynamic = {
        val error: dynamic = js("new Error('refused')")
        error.name = name
        Promise.reject(error.unsafeCast<Throwable>())
    }

    private fun ask(call: (Reply<PersistenceState>) -> Unit): Promise<Pair<Boolean, Result<PersistenceState>>> =
        Promise { resolve, _ ->
            var returned = false
            call(
                Reply(
                    onOk = { resolve(returned to Result.success(it)) },
                    onFailed = { error: PortError -> resolve(returned to Result.failure(Refused(error))) },
                )
            )
            returned = true
        }

    private class Refused(val error: PortError) : Exception()

    @Test
    fun stateAndRequestAnswerWhetherTheBrowserKeepsTheStore() = runTest {
        val kept = WebStoragePersistence(storage(answering(true), answering(true)))
        assertEquals(PersistenceState.persisted, ask(kept::state).await().second.getOrThrow())
        val notKept = WebStoragePersistence(storage(answering(false), answering(false)))
        assertEquals(PersistenceState.notPersisted, ask(notKept::state).await().second.getOrThrow())
        assertEquals(PersistenceState.notPersisted, ask(notKept::request).await().second.getOrThrow())
        val granted = WebStoragePersistence(storage(answering(false), answering(true)))
        assertEquals(PersistenceState.persisted, ask(granted::request).await().second.getOrThrow())
    }

    @Test
    fun missingApisAnswerUnsupportedAfterTheCallReturns() = runTest {
        for (persistence in
            listOf(
                WebStoragePersistence(null),
                WebStoragePersistence(storage(undefined, undefined)),
                WebStoragePersistence(storage(answering(true), undefined)),
            )) {
            val (afterReturn, answer) = ask(persistence::request).await()
            assertTrue(afterReturn)
            assertEquals(PersistenceState.unsupported, answer.getOrThrow())
        }
        val (afterReturn, answer) = ask(WebStoragePersistence(null)::state).await()
        assertTrue(afterReturn)
        assertEquals(PersistenceState.unsupported, answer.getOrThrow())
    }

    @Test
    fun aRejectedCallFailsWithItsDomExceptionName() = runTest {
        val persistence = WebStoragePersistence(storage(rejecting("SecurityError"), rejecting("NotAllowedError")))
        val state = (ask(persistence::state).await().second.exceptionOrNull() as Refused).error
        assertEquals(PortErrorKind.failed, state.kind)
        assertEquals("DOMException", state.platform?.domain)
        assertEquals("SecurityError", state.platform?.code)
        val request = (ask(persistence::request).await().second.exceptionOrNull() as Refused).error
        assertEquals("NotAllowedError", request.platform?.code)
    }

    @Test
    fun aThrowingCallFailsAfterTheCallReturns() = runTest {
        val throwing: dynamic = { throw IllegalStateException("broken") }
        val (afterReturn, answer) = ask(WebStoragePersistence(storage(throwing, throwing))::state).await()
        assertTrue(afterReturn)
        assertEquals(PortErrorKind.failed, (answer.exceptionOrNull() as Refused).error.kind)
    }
}
