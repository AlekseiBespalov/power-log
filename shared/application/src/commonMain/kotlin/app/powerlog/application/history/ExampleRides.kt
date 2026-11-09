package app.powerlog.application.history

import app.powerlog.application.Executors
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.failure
import app.powerlog.application.api.readFailure
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import kotlinx.coroutines.withContext

internal class ExampleRides(
    private val executors: Executors,
    private val source: ExampleRideSource?,
    private val rides: RideRepository,
    private val writer: ArchiveWriter,
    private val refuse: (StoreResult.Failed) -> Unit,
) {
    suspend fun add(): Outcome<AddedRidesView> {
        val source = source ?: return Outcome.Failed(failure(FailureCode.actionUnavailable))
        return withContext(executors.maintenance) {
            var added = 0
            for (rideId in source.rideIds) {
                when (val stored = rides.ride(rideId)) {
                    is StoreResult.Failed -> return@withContext failed(stored)
                    is StoreResult.Ok -> if (stored.value != null) continue
                }
                var index = 0
                while (true) {
                    val batch = source.batch(rideId, index++) ?: break
                    when (val open = rides.openRide()) {
                        is StoreResult.Failed -> return@withContext failed(open)
                        is StoreResult.Ok ->
                            if (open.value != null) return@withContext Outcome.Failed(failure(FailureCode.rideActive))
                    }
                    when (val commit = writer.commit(batch)) {
                        is StoreResult.Failed -> return@withContext Outcome.Failed(commitFailure(commit.error))
                        is StoreResult.Ok ->
                            if (commit.value !is Commit.Committed)
                                return@withContext Outcome.Failed(failure(FailureCode.rideChanged))
                    }
                }
                added++
            }
            Outcome.Ok(AddedRidesView(added))
        }
    }

    private fun failed(read: StoreResult.Failed): Outcome<Nothing> {
        val mapped = readFailure(read.error)
        if (mapped.code == FailureCode.storageRefused) refuse(read)
        return Outcome.Failed(mapped)
    }
}
