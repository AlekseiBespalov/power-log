package app.powerlog.application.api

import app.powerlog.application.launch.LaunchPolicy
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalAtomicApi::class)
internal class LaunchGate {
    private val opened = CompletableDeferred<Unit>()
    private val refusal = AtomicReference<RefusalDetail?>(null)

    val refused: RefusalDetail?
        get() = refusal.load()

    fun open() {
        opened.complete(Unit)
    }

    var onRefused: () -> Unit = {}

    fun refuse(detail: RefusalDetail) {
        if (refusal.compareAndSet(null, detail)) onRefused()
        opened.complete(Unit)
    }

    suspend fun failure(): ApiFailure? {
        if (!opened.isCompleted && withTimeoutOrNull(LaunchPolicy.gateUs / 1_000) { opened.await() } == null)
            return failure(FailureCode.storageBusy)
        return refused?.let { failure(FailureCode.storageRefused, it) }
    }
}

internal class RefusingWriter(private val writer: ArchiveWriter, private val gate: LaunchGate) :
    ArchiveWriter by writer {
    override suspend fun commit(batch: WriteBatch): StoreResult<Commit> {
        val result = writer.commit(batch)
        if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.corrupt)
            gate.refuse(RefusalDetail.unreadable)
        return result
    }
}
