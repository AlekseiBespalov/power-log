package app.powerlog.application.health

import app.powerlog.application.Executors
import app.powerlog.application.StoreFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.commitFailure
import app.powerlog.application.api.failure
import app.powerlog.application.api.readFailure
import app.powerlog.application.orThrow
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthOriginal
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.PublicationRules
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAtomicApi::class)
internal class Health(
    private val scope: CoroutineScope,
    private val executors: Executors,
    private val clock: Clock,
    private val provider: HealthProvider?,
    private val rides: RideRepository,
    private val repository: HealthRepository,
    measurements: MeasurementRepository,
    private val writer: ArchiveWriter,
    private val scheduler: BackgroundScheduler?,
    device: DeviceInfo,
) {
    private val android = provider?.kind == HealthProviderKind.healthConnect
    private val readback = provider?.let { HealthReadback(clock, it, rides, repository, measurements, writer) }

    suspend fun samples(ride: Ride, originals: List<HealthOriginal>): List<HealthSample> =
        readback?.samples(ride, originals).orEmpty()

    private val work =
        provider
            ?.takeIf { android }
            ?.let {
                HealthConnectWork(
                    clock,
                    it,
                    rides,
                    repository,
                    measurements,
                    writer,
                    requireNotNull(readback),
                    scheduler,
                    device,
                )
            }
    private val timers = mutableMapOf<RideId, Job>()
    private val foreground = AtomicBoolean(true)

    val publisher: HealthPublisher? = provider?.let {
        HealthPublisher(
            scope,
            executors,
            clock,
            it,
            rides,
            repository,
            measurements,
            writer,
            requireNotNull(readback),
            scheduler,
            ::arm,
            ::interruptedAtLaunch,
        )
    }

    suspend fun refresh(rideId: RideId): Outcome<Unit> {
        val readback = readback ?: return Outcome.Failed(failure(FailureCode.actionUnavailable))
        return withContext(executors.health) {
            guarded {
                val record =
                    rides.ride(rideId).orThrow() ?: return@guarded Outcome.Failed(failure(FailureCode.rideDeleted))
                if (record.ride.phase != RidePhase.completed)
                    return@guarded Outcome.Failed(failure(FailureCode.actionUnavailable))
                if (!android && provider?.access?.availability() != HealthAvailability.available)
                    return@guarded Outcome.Failed(failure(FailureCode.healthUnavailable))
                if (!android) readback.settle(record)
                if (!readback.hasRows(rideId)) readback.schedule(record.ride)
                readback.reset(rideId, clock.utcUs())
                if (android) scheduler?.schedule(rideId.value, null, ScheduleKind.replace)
                else {
                    readback.refusal(rideId)
                    arm(rideId, readback.run(rideId, background = false))
                    when (readback.refusal(rideId)) {
                        app.powerlog.application.port.PortErrorKind.denied ->
                            return@guarded Outcome.Failed(failure(FailureCode.healthDenied))
                        app.powerlog.application.port.PortErrorKind.locked ->
                            return@guarded Outcome.Failed(failure(FailureCode.healthLocked))
                        app.powerlog.application.port.PortErrorKind.unsupported ->
                            return@guarded Outcome.Failed(failure(FailureCode.healthUnavailable))
                        else -> Unit
                    }
                }
                Outcome.Ok(Unit)
            }
        }
    }

    suspend fun retrySave(rideId: RideId): Outcome<Unit> {
        val readback = readback?.takeIf { android } ?: return Outcome.Failed(failure(FailureCode.actionUnavailable))
        return withContext(executors.health) {
            guarded {
                val record =
                    rides.ride(rideId).orThrow() ?: return@guarded Outcome.Failed(failure(FailureCode.rideDeleted))
                val retried = record.publish.filter { PublicationRules.retryAllowed(it.state, it.reason) }
                if (!record.ride.options.saveToHealth || retried.isEmpty())
                    return@guarded Outcome.Failed(failure(FailureCode.actionUnavailable))
                val reset = retried.map {
                    it.copy(state = ComponentState.pending, attempts = 0, dueUtcUs = null, reason = null)
                }
                val committed =
                    writer.commit(
                        WriteBatch(
                            listOf(
                                Mutation.PutHealthWork(reset),
                                Mutation.SaveHealthOutcome(
                                    rideId,
                                    record.health.copy(outcome = HealthOutcome.pending, reason = null),
                                ),
                            )
                        )
                    )
                if (committed is StoreResult.Failed) return@guarded Outcome.Failed(commitFailure(committed.error))
                readback.reset(rideId, clock.utcUs())
                scheduler?.schedule(rideId.value, null, ScheduleKind.replace)
                Outcome.Ok(Unit)
            }
        }
    }

    suspend fun runHealthWork(rideId: String, attempt: Int): HealthWorkResult {
        val work = work ?: return HealthWorkResult.success
        return withContext(executors.health) { work.run(RideId(rideId), attempt, background = !foreground.load()) }
    }

    fun launched(userInterface: Boolean) {
        if (readback == null) return
        scope.launch(executors.health) {
            if (!android) orphan()
            if (!android || userInterface) inProcess()
        }
    }

    fun interruptedAtLaunch(rideId: RideId) {
        if (android || provider?.session == null) return
        scope.launch(executors.health) { builderLost(rideId) }
    }

    fun foregroundChanged(foreground: Boolean) {
        this.foreground.store(foreground)
        if (!foreground || readback == null) return
        scope.launch(executors.health) { inProcess() }
    }

    private suspend fun inProcess() {
        val readback = readback ?: return
        val nowUtcUs = clock.utcUs()
        if (android)
            (repository.deferredRides() as? StoreResult.Ok)?.value?.forEach {
                runCatching { readback.dueNow(it, nowUtcUs) }
            }
        val horizonUtcUs = if (android) nowUtcUs else nowUtcUs + HealthPolicy.readbackAfterFinishUs.last()
        val rides = (repository.dueRides(horizonUtcUs) as? StoreResult.Ok)?.value ?: return
        for (rideId in rides) arm(rideId, runCatching { readback.run(rideId, background = false) }.getOrNull())
    }

    fun deleted(rideId: RideId) {
        publisher?.deleted(rideId) ?: scheduler?.cancel(rideId.value)
        scope.launch(executors.health) { timers.remove(rideId)?.cancel() }
    }

    private fun arm(rideId: RideId, dueUtcUs: Long?) {
        val readback = readback ?: return
        scope.launch(executors.health) {
            timers.remove(rideId)?.cancel()
            if (dueUtcUs == null || android && !foreground.load()) return@launch
            timers[rideId] =
                scope.launch(executors.health) {
                    delay(maxOf(0L, dueUtcUs - clock.utcUs()) / 1_000)
                    timers.remove(rideId)
                    if (android && !foreground.load()) return@launch
                    arm(rideId, runCatching { readback.run(rideId, background = false) }.getOrNull())
                }
        }
    }

    private suspend fun orphan() {
        val session = provider?.session ?: return
        val orphan = (called<String?> { session.recoverOrphan(it) } as? Called.Ok)?.value ?: return
        builderLost(RideId(orphan))
    }

    private suspend fun builderLost(rideId: RideId) {
        val record = (rides.ride(rideId) as? StoreResult.Ok)?.value ?: return
        if (record.ride.options.healthWriter != app.powerlog.domain.core.health.HealthWriter.phone) return
        if (record.health.outcome != HealthOutcome.pending) return
        val unsaved =
            record.publish
                .filter {
                    it.state != ComponentState.saved &&
                        (it.scope as HealthWorkScope.Publish).component != HealthComponent.readback
                }
                .map { it.copy(state = ComponentState.failed, reason = HealthReason.builderLost) }
        writer.commit(
            WriteBatch(
                listOf(
                    Mutation.SaveHealthOutcome(
                        rideId,
                        record.health.copy(outcome = HealthOutcome.unknown, reason = HealthReason.builderLost),
                    )
                ) + listOfNotNull(unsaved.takeIf { it.isNotEmpty() }?.let(Mutation::PutHealthWork))
            )
        )
    }

    private suspend fun guarded(block: suspend () -> Outcome<Unit>): Outcome<Unit> =
        try {
            block()
        } catch (failed: StoreFailure) {
            Outcome.Failed(readFailure(failed.failed.error))
        }
}
