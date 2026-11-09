package app.powerlog.application.fakes

import app.powerlog.domain.settings.PersistenceState
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeStoragePersistenceTest {
    private val queue = FakePortQueue()

    @Test
    fun aGrantedRequestPersistsTheStorage() {
        val persistence = FakeStoragePersistence(queue::post)
        val before = ReplyProbe<PersistenceState>()
        val request = ReplyProbe<PersistenceState>()

        persistence.state(before.reply)
        persistence.request(request.reply)
        queue.drain()

        assertEquals(listOf(PersistenceState.notPersisted), before.values)
        assertEquals(listOf(PersistenceState.persisted), request.values)
        assertEquals(1, persistence.requests)
    }

    @Test
    fun withoutPersistBothAnswersAreUnsupported() {
        val persistence = FakeStoragePersistence(queue::post, state = PersistenceState.unsupported)
        val state = ReplyProbe<PersistenceState>()
        val request = ReplyProbe<PersistenceState>()

        persistence.state(state.reply)
        persistence.request(request.reply)
        queue.drain()

        assertEquals(listOf(PersistenceState.unsupported), state.values)
        assertEquals(listOf(PersistenceState.unsupported), request.values)
    }
}
