package app.powerlog.application.exports

import app.powerlog.application.PagePolicy
import app.powerlog.application.api.failure
import app.powerlog.application.exports.ExportPages.Companion.numbers
import app.powerlog.application.exports.ExportPages.Companion.orNaN
import app.powerlog.application.exports.ExportPages.Companion.secondColumns
import app.powerlog.application.exports.ExportPages.Companion.texts
import app.powerlog.application.store.HealthKey
import app.powerlog.application.store.HealthSamplePage
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.LocationPage
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.application.store.TelemetryPage
import app.powerlog.domain.core.analysis.battery.BatteryConsumptionReducer
import app.powerlog.domain.core.analysis.battery.ConsumptionMetric
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.measurement.Bounded
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryConsumer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.ActiveTimeline
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.exports.ExportPrint
import app.powerlog.domain.exports.FitHealth
import app.powerlog.domain.exports.FitHealthPlanner
import app.powerlog.domain.exports.FitRecordPlan
import app.powerlog.domain.exports.FitSession
import app.powerlog.domain.exports.FitSessionReducer
import app.powerlog.domain.exports.RecordInterval
import app.powerlog.domain.exports.SecondKind
import app.powerlog.domain.exports.SecondRecords
import app.powerlog.domain.exports.SecondRow

internal typealias StoreRead = suspend (StoreResult<*>) -> Any?

// The ride's facts in elapsed order; at one instant a telemetry row, then a fix, a Health sample and an event.
internal class FactCursor(private val session: ExportSession, private val read: StoreRead) {
    sealed interface Next {
        val elapsedUs: Long

        class Row(val rows: TelemetryRows, val row: Int) : Next {
            override val elapsedUs: Long = rows.elapsedUs[row].toLong()
        }

        class Other(override val elapsedUs: Long, val fact: Fact, val rank: Int) : Next
    }

    private val extent = requireNotNull(session.extent)
    private var rows: TelemetryRows? = null
    private var row = 0
    private var telemetryAfter: TelemetryKey? = null
    private var telemetryMore = extent.telemetry != null
    private var fixes: List<LocationFix> = emptyList()
    private var fix = 0
    private var locationAfter: LocationKey? = null
    private var locationMore = extent.locations != null
    private var samples: List<HealthSample> = emptyList()
    private var sample = 0
    private var healthAfter: HealthKey? = null
    private var healthMore = extent.health != null
    private var event = 0

    suspend fun next(): Next? {
        val candidates = listOfNotNull(telemetry(), location(), health(), lifecycle())
        val best = candidates.minWithOrNull(compareBy<Next>({ it.elapsedUs }, { (it as? Next.Other)?.rank ?: 0 }))
        when (best) {
            is Next.Row -> row++
            is Next.Other ->
                when (best.rank) {
                    LOCATION -> fix++
                    HEALTH -> sample++
                    else -> event++
                }
            null -> Unit
        }
        return best
    }

    private suspend fun telemetry(): Next? {
        while (rows.let { it == null || row >= it.rows } && telemetryMore) {
            val page =
                read(
                    session.store.telemetry(
                        session.rideId,
                        requireNotNull(extent.telemetry),
                        telemetryAfter,
                        PagePolicy.rows,
                    )
                )
                    as TelemetryPage
            rows = page.rows
            row = 0
            telemetryAfter = page.next
            telemetryMore = page.next != null
        }
        val current = rows ?: return null
        return if (row < current.rows) Next.Row(current, row) else null
    }

    private suspend fun location(): Next? {
        while (fix >= fixes.size && locationMore) {
            val page =
                read(
                    session.store.locations(
                        session.rideId,
                        requireNotNull(extent.locations),
                        locationAfter,
                        PagePolicy.rows,
                    )
                )
                    as LocationPage
            fixes = page.fixes
            fix = 0
            locationAfter = page.next
            locationMore = page.next != null
        }
        return fixes.getOrNull(fix)?.let { Next.Other(it.elapsedUs, Fact.Location(it), LOCATION) }
    }

    private suspend fun health(): Next? {
        while (sample >= samples.size && healthMore) {
            val page =
                read(
                    session.store.health(
                        session.rideId,
                        requireNotNull(extent.health),
                        null,
                        healthAfter,
                        PagePolicy.rows,
                    )
                )
                    as HealthSamplePage
            samples = page.samples
            sample = 0
            healthAfter = page.next
            healthMore = page.next != null
        }
        return samples.getOrNull(sample)?.let { Next.Other(it.elapsedUs, Fact.Health(it), HEALTH) }
    }

    private fun lifecycle(): Next? =
        session.events.getOrNull(event)?.let { Next.Other(it.elapsedUs, Fact.Event(it), EVENT) }

    private companion object {
        const val LOCATION = 1
        const val HEALTH = 2
        const val EVENT = 3
    }
}

// Feeds a FIT consumer the facts with the frozen source's distance intervals and the battery intervals of each row.
internal class FitFeed(ride: Ride, private val battery: Boolean) {
    private val frozen = ride.options.distanceSource
    private val consumption = BatteryConsumptionReducer(null)
    private val controller = ControllerDistanceReducer(null)
    private val gps = GpsDistanceReducer(null)

    fun feed(
        next: FactCursor.Next,
        session: ExportSession,
        consumer: TelemetryConsumer,
        fact: (Fact) -> Unit,
        distance: (DistanceInterval) -> Unit,
        intervals: (List<app.powerlog.domain.core.analysis.battery.ConsumptionInterval>) -> Unit = {},
    ) {
        when (next) {
            is FactCursor.Next.Row -> {
                consumer.append(next.rows, next.row, next.row + 1, session.sessions)
                if (battery) intervals(consumption.append(next.rows, next.row, next.row + 1, session.sessions))
                val edges = controller.append(next.rows, next.row, next.row + 1, session.sessions)
                if (frozen == DistanceSource.controller) edges.forEach(distance)
            }
            is FactCursor.Next.Other -> {
                fact(next.fact)
                val located = next.fact as? Fact.Location ?: return
                val edge = gps.append(located.fix)
                if (frozen == DistanceSource.phoneGps && edge != null) distance(edge.interval)
            }
        }
    }
}

internal class FitPlan(val health: FitHealth, val intervalS: Int, val power: FitPowerSource) {
    companion object {
        suspend fun of(session: ExportSession, read: StoreRead): FitPlan {
            val ride = requireNotNull(session.ride)
            val planner = FitHealthPlanner(ride)
            val extent = requireNotNull(session.extent)
            val range = extent.health
            if (range != null) {
                var after: HealthKey? = null
                while (true) {
                    val page =
                        read(session.store.health(session.rideId, range, null, after, PagePolicy.rows))
                            as HealthSamplePage
                    page.samples.forEach(planner::append)
                    after = page.next ?: break
                }
            }
            val health = planner.result()
            if (session.kind != ExportKind.fit) return FitPlan(health, 1, FitPowerSource.rider)
            val timeline = ActiveTimeline(session.endUs, session.events)
            val plan = FitRecordPlan(ride, timeline, session.power, health.heartRate)
            if (plan.needed) {
                val cursor = FactCursor(session, read)
                val feed = FitFeed(ride, battery = false)
                while (true) {
                    val next = cursor.next() ?: break
                    feed.feed(next, session, plan, plan::append, plan::distance)
                }
            }
            return when (val interval = plan.result()) {
                is RecordInterval.Planned -> FitPlan(health, interval.intervalS, session.power)
                is RecordInterval.Refused -> throw ExportFailed(failure(interval.code))
            }
        }
    }
}

internal sealed class DerivedPass {
    abstract suspend fun page(): ExportPage

    companion object {
        suspend fun start(
            session: ExportSession,
            projection: String,
            read: StoreRead,
            plan: suspend () -> FitPlan,
        ): DerivedPass =
            when (projection) {
                ExportPages.CONSUMPTION -> ConsumptionPass(session, read)
                ExportPages.SECONDS -> SecondsPass(session, read, plan())
                else -> SummaryPass(session, read, plan())
            }
    }
}

internal class ConsumptionPass(private val session: ExportSession, private val read: StoreRead) : DerivedPass() {
    private val reducer = BatteryConsumptionReducer(null)
    private var after: TelemetryKey? = null
    private var more = session.consumption is ConsumptionAvailability.Available
    private var emitted = 0

    override suspend fun page(): ExportPage {
        val range = session.extent?.telemetry
        if (!more || range == null) return ExportPage(0, null, true, emptyList(), emptyMap())
        val page = read(session.store.telemetry(session.rideId, range, after, PagePolicy.rows)) as TelemetryPage
        val rows = page.rows
        val values = ConsumptionMetric.entries.associateWith { DoubleArray(rows.rows) { Double.NaN } }
        for (row in 0 until rows.rows) {
            for (interval in reducer.append(rows, row, row + 1, session.sessions)) values
                .getValue(interval.metric)[row] = interval.endValue
        }
        after = page.next
        more = page.next != null
        emitted += rows.rows
        val columns = linkedMapOf<String, ExportColumn>()
        columns["timestamp"] = texts(rows.rows) { ExportPrint.timestamp(rows.utcUs[it].toLong()) }
        columns["elapsedSeconds"] = numbers(rows.rows) { rows.elapsedUs[it] / 1e6 }
        for (metric in ConsumptionMetric.entries) columns[metric.name] = ExportColumn.Numbers(values.getValue(metric))
        return ExportPage(rows.rows, listOf(emitted.toDouble()), !more, emptyList(), columns)
    }
}

internal class SecondsPass(private val session: ExportSession, read: StoreRead, plan: FitPlan) : DerivedPass() {
    private val ride = requireNotNull(session.ride)
    private val records =
        SecondRecords(
            ride,
            ActiveTimeline(session.endUs, session.events),
            plan.intervalS,
            plan.power,
            plan.health.heartRate,
        )
    private val cursor = FactCursor(session, read)
    private val feed = FitFeed(ride, battery = true)
    private val pending = ArrayDeque<SecondRow>()
    private var finished = false
    private var emitted = 0
    private var recorded = false

    override suspend fun page(): ExportPage {
        while (pending.size < PagePolicy.rows && !finished) {
            val next = cursor.next()
            if (next == null) {
                pending += records.finish()
                finished = true
                break
            }
            feed.feed(next, session, records, records::append, records::distance) { it.forEach(records::battery) }
            pending += records.take()
        }
        val rows = List(minOf(PagePolicy.rows, pending.size)) { pending.removeFirst() }
        if (rows.any { it.kind == SecondKind.record }) recorded = true
        val done = finished && pending.isEmpty()
        if (done && !recorded) throw ExportFailed(failure(FailureCode.exportNoRecords))
        emitted += rows.size
        return ExportPage(
            rows.size,
            if (rows.isEmpty()) null else listOf(emitted.toDouble()),
            done,
            emptyList(),
            secondColumns(rows),
        )
    }
}

internal class SummaryPass(private val session: ExportSession, private val read: StoreRead, private val plan: FitPlan) :
    DerivedPass() {
    private var rows: List<SummaryRow>? = null
    private var emitted = 0

    override suspend fun page(): ExportPage {
        val all = rows ?: summarize().also { rows = it }
        val page = all.drop(emitted).take(PagePolicy.rows)
        emitted += page.size
        val columns = linkedMapOf<String, ExportColumn>()
        columns["kind"] = texts(page) { it.kind }
        for (name in SummaryRow.NUMBERS) columns[name] = numbers(page) { it.values[name].orNaN() }
        return ExportPage(
            page.size,
            if (page.isEmpty()) null else listOf(emitted.toDouble()),
            emitted >= all.size,
            emptyList(),
            columns,
        )
    }

    private suspend fun summarize(): List<SummaryRow> {
        val ride = requireNotNull(session.ride)
        val timeline = ActiveTimeline(session.endUs, session.events)
        val reducer = FitSessionReducer(ride, timeline, plan.power, plan.health.heartRate)
        val cursor = FactCursor(session, read)
        val feed = FitFeed(ride, battery = false)
        while (true) {
            val next = cursor.next() ?: break
            feed.feed(next, session, reducer, reducer::append, reducer::distance)
        }
        val fit: FitSession =
            when (val result = reducer.result()) {
                is Bounded.Done -> result.value
                Bounded.Capacity -> throw ExportFailed(failure(FailureCode.exportLimit))
            }
        val session =
            SummaryRow(
                "session",
                mapOf(
                    "recordIntervalS" to plan.intervalS.toDouble(),
                    "events" to this.session.events.size.toDouble(),
                    "elapsedUs" to this.session.endUs.toDouble(),
                    "timerUs" to timeline.timerUs.toDouble(),
                    "distanceM" to fit.distanceM,
                    "averageSpeedMps" to fit.averageSpeedMps,
                    "maxSpeedMps" to fit.maxSpeedMps,
                    "averageHeartRateBpm" to fit.averageHeartRateBpm,
                    "maxHeartRateBpm" to fit.maxHeartRateBpm,
                    "averageCadenceRpm" to fit.averageCadenceRpm,
                    "maxCadenceRpm" to fit.maxCadenceRpm,
                    "averagePowerW" to fit.averagePowerW,
                    "maxPowerW" to fit.maxPowerW,
                    "averageMotorPowerW" to fit.averageMotorPowerW,
                    "maxMotorPowerW" to fit.maxMotorPowerW,
                    "workJ" to fit.workJ,
                    "ascentM" to fit.ascentM,
                    "descentM" to fit.descentM,
                    "caloriesKcal" to plan.health.caloriesKcal,
                ),
            )
        val laps =
            timeline.laps.mapIndexed { index, lap ->
                SummaryRow(
                    "lap",
                    mapOf(
                        "elapsedUs" to (lap.range.endUs - lap.range.startUs).toDouble(),
                        "timerUs" to lap.timerUs.toDouble(),
                        "startUs" to lap.range.startUs.toDouble(),
                        "endUs" to lap.range.endUs.toDouble(),
                        "distanceM" to fit.lapDistancesM[index],
                    ),
                )
            }
        return listOf(session) + laps
    }
}

internal class SummaryRow(val kind: String, val values: Map<String, Double?>) {
    companion object {
        val NUMBERS =
            listOf(
                "recordIntervalS",
                "events",
                "elapsedUs",
                "timerUs",
                "startUs",
                "endUs",
                "distanceM",
                "averageSpeedMps",
                "maxSpeedMps",
                "averageHeartRateBpm",
                "maxHeartRateBpm",
                "averageCadenceRpm",
                "maxCadenceRpm",
                "averagePowerW",
                "maxPowerW",
                "averageMotorPowerW",
                "maxMotorPowerW",
                "workJ",
                "ascentM",
                "descentM",
                "caloriesKcal",
            )
    }
}
