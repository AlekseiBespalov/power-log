package app.powerlog.application.api

import app.powerlog.application.port.Clock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalAtomicApi::class)
internal class Published<T>(initial: T, private val intervalUs: Long, private val clock: Clock) {
    private class Version<T>(val revision: Long, val value: T, val urgent: Boolean)

    private val versions = MutableStateFlow(Version(1, initial, urgent = true))
    private val answered = AtomicLong(0L)
    private val answeredMonoUs = AtomicLong(NEVER)

    val value: T
        get() = versions.value.value

    fun publish(value: T, urgent: Boolean) {
        versions.update { current ->
            if (current.value == value) current
            else Version(current.revision + 1, value, urgent || current.urgent && current.revision > answered.load())
        }
    }

    suspend fun next(after: Long?): StreamAnswer<T> {
        var version = versions.first { after == null || it.revision > after }
        val last = answeredMonoUs.load()
        if (after != null && !version.urgent && last != NEVER) {
            val waitUs = last + intervalUs - clock.monoUs()
            if (waitUs > 0) {
                withTimeoutOrNull((waitUs + 999) / 1_000) { versions.first { it.urgent && it.revision > after } }
                version = versions.value
            }
        }
        while (true) {
            val seen = answered.load()
            if (seen >= version.revision || answered.compareAndSet(seen, version.revision)) break
        }
        answeredMonoUs.store(clock.monoUs())
        return StreamAnswer.Value(version.revision, version.value)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
