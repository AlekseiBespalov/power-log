package app.powerlog.application.slice

import app.powerlog.application.launch.ApplicationStores
import app.powerlog.application.port.Clock
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.Commit
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.Step
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.application.store.TelemetryPage
import app.powerlog.application.store.WriteBatch
import app.powerlog.data.JvmStores
import app.powerlog.data.MemoryMeasurements
import app.powerlog.data.StoreExecutors
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge

class ScenarioArchive(dispatcher: CoroutineDispatcher, private val clock: Clock) {
    val attempts = mutableListOf<WriteBatch>()
    val attemptedAtUs = mutableListOf<Long>()
    val committed = mutableListOf<WriteBatch>()
    val committedAtUs = mutableListOf<Long>()
    val journal = mutableListOf<String>()
    var telemetryReads = 0
    val telemetryRanges = mutableListOf<TimeRange>()
    var logReadFails = false
    val log = mutableListOf<LogLine>()

    var onOpen: suspend () -> StoreResult<StoreOpen>? = { null }
    var onCommit: suspend (WriteBatch) -> StoreResult<Commit>? = { null }
    var onOpenRide: suspend () -> StoreResult<RideRecord?>? = { null }
    var onDueRides: suspend (Long) -> StoreResult<List<RideId>>? = { null }
    var lastFact: StoreResult<RetainedEnd?>? = null
    private val otherTabNotices = MutableSharedFlow<CommitNotice>(extraBufferCapacity = 64)

    private val logRepository =
        object : DiagnosticLogRepository {
            override suspend fun append(line: LogLine) {
                log += line
            }

            override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
                if (logReadFails) StoreResult.Failed(StorageError(StorageError.Kind.io))
                else StoreResult.Ok(LogChunk(ByteArray(0), null))
        }

    private val raw =
        JvmStores.inMemory(
            StoreExecutors(dispatcher, dispatcher, dispatcher, dispatcher, dispatcher),
            logRepository,
            clock::read,
        )

    val stores: ApplicationStores =
        ApplicationStores(
            host =
                object : StoreHost by raw.host {
                    override suspend fun open(): StoreResult<StoreOpen> = onOpen() ?: raw.host.open()
                },
            writer =
                object : ArchiveWriter by raw.writer {
                    override val commits: Flow<CommitNotice> = merge(raw.writer.commits, otherTabNotices)

                    override val historyCommits: Flow<CommitNotice> = merge(raw.writer.historyCommits, otherTabNotices)

                    override suspend fun commit(batch: WriteBatch): StoreResult<Commit> {
                        attempts += batch
                        attemptedAtUs += clock.monoUs()
                        val result = onCommit(batch) ?: raw.writer.commit(batch)
                        if ((result as? StoreResult.Ok)?.value is Commit.Committed) {
                            committed += batch
                            committedAtUs += clock.monoUs()
                            journal += batch.mutations.joinToString { it::class.simpleName.orEmpty() }
                        }
                        return result
                    }
                },
            rides =
                object : RideRepository by raw.rides {
                    override suspend fun openRide(): StoreResult<RideRecord?> = onOpenRide() ?: raw.rides.openRide()
                },
            measurements =
                object : MeasurementRepository by raw.measurements {
                    override suspend fun telemetry(
                        rideId: RideId,
                        range: TimeRange,
                        after: TelemetryKey?,
                        limit: Int,
                    ): StoreResult<TelemetryPage> =
                        raw.measurements.telemetry(rideId, range, after, limit).also {
                            telemetryReads++
                            telemetryRanges += range
                        }

                    override suspend fun lastFact(rideId: RideId): StoreResult<RetainedEnd?> =
                        lastFact ?: raw.measurements.lastFact(rideId)
                },
            connection = raw.connection,
            maintenance =
                object : MaintenanceRepository by raw.maintenance {
                    override suspend fun checkpoint(truncate: Boolean): StoreResult<Unit> =
                        raw.maintenance.checkpoint(truncate).also { journal += "checkpoint(truncate = $truncate)" }

                    override suspend fun cleanupStep(rideId: RideId, maxRows: Int): StoreResult<Step> =
                        raw.maintenance.cleanupStep(rideId, maxRows).also { journal += "cleanupStep(${rideId.value})" }

                    override suspend fun reclaimStep(): StoreResult<Step> =
                        raw.maintenance.reclaimStep().also { journal += "reclaimStep" }
                },
            health =
                object : HealthRepository by raw.health {
                    override suspend fun dueRides(nowUtcUs: Long): StoreResult<List<RideId>> =
                        onDueRides(nowUtcUs) ?: raw.health.dueRides(nowUtcUs)
                },
            settings = raw.settings,
            cache =
                object : CacheRepository by raw.cache {
                    override suspend fun dropRide(rideId: RideId) {
                        raw.cache.dropRide(rideId)
                        journal += "dropRide(${rideId.value})"
                    }
                },
            memory = MemoryMeasurements(),
            log = logRepository,
        )

    fun holdNext(match: (WriteBatch) -> Boolean): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>()
        var taken = false
        onCommit = { batch ->
            if (!taken && match(batch)) {
                taken = true
                held.await()
            }
            null
        }
        return held
    }

    suspend fun otherTab(vararg batches: WriteBatch, notify: Boolean = true) {
        for (batch in batches) {
            val committed = assertIs<Commit.Committed>(assertIs<StoreResult.Ok<Commit>>(raw.writer.commit(batch)).value)
            if (notify)
                otherTabNotices.emit(
                    CommitNotice(
                        committed.revisions,
                        committed.historyRevision,
                        committed.revisions.keys,
                        otherTab = true,
                    )
                )
        }
    }

    suspend fun seed(vararg batches: WriteBatch) {
        assertIs<StoreOpen.Opened>(assertIs<StoreResult.Ok<StoreOpen>>(raw.host.open()).value)
        for (batch in batches) {
            val result = raw.writer.commit(batch)
            assertIs<Commit.Committed>(assertIs<StoreResult.Ok<Commit>>(result, "$result for $batch").value)
        }
    }
}

object ScenarioStores {
    fun inMemory(dispatcher: CoroutineDispatcher, clock: Clock): ScenarioArchive = ScenarioArchive(dispatcher, clock)
}
