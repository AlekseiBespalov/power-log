package app.powerlog.application.recording

import app.powerlog.application.CommitPolicy
import app.powerlog.application.Logs
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.occurrence
import app.powerlog.application.port.Clock
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.recording.LifecycleDecision
import app.powerlog.domain.recording.RideLifecycle
import app.powerlog.domain.recording.RideSignal
import kotlinx.coroutines.delay

internal class OpenRides(
    private val clock: Clock,
    private val rides: RideRepository,
    private val writer: ArchiveWriter,
    private val state: RecordingState,
    private val live: LivePublisher,
    private val events: RideEvents,
    private val logs: Logs,
) {
    sealed interface Read {
        data class Found(val ride: Ride?) : Read

        data class Failed(val error: StorageError) : Read
    }

    suspend fun read(stage: String): Read {
        while (true) {
            when (val read = rides.openRide()) {
                is StoreResult.Ok -> return Read.Found(read.value?.ride)
                is StoreResult.Failed -> {
                    if (read.error.kind != StorageError.Kind.busy) {
                        logs.storageFailed(read.error, stage)
                        return Read.Failed(read.error)
                    }
                    delay(CommitPolicy.busyRetryUs / 1_000)
                }
            }
        }
    }

    fun holds(stored: Ride?): Boolean = state.open?.id == stored?.id && state.open?.phase == stored?.phase

    fun restore(stored: Ride?) {
        state.lifecycle = RideLifecycle.restored(stored)
        state.interruptedInMemory = false
        live.rideChanged()
        stored?.let(events::rideRestored)
    }

    suspend fun interrupt(stored: Ride, atLaunch: Boolean): StorageError? {
        val decision =
            RideLifecycle.observe(RideLifecycle.restored(stored), RideSignal.ProcessRestarted, clock.read())
                as? LifecycleDecision.Accepted ?: return null
        val batch = WriteBatch(listOf(Mutation.SetPhase(stored.id, RidePhase.interrupted)))
        while (true) {
            val result = writer.commit(batch)
            var failed: StorageError? = null
            when (result) {
                is StoreResult.Ok -> if (result.value !is Commit.Committed) return null
                is StoreResult.Failed -> {
                    logs.storageFailed(result.error, if (atLaunch) "launch" else "command")
                    if (result.error.kind == StorageError.Kind.busy) {
                        delay(CommitPolicy.busyRetryUs / 1_000)
                        continue
                    }
                    if (atLaunch) return result.error
                    failed = result.error
                }
            }
            if (failed == null)
                logs.append(
                    logs.line(LogLevel.warning, "ridePhase", listOf(LogField("phase", LogValue.Text("interrupted"))))
                )
            state.lifecycle = decision.next
            state.interruptedInMemory = failed != null
            if (failed != null) state.failure = occurrence(commitFailure(failed).code, state.failure)
            live.rideChanged()
            events.rideRestored(requireNotNull(decision.saved))
            return null
        }
    }
}
