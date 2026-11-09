package app.powerlog.application

import app.powerlog.application.port.Clock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
internal class Deadlines(private val clock: Clock) {
    private val wake = AtomicReference(CompletableDeferred<Unit>())

    fun poke() {
        wake.exchange(CompletableDeferred()).complete(Unit)
    }

    suspend fun until(monoUs: Long) {
        await<Unit>(null, monoUs)
    }

    suspend fun <T> await(answer: Deferred<T>?, monoUs: Long): T? {
        while (true) {
            if (answer?.isCompleted == true) return answer.await()
            val leftUs = monoUs - clock.monoUs()
            if (leftUs <= 0) return null
            val woken = wake.load()
            select {
                answer?.onAwait {}
                woken.onAwait {}
                onTimeout((leftUs + 999) / 1_000) {}
            }
        }
    }
}
