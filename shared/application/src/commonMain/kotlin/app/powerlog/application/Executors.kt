package app.powerlog.application

import kotlinx.coroutines.CoroutineDispatcher

public class Executors(
    public val ride: CoroutineDispatcher,
    public val health: CoroutineDispatcher,
    public val maintenance: CoroutineDispatcher,
    public val reads: CoroutineDispatcher,
) {
    public val scans: CoroutineDispatcher = reads.limitedParallelism(2)
}
