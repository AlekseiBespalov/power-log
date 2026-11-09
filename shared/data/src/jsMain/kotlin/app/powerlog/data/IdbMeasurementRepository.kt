package app.powerlog.data

import app.powerlog.application.store.Extent
import app.powerlog.application.store.HealthKey
import app.powerlog.application.store.HealthSamplePage
import app.powerlog.application.store.HealthSourceFilter
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.LocationPage
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.NearestTarget
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.application.store.TelemetryPage
import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.TimeRange

private const val NEGATIVE = Double.NEGATIVE_INFINITY
private const val POSITIVE = Double.POSITIVE_INFINITY

internal class IdbMeasurementRepository(private val archive: IdbArchive) : MeasurementRepository {
    override suspend fun extent(rideId: RideId): StoreResult<Extent?> =
        archive.read(listOf(IdbObjectStore.ride, IdbObjectStore.facts)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                if (!isLive(record)) return@request answer(null)
                val end = store(IdbObjectStore.facts).index("end")
                val requests =
                    listOf(IdbFactKind.telemetry, IdbFactKind.location).flatMap { kind ->
                        val range = factRange(rideId, kind, NEGATIVE)
                        listOf(end.openCursor(range, "next"), end.openCursor(range, "prev"))
                    }
                all(requests) { cursors ->
                    fun range(first: dynamic, last: dynamic): TimeRange? =
                        if (first == null) null else TimeRange(long(first.value.firstUs), long(last.value.lastUs) + 1)
                    answer(
                        Extent(
                            revision = long(record.dataRevision),
                            telemetry = range(cursors[0], cursors[1]),
                            locations = range(cursors[2], cursors[3]),
                            health = null,
                            healthMetrics = emptySet(),
                            healthProducers = emptySet(),
                        )
                    )
                }
            }
        }

    override suspend fun sessions(rideId: RideId): StoreResult<List<ControllerSession>> =
        rows(rideId, IdbObjectStore.controllerSession) { row ->
            val identity =
                ControllerIdentity(
                    DecoderId(row.decoder.unsafeCast<String>()),
                    row.model.unsafeCast<String>(),
                    row.firmware.unsafeCast<String>(),
                    row.protocol.unsafeCast<String>(),
                )
            val decoder = ControllerDecoders.of(identity.decoder) ?: throw ConstraintViolation("decoder")
            ControllerSession(rideId, row.sessionNo.unsafeCast<Int>(), identity, decoder.capabilities(identity))
        }

    override suspend fun events(rideId: RideId): StoreResult<List<RideEvent>> =
        rows(rideId, IdbObjectStore.rideEvent) { row ->
            RideEvent(
                EventId(row.eventId.unsafeCast<String>()),
                Vocabularies.eventKind.value(row.kind.unsafeCast<String>()),
                long(row.utcUs),
                long(row.elapsedUs),
            )
        }

    override suspend fun telemetry(
        rideId: RideId,
        range: TimeRange,
        after: TelemetryKey?,
        limit: Int,
    ): StoreResult<TelemetryPage> {
        val page = TelemetryCollector(range, after, limit)
        return facts(
            rideId,
            IdbFactKind.telemetry,
            maxOf(range.startUs, after?.elapsedUs ?: range.startUs),
            page::take,
        ) {
            page.page()
        }
    }

    override suspend fun locations(
        rideId: RideId,
        range: TimeRange,
        after: LocationKey?,
        limit: Int,
    ): StoreResult<LocationPage> {
        val fixes = mutableListOf<LocationFix>()
        var full = false
        return facts(
            rideId,
            IdbFactKind.location,
            maxOf(range.startUs, after?.elapsedUs ?: range.startUs),
            take = { facts ->
                for (row in 0 until facts.rows) {
                    val elapsed = facts.elapsed(row)
                    if (elapsed >= range.endUs) {
                        full = true
                        break
                    }
                    if (elapsed < range.startUs) continue
                    val fix = facts.fix(rideId, row)
                    if (after != null && fix.after(after) <= 0) continue
                    fixes += fix
                    if (fixes.size > limit) {
                        full = true
                        break
                    }
                }
                !full
            },
        ) {
            val page = fixes.take(limit)
            val next = page.lastOrNull()?.takeIf { fixes.size > limit }?.let { LocationKey(it.elapsedUs, it.seq) }
            LocationPage(page, next)
        }
    }

    override suspend fun health(
        rideId: RideId,
        range: TimeRange,
        metric: HealthMetric?,
        after: HealthKey?,
        limit: Int,
        source: HealthSourceFilter?,
    ): StoreResult<HealthSamplePage> = StoreResult.Ok(HealthSamplePage(emptyList(), null))

    override suspend fun nearest(
        rideId: RideId,
        target: NearestTarget,
        atUs: Long,
        before: Boolean,
        withinUs: Long,
    ): StoreResult<Long?> {
        val (kind, column) =
            when (target) {
                is NearestTarget.Telemetry -> {
                    val index = TELEMETRY_METRICS.indexOf(target.metric)
                    if (index < 0) return StoreResult.Ok(null)
                    IdbFactKind.telemetry to telemetryColumn(index)
                }
                is NearestTarget.Location -> {
                    val location = LocationColumn.of(target.metric) ?: return StoreResult.Ok(null)
                    IdbFactKind.location to locationColumn(location)
                }
                is NearestTarget.Health -> return StoreResult.Ok(null)
            }
        return archive.read(listOf(IdbObjectStore.ride, IdbObjectStore.facts)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                if (!isLive(record)) return@request answer(null)
                val end = store(IdbObjectStore.facts).index("end")
                if (before) earlier(end, rideId, kind, column, atUs, saturatingMinus(atUs, withinUs))
                else later(end, rideId, kind, column, atUs, saturatingPlus(atUs, withinUs))
            }
        }
    }

    override suspend fun lastFact(rideId: RideId): StoreResult<RetainedEnd?> =
        archive.read(listOf(IdbObjectStore.ride, IdbObjectStore.facts)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                if (!isLive(record)) return@request answer(null)
                val end = store(IdbObjectStore.facts).index("end")
                val kinds = listOf(IdbFactKind.telemetry, IdbFactKind.location)
                all(kinds.map { end.openCursor(factRange(rideId, it, NEGATIVE), "prev") }) { cursors ->
                    var latest: RetainedEnd? = null
                    for (index in kinds.indices) {
                        val cursor = cursors[index]
                        if (cursor == null) continue
                        val last = Facts(cursor.value).last(kinds[index])
                        val before = latest
                        if (
                            before == null ||
                                last.elapsedUs > before.elapsedUs ||
                                last.elapsedUs == before.elapsedUs && last.utcUs > before.utcUs
                        )
                            latest = last
                    }
                    answer(latest)
                }
            }
        }

    // The first record whose last row reaches atUs is the only later one that may hold rows before it.
    private fun IdbJob<Long?>.earlier(
        end: IDBIndex,
        ride: RideId,
        kind: IdbFactKind,
        column: Int,
        atUs: Long,
        bound: Long,
    ) {
        fun scan(facts: Facts): Long? {
            for (row in facts.rows - 1 downTo 0) {
                val elapsed = facts.elapsed(row)
                if (elapsed >= atUs) continue
                if (elapsed < bound) return null
                if (!facts.value(column, row).isNaN()) return elapsed
            }
            return null
        }
        request(end.openCursor(factRange(ride, kind, atUs.js()), "next")) { cursor ->
            val found = if (cursor == null) null else scan(Facts(cursor.value))
            if (found != null) return@request answer(found)
            var result: Long? = null
            val range =
                IDBKeyRange.bound(
                    arrayOf(ride.value, kind.name, NEGATIVE),
                    arrayOf(ride.value, kind.name, atUs.js()),
                    false,
                    true,
                )
            cursor(
                end.openCursor(range, "prev"),
                each = { record ->
                    val facts = Facts(record)
                    result = scan(facts)
                    result == null && facts.elapsed(0) >= bound
                },
                done = { answer(result) },
            )
        }
    }

    private fun IdbJob<Long?>.later(
        end: IDBIndex,
        ride: RideId,
        kind: IdbFactKind,
        column: Int,
        atUs: Long,
        bound: Long,
    ) {
        var result: Long? = null
        cursor(
            end.openCursor(factRange(ride, kind, atUs.js()), "next"),
            each = { record ->
                val facts = Facts(record)
                for (row in 0 until facts.rows) {
                    val elapsed = facts.elapsed(row)
                    if (elapsed < atUs) continue
                    if (elapsed > bound) return@cursor false
                    if (!facts.value(column, row).isNaN()) {
                        result = elapsed
                        return@cursor false
                    }
                }
                true
            },
            done = { answer(result) },
        )
    }

    private suspend fun <T> rows(rideId: RideId, source: IdbObjectStore, row: (dynamic) -> T): StoreResult<List<T>> =
        archive.read(listOf(IdbObjectStore.ride, source)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                if (!isLive(record)) return@request answer(emptyList())
                val rows = mutableListOf<T>()
                val range =
                    IDBKeyRange.bound(arrayOf(rideId.value, NEGATIVE), arrayOf(rideId.value, POSITIVE), false, false)
                cursor(
                    store(source).openCursor(range, "next"),
                    each = {
                        rows.add(row(it))
                        true
                    },
                    done = { answer(rows) },
                )
            }
        }

    private suspend fun <T> facts(
        rideId: RideId,
        kind: IdbFactKind,
        fromUs: Long,
        take: (Facts) -> Boolean,
        page: () -> T,
    ): StoreResult<T> =
        archive.read(listOf(IdbObjectStore.ride, IdbObjectStore.facts)) {
            request(store(IdbObjectStore.ride).get(rideId.value)) { record ->
                if (!isLive(record)) return@request answer(page())
                cursor(
                    store(IdbObjectStore.facts).index("end").openCursor(factRange(rideId, kind, fromUs.js()), "next"),
                    each = { take(Facts(it)) },
                    done = { answer(page()) },
                )
            }
        }
}

private fun LocationFix.after(key: LocationKey): Int {
    val byElapsed = elapsedUs.compareTo(key.elapsedUs)
    return if (byElapsed != 0) byElapsed else seq.compareTo(key.seq)
}

private fun factRange(ride: RideId, kind: IdbFactKind, fromUs: Double): IDBKeyRange =
    IDBKeyRange.bound(arrayOf(ride.value, kind.name, fromUs), arrayOf(ride.value, kind.name, POSITIVE), false, false)

private class TelemetryCollector(
    private val range: TimeRange,
    private val after: TelemetryKey?,
    private val limit: Int,
) {
    private val elapsedUs = mutableListOf<Double>()
    private val sessionNo = mutableListOf<Int>()
    private val seq = mutableListOf<Double>()
    private val utcUs = mutableListOf<Double>()
    private val activeInterval = mutableListOf<Int>()
    private val run = mutableListOf<Int>()
    private val metrics = TELEMETRY_METRICS.map { mutableListOf<Double>() }

    fun take(facts: Facts): Boolean {
        for (row in 0 until facts.rows) {
            val elapsed = facts.elapsed(row)
            if (elapsed >= range.endUs) return false
            if (elapsed < range.startUs) continue
            val session = facts.value(1, row).toInt()
            val sequence = facts.value(2, row).toLong()
            if (after != null && compare(elapsed, session, sequence, after) <= 0) continue
            elapsedUs += facts.value(0, row)
            sessionNo += session
            seq += facts.value(2, row)
            utcUs += facts.value(3, row)
            activeInterval += facts.value(4, row).takeUnless { it.isNaN() }?.toInt() ?: 0
            run += facts.value(5, row).toInt()
            metrics.forEachIndexed { index, values -> values += facts.value(telemetryColumn(index), row) }
            if (elapsedUs.size > limit) return false
        }
        return true
    }

    fun page(): TelemetryPage {
        val rows = minOf(elapsedUs.size, limit)
        val quanta = LinkedHashMap<MetricId, DoubleArray>()
        metrics.forEachIndexed { index, values ->
            if ((0 until rows).any { !values[it].isNaN() })
                quanta[TELEMETRY_METRICS[index]] = values.take(rows).toDoubleArray()
        }
        val page =
            TelemetryRows(
                rows,
                elapsedUs.take(rows).toDoubleArray(),
                sessionNo.take(rows).toIntArray(),
                seq.take(rows).toDoubleArray(),
                utcUs.take(rows).toDoubleArray(),
                activeInterval.take(rows).toIntArray(),
                run.take(rows).toIntArray(),
                quanta,
            )
        val next =
            if (elapsedUs.size > limit && rows > 0)
                TelemetryKey(elapsedUs[rows - 1].toLong(), sessionNo[rows - 1], seq[rows - 1].toLong())
            else null
        return TelemetryPage(page, next)
    }

    private fun compare(elapsed: Long, session: Int, sequence: Long, key: TelemetryKey): Int {
        val byElapsed = elapsed.compareTo(key.elapsedUs)
        if (byElapsed != 0) return byElapsed
        val bySession = session.compareTo(key.sessionNo)
        if (bySession != 0) return bySession
        return sequence.compareTo(key.seq)
    }
}
