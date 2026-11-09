package app.powerlog.application.exports

import app.powerlog.application.Executors
import app.powerlog.application.Logs
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.failure
import app.powerlog.application.api.newId
import app.powerlog.application.api.readFailure
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.store.Extent
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RideRules
import app.powerlog.domain.exports.ExportPrint
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.withContext

internal class ExportFailed(val failure: ApiFailure) : Exception(failure.code.name)

internal class ExportSession(
    val id: String,
    val source: ExportSource,
    val kind: ExportKind,
    val power: FitPowerSource,
    val platform: Platform,
    val store: MeasurementRepository,
    val rideId: RideId,
    val revision: Long?,
    val record: RideRecord?,
    val extent: Extent?,
    val sessions: Map<Int, ControllerSession>,
    val events: List<RideEvent>,
    val consumption: ConsumptionAvailability,
) {
    val ride: Ride?
        get() = record?.ride

    val endUs: Long
        get() = record?.ride?.let { (it.end ?: it.checkpoint).elapsedUs } ?: 0L
}

@OptIn(ExperimentalAtomicApi::class)
internal class Exports(
    private val executors: Executors,
    private val rides: RideRepository,
    private val archive: MeasurementRepository,
    private val memory: MemoryMeasurementRepository,
    private val device: DeviceInfo,
    private val logs: Logs,
    private val refuse: () -> Unit,
) : ExportSessions {
    private val open = AtomicReference<Map<String, ExportSession>>(emptyMap())
    private val states = AtomicReference<Map<String, ProjectionState>>(emptyMap())

    override fun exporting(): Set<RideId> =
        open.load().values.mapNotNullTo(mutableSetOf()) { (it.source as? ExportSource.Ride)?.rideId }

    suspend fun open(input: ExportOpenInput): Outcome<ExportSessionView> =
        withContext(executors.reads) {
            try {
                val session =
                    when (val source = input.source) {
                        is ExportSource.Ride -> ride(source, input)
                        is ExportSource.Imported -> imported(source, input)
                    }
                add(session)
                Outcome.Ok(view(session))
            } catch (failed: ExportFailed) {
                Outcome.Failed(failed.failure)
            }
        }

    suspend fun page(input: ExportPageInput): Outcome<ExportPage> =
        withContext(executors.scans) {
            val session = open.load()[input.sessionId] ?: return@withContext Outcome.Cancelled
            val pages = ExportPages(session, { read(it) }, ::state)
            try {
                if (input.projection !in projections(session)) throw ExportFailed(failure(FailureCode.unknown))
                check(session)
                val page = pages.page(input.projection, input.after)
                if (page.done) check(session)
                if (open.load()[input.sessionId] == null) Outcome.Cancelled else Outcome.Ok(page)
            } catch (failed: ExportFailed) {
                if (open.load()[input.sessionId] == null) Outcome.Cancelled else Outcome.Failed(failed.failure)
            }
        }

    suspend fun close(input: ExportCloseInput): Outcome<Unit> =
        withContext(executors.reads) {
            var removed: ExportSession? = null
            while (true) {
                val current = open.load()
                val session = current[input.sessionId] ?: break
                if (open.compareAndSet(current, current - input.sessionId)) {
                    removed = session
                    break
                }
            }
            while (true) {
                val current = states.load()
                val next = current.filterKeys { !it.startsWith("${input.sessionId}/") }
                if (next.size == current.size || states.compareAndSet(current, next)) break
            }
            val code = input.failure
            if (removed != null && code != null)
                logs.event(LogLevel.warning, "failure", "source" to "exports", "error" to code)
            Outcome.Ok(Unit)
        }

    private fun add(session: ExportSession) {
        while (true) {
            val current = open.load()
            if (open.compareAndSet(current, current + (session.id to session))) return
        }
    }

    private fun state(session: ExportSession, projection: String, change: (ProjectionState?) -> ProjectionState?) {
        val key = "${session.id}/$projection"
        update(key, change)
        // close removes the session before its states, so a state written after that removal is dropped here
        if (open.load()[session.id] == null) update(key) { null }
    }

    private fun update(key: String, change: (ProjectionState?) -> ProjectionState?) {
        while (true) {
            val current = states.load()
            val next = change(current[key])
            val updated = if (next == null) current - key else current + (key to next)
            if (states.compareAndSet(current, updated)) return
        }
    }

    private suspend fun ride(source: ExportSource.Ride, input: ExportOpenInput): ExportSession {
        val record = read(rides.ride(source.rideId)) ?: throw ExportFailed(failure(FailureCode.rideDeleted))
        val ride = record.ride
        if (!RideRules.exportReady(ride)) throw ExportFailed(failure(FailureCode.exportNotReady))
        val extent = read(archive.extent(ride.id)) ?: throw ExportFailed(failure(FailureCode.rideDeleted))
        val events = read(archive.events(ride.id))
        val endUs = (ride.end ?: ride.checkpoint).elapsedUs
        if (input.kind != ExportKind.zip && (endUs > ExportLimits.maxRideUs || events.size > ExportLimits.maxEvents))
            throw ExportFailed(failure(FailureCode.exportLimit))
        val consumption =
            when {
                extent.telemetry == null -> ConsumptionAvailability.Unavailable(NO_TELEMETRY)
                events.count { it.kind != RideEventKind.lap } > ExportLimits.maxEvents ->
                    ConsumptionAvailability.Unavailable(TOO_MANY_BOUNDARIES)
                else -> ConsumptionAvailability.Available
            }
        return ExportSession(
            id = newId(),
            source = source,
            kind = input.kind,
            power = input.fitPower,
            platform = platform(ride.recordedBy),
            store = archive,
            rideId = ride.id,
            revision = extent.revision,
            record = record,
            extent = extent,
            sessions = read(archive.sessions(ride.id)).associateBy { it.sessionNo },
            events = events,
            consumption = consumption,
        )
    }

    private suspend fun imported(source: ExportSource.Imported, input: ExportOpenInput): ExportSession {
        if (input.kind != ExportKind.csv) throw ExportFailed(failure(FailureCode.unknown))
        val extent = read(memory.extent(source.importId)) ?: throw ExportFailed(failure(FailureCode.rideDeleted))
        return ExportSession(
            id = newId(),
            source = source,
            kind = input.kind,
            power = input.fitPower,
            platform = device.snapshot().platform,
            store = memory,
            rideId = source.importId,
            revision = null,
            record = null,
            extent = extent,
            sessions = read(memory.sessions(source.importId)).associateBy { it.sessionNo },
            events = emptyList(),
            consumption = ConsumptionAvailability.Unavailable(NO_TELEMETRY),
        )
    }

    private suspend fun view(session: ExportSession): ExportSessionView {
        val record = session.record
        if (record == null) {
            val elapsedEnd = ExportPages.importedEnd(memory, session.rideId) { read(it) }
            return ExportSessionView(
                session.id,
                session.platform,
                null,
                elapsedEnd,
                elapsedEnd,
                ExportProducers(emptyList(), emptyList()),
                session.consumption,
            )
        }
        val ride = record.ride
        val end = requireNotNull(ride.end)
        val checkpoint = ride.checkpoint
        val timeline = ActiveTimeline(end.elapsedUs, session.events)
        val extent = requireNotNull(session.extent)
        return ExportSessionView(
            sessionId = session.id,
            platform = session.platform,
            metadata =
                ExportRideMetadata(
                    batteryConsumptionSource = ride.options.batteryMethod,
                    startedAt = ExportPrint.timestamp(ride.startedUtcUs),
                    endedAt = ExportPrint.timestamp(end.utcUs),
                    ownerTiming =
                        ExportOwnerTiming(
                            ExportPrint.timestamp(checkpoint.utcUs),
                            ExportPrint.seconds(checkpoint.elapsedUs),
                            ExportPrint.seconds(checkpoint.timerUs),
                        ),
                    indoor = ride.options.indoor,
                    interrupted = ride.interrupted,
                    watchEnabled = ride.options.useWatch,
                    saveToHealth = ride.options.saveToHealth,
                    recordGPS = ride.options.recordGps,
                    health =
                        ExportHealth(
                            ExportPrint.provider(session.platform),
                            record.health.outcome,
                            record.health.workoutId,
                        ),
                    example = ride.example,
                    sampleHz = ride.options.sampleRate.hz,
                ),
            elapsedEnd = ExportPrint.seconds(end.elapsedUs),
            timerSeconds = ExportPrint.seconds(timeline.timerUs),
            producers =
                ExportProducers(
                    if (extent.locations != null) listOf(Producer.phone) else emptyList(),
                    listOf(Producer.phone, Producer.watch).filter { it in extent.healthProducers },
                ),
            consumption = session.consumption,
        )
    }

    private suspend fun check(session: ExportSession) {
        val revision = session.revision ?: return
        val extent = read(session.store.extent(session.rideId)) ?: throw ExportFailed(failure(FailureCode.rideDeleted))
        if (extent.revision != revision) throw ExportFailed(failure(FailureCode.exportChanged))
    }

    private fun projections(session: ExportSession): List<String> =
        if (session.source is ExportSource.Imported) listOf(ExportPages.TELEMETRY)
        else ExportPages.projections.getValue(session.kind)

    private fun <T> read(result: StoreResult<T>): T =
        when (result) {
            is StoreResult.Ok -> result.value
            is StoreResult.Failed -> {
                val mapped = readFailure(result.error)
                if (mapped.code == FailureCode.storageRefused) refuse()
                throw ExportFailed(mapped)
            }
        }

    private fun platform(device: Device): Platform =
        when (device) {
            Device.iphone,
            Device.appleWatch -> Platform.ios
            Device.androidPhone -> Platform.android
            Device.browser -> Platform.web
        }

    private companion object {
        const val NO_TELEMETRY = "No controller telemetry."
        const val TOO_MANY_BOUNDARIES = "More than 10,000 activity-boundary events."
    }
}

internal object ExportLimits {
    const val maxRideUs = 2_678_400_000_000L
    const val maxEvents = 10_000
}
