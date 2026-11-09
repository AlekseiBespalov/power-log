package app.powerlog.data

import androidx.sqlite.SQLiteStatement
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
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.RetainedEnd
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.TimeRange

private const val OF_RIDE = "WHERE r.uuid = ? AND r.deleted_utc_us IS NULL"

private const val LAST_FACT =
    "SELECT utc_us, elapsed_us FROM (" +
        "SELECT * FROM (SELECT f.utc_us, f.elapsed_us FROM ride r JOIN telemetry f ON f.ride_id = r.id " +
        "WHERE r.uuid = ?1 AND r.deleted_utc_us IS NULL ORDER BY f.elapsed_us DESC, f.session_no DESC, f.seq DESC LIMIT 1) " +
        "UNION ALL " +
        "SELECT * FROM (SELECT f.utc_us, f.elapsed_us FROM ride r JOIN location f ON f.ride_id = r.id " +
        "WHERE r.uuid = ?1 AND r.deleted_utc_us IS NULL ORDER BY f.elapsed_us DESC, f.seq DESC LIMIT 1) " +
        "UNION ALL " +
        "SELECT * FROM (SELECT f.utc_us, f.elapsed_us FROM ride r JOIN health_sample f ON f.ride_id = r.id " +
        "WHERE r.uuid = ?1 AND r.deleted_utc_us IS NULL ORDER BY f.elapsed_us DESC, f.id DESC LIMIT 1)" +
        ") ORDER BY elapsed_us DESC, utc_us DESC LIMIT 1"

internal class SqlMeasurementRepository(private val archive: SqlArchive) : MeasurementRepository {
    override suspend fun extent(rideId: RideId): StoreResult<Extent?> = archive.read { db ->
        db.statement(
            "SELECT r.data_revision, " +
                "(SELECT min(elapsed_us) FROM telemetry WHERE ride_id = r.id), " +
                "(SELECT max(elapsed_us) FROM telemetry WHERE ride_id = r.id), " +
                "(SELECT min(elapsed_us) FROM location WHERE ride_id = r.id), " +
                "(SELECT max(elapsed_us) FROM location WHERE ride_id = r.id), " +
                "(SELECT min(elapsed_us) FROM health_sample WHERE ride_id = r.id), " +
                "(SELECT max(elapsed_us) FROM health_sample WHERE ride_id = r.id), " +
                "(SELECT group_concat(DISTINCT hex(metric)) FROM health_sample " +
                "WHERE ride_id = r.id AND metric IS NOT NULL), " +
                "(SELECT group_concat(DISTINCT producer) FROM health_sample WHERE ride_id = r.id) " +
                "FROM ride r $OF_RIDE"
        ) {
            it.bindText(1, rideId.value)
            if (!it.step()) return@statement null
            Extent(
                revision = it.getLong(0),
                telemetry = it.range(1),
                locations = it.range(3),
                health = it.range(5),
                healthMetrics =
                    it.textOrNull(7)?.split(',')?.map { hex -> HealthMetric(unhex(hex)) }?.toSet().orEmpty(),
                healthProducers = it.textOrNull(8)?.split(',')?.map(Vocabularies.producer::value)?.toSet().orEmpty(),
            )
        }
    }

    override suspend fun sessions(rideId: RideId): StoreResult<List<ControllerSession>> = archive.read { db ->
        db.statement(
            "SELECT s.session_no, s.decoder, s.model, s.firmware, s.protocol FROM ride r " +
                "JOIN controller_session s ON s.ride_id = r.id $OF_RIDE ORDER BY s.session_no"
        ) {
            it.bindText(1, rideId.value)
            buildList {
                while (it.step()) {
                    val identity =
                        ControllerIdentity(DecoderId(it.getText(1)), it.getText(2), it.getText(3), it.getText(4))
                    val decoder = ControllerDecoders.of(identity.decoder) ?: throw ConstraintViolation("decoder")
                    add(ControllerSession(rideId, it.getLong(0).toInt(), identity, decoder.capabilities(identity)))
                }
            }
        }
    }

    override suspend fun events(rideId: RideId): StoreResult<List<RideEvent>> = archive.read { db ->
        db.statement(
            "SELECT e.event_id, e.kind, e.utc_us, e.elapsed_us FROM ride r " +
                "JOIN ride_event e ON e.ride_id = r.id $OF_RIDE ORDER BY e.seq"
        ) {
            it.bindText(1, rideId.value)
            buildList {
                while (it.step()) add(
                    RideEvent(
                        EventId(it.getText(0)),
                        Vocabularies.eventKind.value(it.getText(1)),
                        it.getLong(2),
                        it.getLong(3),
                    )
                )
            }
        }
    }

    override suspend fun telemetry(
        rideId: RideId,
        range: TimeRange,
        after: TelemetryKey?,
        limit: Int,
    ): StoreResult<TelemetryPage> = archive.read { db ->
        val continued = if (after == null) "" else "AND (t.elapsed_us, t.session_no, t.seq) > (?, ?, ?) "
        db.statement(
            "SELECT t.elapsed_us, t.session_no, t.seq, t.utc_us, t.active_interval, t.run, " +
                TELEMETRY_METRICS.joinToString(", ") { "t." + it.column() } +
                " FROM ride r JOIN telemetry t ON t.ride_id = r.id $OF_RIDE " +
                "AND t.elapsed_us >= ? AND t.elapsed_us < ? $continued" +
                "ORDER BY t.elapsed_us, t.session_no, t.seq LIMIT ?"
        ) {
            it.bindText(1, rideId.value)
            it.bindLong(2, from(range, after?.elapsedUs))
            it.bindLong(3, range.endUs)
            var index = 4
            if (after != null) {
                it.bindLong(index++, after.elapsedUs)
                it.bindLong(index++, after.sessionNo.toLong())
                it.bindLong(index++, after.seq)
            }
            it.bindLong(index, limit + 1L)
            TelemetryColumns(limit + 1).read(it, limit)
        }
    }

    override suspend fun locations(
        rideId: RideId,
        range: TimeRange,
        after: LocationKey?,
        limit: Int,
    ): StoreResult<LocationPage> = archive.read { db ->
        val continued = if (after == null) "" else "AND (l.elapsed_us, l.seq) > (?, ?) "
        db.statement(
            "SELECT l.elapsed_us, l.seq, l.utc_us, l.active_interval, l.run, l.barrier, l.lat, l.lon, l.h_acc_m, " +
                "l.altitude_m, l.altitude_datum, l.v_acc_m, l.msl_m, l.msl_v_acc_m, l.speed_mps, l.speed_acc_mps, " +
                "l.course_deg, l.course_acc_deg FROM ride r JOIN location l ON l.ride_id = r.id $OF_RIDE " +
                "AND l.elapsed_us >= ? AND l.elapsed_us < ? $continued" +
                "ORDER BY l.elapsed_us, l.seq LIMIT ?"
        ) {
            it.bindText(1, rideId.value)
            it.bindLong(2, from(range, after?.elapsedUs))
            it.bindLong(3, range.endUs)
            var index = 4
            if (after != null) {
                it.bindLong(index++, after.elapsedUs)
                it.bindLong(index++, after.seq)
            }
            it.bindLong(index, limit + 1L)
            val fixes = buildList { while (it.step()) add(it.fix(rideId)) }
            val page = fixes.take(limit)
            val next =
                page.lastOrNull()?.takeIf { fixes.size > limit }?.let { fix -> LocationKey(fix.elapsedUs, fix.seq) }
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
    ): StoreResult<HealthSamplePage> = archive.read { db ->
        val query = FactQuery(rideId)
        query.where("h.elapsed_us >= ? AND h.elapsed_us < ?") {
            it.bindLong(from(range, after?.elapsedUs))
            it.bindLong(range.endUs)
        }
        if (metric != null) query.where("h.metric = ?") { it.bindText(metric.wire) }
        if (after != null)
            query.where("(h.elapsed_us, h.id) > (?, ?)") {
                it.bindLong(after.elapsedUs)
                it.bindLong(after.order)
            }
        query.source(source)
        db.statement(
            "SELECT h.id, h.elapsed_us, h.producer, h.sample_key, h.utc_us, h.start_utc_us, h.end_utc_us, " +
                "h.active_interval, h.metric, h.value, h.representation, h.sample_count, h.data_origin, " +
                "h.parent_id, h.member FROM ride r JOIN health_sample h ON h.ride_id = r.id ${query.conditions()} " +
                "ORDER BY h.elapsed_us, h.id LIMIT ?"
        ) {
            val index = query.bind(it)
            it.bindLong(index, limit + 1L)
            val rows = buildList { while (it.step()) add(it.getLong(0) to it.sample(rideId)) }
            val page = rows.take(limit)
            val next =
                page.lastOrNull()?.takeIf { rows.size > limit }?.let { (id, sample) -> HealthKey(sample.elapsedUs, id) }
            HealthSamplePage(page.map { row -> row.second }, next)
        }
    }

    override suspend fun nearest(
        rideId: RideId,
        target: NearestTarget,
        atUs: Long,
        before: Boolean,
        withinUs: Long,
    ): StoreResult<Long?> {
        val query = FactQuery(rideId)
        val table =
            when (target) {
                is NearestTarget.Telemetry -> {
                    if (target.metric !in TELEMETRY_METRICS) return StoreResult.Ok(null)
                    query.where("f.${target.metric.column()} IS NOT NULL") {}
                    "telemetry"
                }
                is NearestTarget.Location -> {
                    val column = LocationColumn.of(target.metric) ?: return StoreResult.Ok(null)
                    query.where("f.${column.sql} IS NOT NULL") {}
                    "location"
                }
                is NearestTarget.Health -> {
                    if (target.metrics.isEmpty()) return StoreResult.Ok(null)
                    query.where("f.metric IN (${target.metrics.joinToString(", ") { "?" }})") { bind ->
                        for (metric in target.metrics) bind.bindText(metric.wire)
                    }
                    query.source(target.source, alias = "f")
                    "health_sample"
                }
            }
        if (before)
            query.where("f.elapsed_us < ? AND f.elapsed_us >= ?") {
                it.bindLong(atUs)
                it.bindLong(saturatingMinus(atUs, withinUs))
            }
        else
            query.where("f.elapsed_us >= ? AND f.elapsed_us <= ?") {
                it.bindLong(atUs)
                it.bindLong(saturatingPlus(atUs, withinUs))
            }
        return archive.read { db ->
            db.statement(
                "SELECT f.elapsed_us FROM ride r JOIN $table f ON f.ride_id = r.id ${query.conditions()} " +
                    "ORDER BY f.elapsed_us ${if (before) "DESC" else "ASC"} LIMIT 1"
            ) {
                query.bind(it)
                if (it.step()) it.getLong(0) else null
            }
        }
    }

    override suspend fun lastFact(rideId: RideId): StoreResult<RetainedEnd?> = archive.read { db ->
        db.statement(LAST_FACT) {
            it.bindText(1, rideId.value)
            if (it.step()) RetainedEnd(it.getLong(0), it.getLong(1)) else null
        }
    }
}

private class Binder(private val statement: SQLiteStatement, var index: Int) {
    fun bindLong(value: Long) = statement.bindLong(index++, value)

    fun bindText(value: String) = statement.bindText(index++, value)
}

private class FactQuery(private val rideId: RideId) {
    private val clauses = mutableListOf<String>()
    private val binders = mutableListOf<(Binder) -> Unit>()

    fun where(clause: String, bind: (Binder) -> Unit) {
        clauses += clause
        binders += bind
    }

    fun source(source: HealthSourceFilter?, alias: String = "h") {
        if (source == null) return
        source.producer?.let { producer ->
            where("$alias.producer = ?") { it.bindText(Vocabularies.producer.name(producer)) }
        }
        source.dataOrigin?.let { origin -> where("$alias.data_origin = ?") { it.bindText(origin) } }
        source.representations?.let { kinds ->
            if (kinds.isEmpty()) where("0") {}
            else
                where("$alias.representation IN (${kinds.joinToString(", ") { "?" }})") { bind ->
                    for (kind in kinds) bind.bindText(Vocabularies.representation.name(kind))
                }
        }
    }

    fun conditions(): String =
        (listOf("r.uuid = ? AND r.deleted_utc_us IS NULL") + clauses).joinToString(" AND ", "WHERE ")

    fun bind(statement: SQLiteStatement): Int {
        statement.bindText(1, rideId.value)
        val binder = Binder(statement, 2)
        for (bind in binders) bind(binder)
        return binder.index
    }
}

// capacity: the query's LIMIT, so the columns never grow
private class TelemetryColumns(private val capacity: Int) {
    private var count = 0
    private val elapsedUs = DoubleArray(capacity)
    private val sessionNo = IntArray(capacity)
    private val seq = DoubleArray(capacity)
    private val utcUs = DoubleArray(capacity)
    private val activeInterval = IntArray(capacity)
    private val run = IntArray(capacity)
    private val metrics = arrayOfNulls<DoubleArray>(TELEMETRY_METRICS.size)

    fun read(statement: SQLiteStatement, limit: Int): TelemetryPage {
        while (statement.step()) add(statement)
        val rows = minOf(count, limit)
        val quanta = LinkedHashMap<MetricId, DoubleArray>()
        metrics.forEachIndexed { index, values ->
            if (values != null && (0 until rows).any { !values[it].isNaN() })
                quanta[TELEMETRY_METRICS[index]] = values.copyOf(rows)
        }
        val page =
            TelemetryRows(
                rows,
                elapsedUs.copyOf(rows),
                sessionNo.copyOf(rows),
                seq.copyOf(rows),
                utcUs.copyOf(rows),
                activeInterval.copyOf(rows),
                run.copyOf(rows),
                quanta,
            )
        val next =
            if (count > limit && rows > 0)
                TelemetryKey(elapsedUs[rows - 1].toLong(), sessionNo[rows - 1], seq[rows - 1].toLong())
            else null
        return TelemetryPage(page, next)
    }

    private fun add(statement: SQLiteStatement) {
        elapsedUs[count] = statement.getLong(0).toDouble()
        sessionNo[count] = statement.getLong(1).toInt()
        seq[count] = statement.getLong(2).toDouble()
        utcUs[count] = statement.getLong(3).toDouble()
        activeInterval[count] = statement.longOrNull(4)?.toInt() ?: 0
        run[count] = statement.getLong(5).toInt()
        for (index in metrics.indices) {
            if (statement.isNull(6 + index)) continue
            val values = metrics[index] ?: DoubleArray(capacity) { Double.NaN }.also { metrics[index] = it }
            values[count] = statement.getLong(6 + index).toDouble()
        }
        count++
    }
}

// The plain bound is the index seek and the row-value term a filter, so a continued page moves the bound to its key.
private fun from(range: TimeRange, afterUs: Long?): Long =
    if (afterUs == null) range.startUs else maxOf(range.startUs, afterUs)

private fun SQLiteStatement.range(index: Int): TimeRange? {
    val first = longOrNull(index) ?: return null
    return TimeRange(first, getLong(index + 1) + 1)
}

private fun SQLiteStatement.fix(rideId: RideId): LocationFix =
    LocationFix(
        rideId = rideId,
        seq = getLong(1),
        elapsedUs = getLong(0),
        utcUs = getLong(2),
        activeInterval = longOrNull(3)?.toInt(),
        run = getLong(4).toInt(),
        barrier = Vocabularies.flag(getLong(5)),
        latitude = getDouble(6),
        longitude = getDouble(7),
        horizontalAccuracyM = doubleOrNull(8),
        altitudeM = doubleOrNull(9),
        altitudeDatum = Vocabularies.altitudeDatum.value(getText(10)),
        verticalAccuracyM = doubleOrNull(11),
        mslM = doubleOrNull(12),
        mslVerticalAccuracyM = doubleOrNull(13),
        speedMps = doubleOrNull(14),
        speedAccuracyMps = doubleOrNull(15),
        courseDeg = doubleOrNull(16),
        courseAccuracyDeg = doubleOrNull(17),
    )

private fun SQLiteStatement.sample(rideId: RideId): HealthSample =
    HealthSample(
        rideId = rideId,
        producer = Vocabularies.producer.value(getText(2)),
        sampleKey = getText(3),
        dataOrigin = getText(12),
        parentId = textOrNull(13),
        member = longOrNull(14)?.toInt(),
        metric = textOrNull(8)?.let(::HealthMetric),
        representation = Vocabularies.representation.value(getText(10)),
        value = doubleOrNull(9),
        sampleCount = longOrNull(11)?.toInt(),
        utcUs = getLong(4),
        elapsedUs = getLong(1),
        startUtcUs = longOrNull(5),
        endUtcUs = longOrNull(6),
        activeInterval = longOrNull(7)?.toInt(),
    )

private fun unhex(hex: String): String =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.decodeToString()
