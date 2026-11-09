package app.powerlog.application.history

import app.powerlog.application.Executors
import app.powerlog.application.PagePolicy
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.Step
import app.powerlog.application.store.StoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal class Maintenance(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val repository: MaintenanceRepository,
    private val cache: CacheRepository,
    private val writer: ArchiveWriter,
) {
    private val wanted = Channel<Unit>(Channel.CONFLATED)

    fun watch() {
        scope.launch(executors.maintenance, CoroutineStart.UNDISPATCHED) {
            writer.historyCommits.filter { !it.otherTab }.collect { wanted.trySend(Unit) }
        }
        scope.launch(executors.maintenance) { for (signal in wanted) clean() }
        wanted.trySend(Unit)
    }

    private suspend fun clean() {
        val rides = (repository.pendingDeletions() as? StoreResult.Ok)?.value ?: return
        if (rides.isEmpty()) return
        for (rideId in rides) {
            cache.dropRide(rideId)
            if (!steps { repository.cleanupStep(rideId, PagePolicy.rows) }) return
            cache.dropRide(rideId)
        }
        steps { repository.reclaimStep() }
    }

    private suspend fun steps(step: suspend () -> StoreResult<Step>): Boolean {
        while (true) {
            when (val result = step()) {
                is StoreResult.Failed -> return false
                is StoreResult.Ok -> if (result.value == Step.done) return true
            }
            yield()
        }
    }
}
