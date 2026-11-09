package app.powerlog.application.port

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
public class Reply<in T>(private val onOk: (T) -> Unit, private val onFailed: (PortError) -> Unit) {
    // One compareAndSet across ok and failed: of racing completions on any threads, exactly one is delivered.
    private val settled = AtomicBoolean(false)

    public fun ok(value: T) {
        if (settled.compareAndSet(expectedValue = false, newValue = true)) onOk(value)
    }

    public fun failed(error: PortError) {
        if (settled.compareAndSet(expectedValue = false, newValue = true)) onFailed(error)
    }
}
