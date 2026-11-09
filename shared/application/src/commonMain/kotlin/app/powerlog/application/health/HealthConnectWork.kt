package app.powerlog.application.health

import app.powerlog.application.FactReader
import app.powerlog.application.FactSink
import app.powerlog.application.FactStreams
import app.powerlog.application.orThrow
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.Clock
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthQuantity
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.TimeRange
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.HealthPublication
import app.powerlog.domain.health.RoutePoint
import app.powerlog.domain.health.RouteReduction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HealthConnectWork(
    private val clock: Clock,
    private val provider: HealthProvider,
    private val rides: RideRepository,
    private val repository: HealthRepository,
    private val measurements: MeasurementRepository,
    private val writer: ArchiveWriter,
    private val readback: HealthReadback,
    private val scheduler: BackgroundScheduler?,
    private val device: DeviceInfo,
) {
    private class Page(
        val records: List<HealthConnectRecordPlan>,
        val components: Set<HealthComponent>,
        val next: String,
        val phaseEnds: Boolean,
    )

    private class Stop(val retry: Boolean) : Exception()

    private class Refused : Exception()

    private val runs = mutableMapOf<RideId, Mutex>()

    suspend fun run(rideId: RideId, attempt: Int, background: Boolean): HealthWorkResult {
        val lock = runs.getOrPut(rideId) { Mutex() }
        return try {
            lock.withLock { attempted(rideId, attempt, background) }
        } finally {
            if (!lock.isLocked && runs[rideId] === lock) runs.remove(rideId)
        }
    }

    private suspend fun attempted(rideId: RideId, attempt: Int, background: Boolean): HealthWorkResult =
        try {
            work(rideId, background)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Refused) {
            HealthWorkResult.failure
        } catch (_: Throwable) {
            if (attempt < HealthWorkPolicy.workerAttempts) HealthWorkResult.retry
            else {
                exhausted(rideId)
                HealthWorkResult.failure
            }
        }

    private suspend fun work(rideId: RideId, background: Boolean): HealthWorkResult {
        val record = rides.ride(rideId).orThrow() ?: return HealthWorkResult.success
        val ride = record.ride
        if (ride.phase != RidePhase.completed) return HealthWorkResult.success
        val end = ride.end ?: return HealthWorkResult.success
        if (end.utcUs <= ride.startedUtcUs) {
            clockFailure(record)
            return HealthWorkResult.success
        }
        uncertain(record)
        if (provider.access.availability() != HealthAvailability.available) {
            unavailable(rideId)
            return HealthWorkResult.success
        }
        if (repository.work(rideId).orThrow().none { it.scope is HealthWorkScope.Readback }) readback.schedule(ride)
        var retry = false
        val current = outcome(rideId)
        if (current?.outcome == HealthOutcome.pending && ride.options.healthWriter != null) {
            retry =
                try {
                    publish(rideId)
                    false
                } catch (stop: Stop) {
                    stop.retry
                }
        }
        val nextReadback = readback.run(rideId, background)
        continueLater(rideId, nextReadback)
        return if (retry) HealthWorkResult.retry else HealthWorkResult.success
    }

    private suspend fun continueLater(rideId: RideId, nextReadback: Long?) {
        val scheduler = scheduler ?: return
        val publishing =
            outcome(rideId)?.outcome == HealthOutcome.pending &&
                publishRows(rideId).any {
                    it.state == ComponentState.pending && component(it) != HealthComponent.readback
                }
        val notBefore =
            if (publishing) {
                val limited = publishRows(rideId).mapNotNull { it.dueUtcUs }.maxOrNull()
                maxOf(clock.utcUs() + HealthWorkPolicy.continueAfterUs, limited ?: Long.MIN_VALUE)
            } else nextReadback ?: return
        scheduler.schedule(rideId.value, notBefore, ScheduleKind.afterCurrent)
    }

    private suspend fun publish(rideId: RideId) {
        val grants =
            (called<HealthGrants> { provider.access.grants(it) } as? Called.Ok)?.value
                ?: throw IllegalStateException("Health Connect grants")
        var rows = publishRows(rideId)
        val record = rides.ride(rideId).orThrow() ?: return
        val ride = record.ride
        val end = ride.end ?: return
        val workout = rows.firstOrNull { it.scope == scope(HealthComponent.workout) } ?: return
        if (grants.write[HealthComponent.workout] != PermissionState.granted) {
            save(record, rows, listOf(workout.copy(state = ComponentState.failed, reason = HealthReason.permission)))
            return
        }
        val denied = rows.filter { row ->
            val component = component(row)
            row.state == ComponentState.pending &&
                component != HealthComponent.workout &&
                component != HealthComponent.readback &&
                !granted(grants, component)
        }
        if (denied.isNotEmpty())
            rows =
                save(
                    record,
                    rows,
                    denied.map { it.copy(state = ComponentState.unavailable, reason = HealthReason.permission) },
                )
        if (rows.any { (it.dueUtcUs ?: Long.MIN_VALUE) > clock.utcUs() && it.state == ComponentState.pending }) return
        val events = measurements.events(rideId).orThrow()
        val sessions = measurements.sessions(rideId).orThrow().associateBy { it.sessionNo }
        var cursor = rows.first { it.scope == scope(HealthComponent.workout) }.cursor
        repeat(HealthWorkPolicy.healthConnectPagesPerRun) {
            rows = publishRows(rideId)
            val writable = rows.filter { it.state == ComponentState.pending }.map(::component).toSet()
            val page = page(ride, end.utcUs, cursor, writable, sessions, events, grants) ?: return
            val workoutRow = rows.first { it.scope == scope(HealthComponent.workout) }
            if (page.records.isEmpty()) {
                rows = save(record, rows, listOf(workoutRow.copy(cursor = page.next)) + phaseEnd(rows, page))
                cursor = page.next
                return@repeat
            }
            val held = rows.filter { component(it) in page.components }
            rows = save(record, rows, held.map { it.copy(state = ComponentState.submitted) })
            when (val inserted = called<List<String>> { requireNotNull(provider.writer).insert(page.records, it) }) {
                is Called.Ok -> {
                    val session = page.records.any { it is HealthConnectRecordPlan.Session }
                    val written = held.map {
                        if (session) it.copy(state = ComponentState.saved, attempts = 0, dueUtcUs = null)
                        else it.copy(state = ComponentState.pending, attempts = 0, cursor = WRITTEN, dueUtcUs = null)
                    }
                    val moved = workoutRow.copy(cursor = page.next).takeIf { !session }
                    val rest =
                        if (session)
                            rows
                                .filter {
                                    it.state == ComponentState.pending &&
                                        it.scope !in held.map { h -> h.scope } &&
                                        component(it) != HealthComponent.readback
                                }
                                .map { it.copy(state = ComponentState.notRequested) }
                        else emptyList()
                    val finished =
                        if (page.phaseEnds)
                            phaseEnd(rows.map { r -> written.firstOrNull { it.scope == r.scope } ?: r }, page)
                        else emptyList()
                    val workoutId = if (session) inserted.value.lastOrNull() else null
                    rows =
                        save(
                            record,
                            rows,
                            merge(written, listOfNotNull(moved), rest, finished),
                            workoutId,
                        )
                    cursor = page.next
                    if (session) return
                }
                is Called.Failed -> {
                    failed(record, rows, held, inserted.error.kind)
                    return
                }
                Called.Abandoned -> {
                    save(
                        record,
                        rows,
                        held.map { it.copy(state = ComponentState.failed, reason = HealthReason.ambiguous) },
                    )
                    return
                }
            }
        }
    }

    private suspend fun failed(
        record: RideRecord,
        rows: List<HealthWork>,
        held: List<HealthWork>,
        kind: PortErrorKind,
    ) {
        when (kind) {
            PortErrorKind.rateLimited -> {
                val attempts = held.maxOf { it.attempts } + 1
                save(
                    record,
                    rows,
                    held.map {
                        if (attempts < HealthWorkPolicy.rateLimitAttempts)
                            it.copy(
                                state = ComponentState.pending,
                                attempts = attempts,
                                dueUtcUs = clock.utcUs() + HealthWorkPolicy.rateLimitRetryUs,
                            )
                        else
                            it.copy(
                                state = ComponentState.failed,
                                attempts = attempts,
                                reason = HealthReason.quota,
                                dueUtcUs = null,
                            )
                    },
                )
            }
            PortErrorKind.denied ->
                save(
                    record,
                    rows,
                    held.map { it.copy(state = ComponentState.failed, reason = HealthReason.permission) },
                )
            PortErrorKind.invalid ->
                save(record, rows, held.map { it.copy(state = ComponentState.failed, reason = HealthReason.invalid) })
            PortErrorKind.busy -> {
                save(record, rows, held.map { it.copy(state = ComponentState.pending) })
                throw Stop(retry = true)
            }
            PortErrorKind.locked ->
                save(record, rows, held.map { it.copy(state = ComponentState.failed, reason = HealthReason.locked) })
            else ->
                save(record, rows, held.map { it.copy(state = ComponentState.failed, reason = HealthReason.ambiguous) })
        }
    }

    private fun phaseEnd(rows: List<HealthWork>, page: Page): List<HealthWork> =
        if (!page.phaseEnds) emptyList()
        else
            rows
                .filter {
                    component(it) in phaseComponents(page.next) &&
                        it.state == ComponentState.pending &&
                        it.cursor == WRITTEN
                }
                .map { it.copy(state = ComponentState.saved, cursor = null) }

    private fun phaseComponents(next: String): Set<HealthComponent> =
        when {
            next.startsWith(DISTANCE) -> secondsComponents
            next == SESSION -> setOf(HealthComponent.cyclingDistance)
            else -> emptySet()
        }

    private suspend fun page(
        ride: Ride,
        endUtcUs: Long,
        cursor: String?,
        writable: Set<HealthComponent>,
        sessions: Map<Int, ControllerSession>,
        events: List<app.powerlog.domain.core.ride.RideEvent>,
        grants: HealthGrants,
    ): Page? {
        val seconds = (endUtcUs - ride.startedUtcUs + SECOND_US - 1) / SECOND_US
        return when {
            cursor == null || cursor.startsWith(SECONDS) -> {
                val n = cursor?.removePrefix(SECONDS)?.toLong() ?: 0L
                if (n >= seconds || secondsComponents.none { it in writable })
                    return Page(emptyList(), emptySet(), "${DISTANCE}0", phaseEnds = true)
                val last = minOf(seconds, n + SECONDS_PER_PAGE)
                val facts = facts(ride, TimeRange(n * SECOND_US - MARGIN_US, last * SECOND_US + MARGIN_US), sessions)
                val records = mutableListOf<HealthConnectRecordPlan>()
                for (second in n until last) {
                    val bounds = HealthPublication.bounds(ride.startedUtcUs, endUtcUs, second)
                    for (quantity in secondQuantities) {
                        if (component(quantity) !in writable) continue
                        HealthPublication.second(ride.id, bounds, quantity, facts.rows, sessions, facts.fixes)?.let {
                            records += HealthConnectRecordPlan.Record(it)
                        }
                    }
                }
                Page(
                    records,
                    records.map { component((it as HealthConnectRecordPlan.Record).plan.quantity) }.toSet(),
                    if (last >= seconds) "${DISTANCE}0" else "$SECONDS$last",
                    phaseEnds = last >= seconds,
                )
            }
            cursor.startsWith(DISTANCE) -> {
                val after = cursor.removePrefix(DISTANCE).toLong()
                if (HealthComponent.cyclingDistance !in writable)
                    return Page(emptyList(), emptySet(), SESSION, phaseEnds = true)
                val edges = edges(ride, after)
                if (edges.isEmpty()) return Page(emptyList(), emptySet(), SESSION, phaseEnds = true)
                val records = edges.mapNotNull { edge ->
                    HealthPublication.distance(ride.id, edge)?.let(HealthConnectRecordPlan::Record)
                }
                val more = edges.size >= HealthWorkPolicy.healthConnectRecordsPerPage
                Page(
                    records,
                    if (records.isEmpty()) emptySet() else setOf(HealthComponent.cyclingDistance),
                    if (more) "$DISTANCE${edges.last().lastSeq}" else SESSION,
                    phaseEnds = !more,
                )
            }
            cursor == SESSION -> {
                if (HealthComponent.workout !in writable) return null
                val timeline = ActiveTimeline(requireNotNull(ride.end).elapsedUs, events)
                val workout = HealthPublication.workout(ride, timeline)
                val route =
                    if (HealthComponent.route in writable && grants.route == PermissionState.granted)
                        route(ride, workout)
                    else emptyList()
                val session = HealthPublication.healthConnect(ride, timeline, route)
                val components = buildSet {
                    add(HealthComponent.workout)
                    add(HealthComponent.association)
                    if (route.isNotEmpty()) add(HealthComponent.route)
                    if (events.any { it.kind == RideEventKind.lap }) add(HealthComponent.laps)
                }
                    .filter { it in writable }
                    .toSet()
                Page(listOf(session), components, SESSION, phaseEnds = true)
            }
            else -> null
        }
    }

    private class Facts(val rows: TelemetryRows, val fixes: List<LocationFix>)

    private suspend fun facts(ride: Ride, range: TimeRange, sessions: Map<Int, ControllerSession>): Facts {
        val samples = mutableListOf<TelemetrySample>()
        val fixes = mutableListOf<LocationFix>()
        FactReader(measurements, ride.id, sessions)
            .feed(
                TimeRange(maxOf(0L, range.startUs), range.endUs),
                FactStreams(true, ride.options.recordGps, null),
                object : FactSink {
                    override fun append(
                        rows: TelemetryRows,
                        from: Int,
                        to: Int,
                        sessions: Map<Int, ControllerSession>,
                    ) {
                        for (row in from until to) samples += sample(rows, row)
                    }

                    override fun append(fact: Fact) {
                        if (fact is Fact.Location) fixes += fact.fix
                    }
                },
            )
        return Facts(TelemetryRows.of(samples), fixes)
    }

    private fun sample(rows: TelemetryRows, row: Int): TelemetrySample =
        TelemetrySample(
            app.powerlog.domain.core.ids.RideId(""),
            rows.sessionNo[row],
            rows.seq[row].toLong(),
            rows.elapsedUs[row].toLong(),
            rows.utcUs[row].toLong(),
            rows.activeInterval[row].takeIf { it != 0 },
            rows.run[row],
            rows.quanta
                .mapNotNull { (metric, values) -> values[row].takeIf { !it.isNaN() }?.let { metric to it.toLong() } }
                .toMap(),
        )

    private suspend fun edges(ride: Ride, afterSeq: Long): List<app.powerlog.domain.core.analysis.distance.GpsEdge> {
        val reducer = GpsDistanceReducer(null)
        val edges = mutableListOf<app.powerlog.domain.core.analysis.distance.GpsEdge>()
        val stop = object : Exception() {}
        try {
            FactReader(measurements, ride.id, emptyMap())
                .feed(
                    TimeRange(0, Long.MAX_VALUE),
                    FactStreams(false, true, null),
                    object : FactSink {
                        override fun append(
                            rows: TelemetryRows,
                            from: Int,
                            to: Int,
                            sessions: Map<Int, ControllerSession>,
                        ) = Unit

                        override fun append(fact: Fact) {
                            val fix = (fact as? Fact.Location)?.fix ?: return
                            val edge = reducer.append(fix) ?: return
                            if (edge.lastSeq <= afterSeq) return
                            edges += edge
                            if (edges.size >= HealthWorkPolicy.healthConnectRecordsPerPage) throw stop
                        }
                    },
                )
        } catch (thrown: Exception) {
            if (thrown !== stop) throw thrown
        }
        return edges
    }

    private suspend fun route(ride: Ride, workout: app.powerlog.domain.health.HealthPlan.Workout): List<RoutePoint> {
        val connect = requireNotNull(provider.writer)
        val snapshot = device.snapshot()
        val reduction =
            RouteReduction.HealthConnect(
                RouteReduction.HealthConnect.baseBytes(
                    workout,
                    connect.packageName,
                    connect.appLabel,
                    snapshot.manufacturer,
                    snapshot.model,
                ),
                HealthPolicy.healthConnectRouteBudgetBytes,
            )
        FactReader(measurements, ride.id, emptyMap())
            .feed(
                TimeRange(0, Long.MAX_VALUE),
                FactStreams(false, true, null),
                object : FactSink {
                    override fun append(
                        rows: TelemetryRows,
                        from: Int,
                        to: Int,
                        sessions: Map<Int, ControllerSession>,
                    ) = Unit

                    override fun append(fact: Fact) {
                        val fix = (fact as? Fact.Location)?.fix ?: return
                        if (fix.activeInterval == null) return
                        RoutePoint.of(fix)?.let { reduction.append(listOf(it)) }
                    }
                },
            )
        return reduction.result()
    }

    private suspend fun uncertain(record: RideRecord) {
        val submitted = publishRows(record.ride.id).filter { it.state == ComponentState.submitted }
        if (submitted.isEmpty()) return
        save(
            record,
            publishRows(record.ride.id),
            submitted.map { it.copy(state = ComponentState.failed, reason = HealthReason.ambiguous) },
        )
    }

    private suspend fun unavailable(rideId: RideId) {
        val record = rides.ride(rideId).orThrow() ?: return
        val rows = repository.work(rideId).orThrow()
        val publish =
            rows
                .filter {
                    it.scope is HealthWorkScope.Publish &&
                        component(it) != HealthComponent.readback &&
                        (it.state == ComponentState.pending || it.state == ComponentState.submitted)
                }
                .map { it.copy(state = ComponentState.failed, reason = HealthReason.invalid) }
        val ended =
            rows
                .filter { it.scope is HealthWorkScope.Readback && it.state == ComponentState.pending }
                .map {
                    it.copy(
                        state = ComponentState.unavailable,
                        reason = HealthReason.readbackUnavailable,
                        dueUtcUs = null,
                    )
                }
        val part =
            rows
                .firstOrNull { it.scope == scope(HealthComponent.readback) }
                ?.let { stored ->
                    val readbackRows =
                        rows
                            .filter { it.scope is HealthWorkScope.Readback }
                            .map { row ->
                                ended.firstOrNull { it.scope == row.scope } ?: row
                            }
                    HealthViews.readbackPart(readbackRows)?.let { stored.copy(state = it.state, reason = it.reason) }
                }
        save(record, publishRows(rideId), publish + listOfNotNull(part), readbackRows = ended)
    }

    private suspend fun clockFailure(record: RideRecord) {
        val current = outcome(record.ride.id) ?: record.health
        val observed = current.copy(outcome = HealthOutcome.unavailable, reason = HealthReason.clock)
        if (current == observed) return
        val result =
            writer.commit(
                WriteBatch(
                    listOf(
                        Mutation.RemoveHealthWork(record.ride.id, null),
                        Mutation.SaveHealthOutcome(record.ride.id, observed),
                    )
                )
            )
        if (result is StoreResult.Failed && result.error.kind == StorageError.Kind.corrupt) throw Refused()
    }

    private suspend fun exhausted(rideId: RideId) {
        val record = (rides.ride(rideId) as? StoreResult.Ok)?.value ?: return
        val rows =
            (repository.work(rideId) as? StoreResult.Ok)?.value?.filter { it.scope is HealthWorkScope.Publish }
                ?: return
        val changed = rows.mapNotNull {
            when (it.state) {
                ComponentState.pending ->
                    if (component(it) == HealthComponent.readback) null
                    else it.copy(state = ComponentState.failed, reason = HealthReason.transientFailure)
                ComponentState.submitted -> it.copy(state = ComponentState.failed, reason = HealthReason.ambiguous)
                else -> null
            }
        }
        if (changed.isNotEmpty()) save(record, rows, changed)
    }

    private suspend fun save(
        record: RideRecord,
        rows: List<HealthWork>,
        changed: List<HealthWork>,
        workoutId: String? = null,
        readbackRows: List<HealthWork> = emptyList(),
    ): List<HealthWork> {
        val next = rows.map { row -> changed.firstOrNull { it.scope == row.scope } ?: row }
        val current = outcome(record.ride.id) ?: record.health
        val folded = fold(current, next, workoutId)
        val mutations =
            listOfNotNull(
                (changed + readbackRows).takeIf { it.isNotEmpty() }?.let(Mutation::PutHealthWork),
                folded.takeIf { it != current }?.let { Mutation.SaveHealthOutcome(record.ride.id, it) },
            )
        if (mutations.isEmpty()) return next
        when (val result = writer.commit(WriteBatch(mutations))) {
            is StoreResult.Failed ->
                if (result.error.kind == StorageError.Kind.corrupt) throw Refused()
                else throw IllegalStateException("Health state batch ${result.error.kind}")
            is StoreResult.Ok ->
                if (result.value !is app.powerlog.application.store.Commit.Committed) throw Stop(retry = false)
        }
        return next
    }

    private fun fold(current: RideHealth, rows: List<HealthWork>, workoutId: String?): RideHealth {
        val workout = rows.firstOrNull { it.scope == scope(HealthComponent.workout) }
        val failed = rows.filter { it.state == ComponentState.failed && component(it) != HealthComponent.readback }
        return when {
            workout?.state == ComponentState.saved ->
                current.copy(outcome = HealthOutcome.saved, reason = null, workoutId = workoutId ?: current.workoutId)
            failed.any { it.reason == HealthReason.ambiguous } ->
                current.copy(outcome = HealthOutcome.unknown, reason = HealthReason.ambiguous)
            failed.isNotEmpty() -> current.copy(outcome = HealthOutcome.failed, reason = failed.first().reason)
            else -> current.copy(outcome = HealthOutcome.pending, reason = null)
        }
    }

    private fun merge(vararg lists: List<HealthWork>): List<HealthWork> {
        val merged = linkedMapOf<HealthWorkScope, HealthWork>()
        for (list in lists) for (row in list) merged[row.scope] = row
        return merged.values.toList()
    }

    private suspend fun outcome(rideId: RideId): RideHealth? = repository.outcome(rideId).orThrow()

    private suspend fun publishRows(rideId: RideId): List<HealthWork> =
        repository.work(rideId).orThrow().filter { it.scope is HealthWorkScope.Publish }

    private fun granted(grants: HealthGrants, component: HealthComponent): Boolean =
        if (component == HealthComponent.route) grants.route == PermissionState.granted
        else grants.write[component] == PermissionState.granted

    private fun component(row: HealthWork): HealthComponent = (row.scope as HealthWorkScope.Publish).component

    private fun scope(component: HealthComponent): HealthWorkScope = HealthWorkScope.Publish(component)

    private companion object {
        const val SECOND_US = 1_000_000L
        const val MARGIN_US = 10_000_000L
        const val SECONDS_PER_PAGE = 32L
        const val SECONDS = "seconds:"
        const val DISTANCE = "distance:"
        const val SESSION = "session"
        const val WRITTEN = "written"
        val secondQuantities =
            listOf(HealthQuantity.riderPower, HealthQuantity.cyclingCadence, HealthQuantity.cyclingSpeed)
        val secondsComponents =
            setOf(HealthComponent.riderPower, HealthComponent.cyclingCadence, HealthComponent.cyclingSpeed)

        fun component(quantity: HealthQuantity): HealthComponent =
            when (quantity) {
                HealthQuantity.riderPower -> HealthComponent.riderPower
                HealthQuantity.cyclingCadence -> HealthComponent.cyclingCadence
                HealthQuantity.cyclingSpeed -> HealthComponent.cyclingSpeed
                HealthQuantity.cyclingDistance -> HealthComponent.cyclingDistance
            }
    }
}
