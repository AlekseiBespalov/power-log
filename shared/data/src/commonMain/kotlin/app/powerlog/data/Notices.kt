package app.powerlog.data

import app.powerlog.application.store.CommitNotice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class Notices {
    private val all = flow()
    private val history = flow()

    val commits: Flow<CommitNotice> = all.asSharedFlow()
    val historyCommits: Flow<CommitNotice> = history.asSharedFlow()

    fun emit(notice: CommitNotice, historyMoved: Boolean) {
        all.tryEmit(notice)
        if (historyMoved) history.tryEmit(notice)
    }

    private fun flow() =
        MutableSharedFlow<CommitNotice>(
            extraBufferCapacity = StorePolicy.noticeBuffer,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
}
