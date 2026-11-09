package app.powerlog.application

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield

// A dispatcher whose host must get a turn of its own between slices: the web's one event loop, where yield() only
// lets other coroutines run (60 §4.4).
public interface HostTurns {
    public suspend fun hostTurn()
}

public suspend inline fun inSlices(rows: Int, work: (from: Int, to: Int) -> Unit) {
    var from = 0
    while (from < rows) {
        val to = minOf(rows, from + YieldPolicy.rows)
        work(from, to)
        from = to
        if (from < rows) betweenSlices()
    }
}

@PublishedApi
internal suspend fun betweenSlices() {
    val host = currentCoroutineContext()[ContinuationInterceptor] as? HostTurns
    if (host != null) host.hostTurn() else yield()
}
