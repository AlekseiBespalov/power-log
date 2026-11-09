package app.powerlog.data

import kotlinx.coroutines.CoroutineDispatcher

public class StoreExecutors(
    public val archiveWriter: CoroutineDispatcher,
    public val archiveReads: CoroutineDispatcher,
    public val cacheWriter: CoroutineDispatcher,
    public val cacheReads: CoroutineDispatcher,
    public val log: CoroutineDispatcher,
)
