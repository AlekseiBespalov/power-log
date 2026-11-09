package app.powerlog.data

import androidx.sqlite.SQLiteStatement
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.RideRecord
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideOptions
import app.powerlog.domain.core.time.RideTiming

internal fun MetricId.column(): String = name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() }

internal fun SQLiteStatement.timing(index: Int): RideTiming? {
    val values = List(3) { longOrNull(index + it) }
    if (values.all { it == null }) return null
    if (values.any { it == null }) throw ConstraintViolation("a partial timing")
    return RideTiming(values[0]!!, values[1]!!, values[2]!!)
}

internal fun SQLiteStatement.rideHealth(index: Int): RideHealth =
    RideHealth(
        provider = textOrNull(index)?.let(Vocabularies.provider::value),
        outcome = Vocabularies.outcome.value(getText(index + 1)),
        reason = textOrNull(index + 2)?.let(Vocabularies.reason::value),
        workoutId = textOrNull(index + 3),
    )

internal const val RIDE_COLUMNS: String =
    "r.id, r.uuid, r.recorded_by, r.example, r.started_utc_us, r.sample_hz, r.indoor, r.use_watch, " +
        "r.save_to_health, r.record_gps, r.distance_source, r.battery_method, r.phase, r.interrupted, r.cp_utc_us, " +
        "r.cp_elapsed_us, r.cp_timer_us, r.end_utc_us, r.end_elapsed_us, r.end_timer_us, r.health_provider, " +
        "r.health_outcome, r.health_reason, r.health_workout_id, r.data_revision"

internal const val PUBLISH_COLUMNS: String = "w.scope, w.state, w.attempts, w.cursor, w.due_utc_us, w.reason"

internal const val PUBLISH_JOIN: String = "LEFT JOIN health_work w ON w.ride_id = r.id AND w.task = 'publish'"

private const val PUBLISH_FIRST = 25

// Folds rides LEFT JOINed to their publish rows into one record per ride, in row order.
internal fun SQLiteStatement.rideRecords(): List<RideRecord> {
    val records = mutableListOf<RideRecord>()
    val publish = mutableListOf<HealthWork>()
    var current: RideRecord? = null
    var currentId: Long? = null
    while (step()) {
        val id = getLong(0)
        if (id != currentId) {
            current?.let { records += it.copy(publish = publish.toList()) }
            publish.clear()
            currentId = id
            current = RideRecord(ride(), rideHealth(20), emptyList(), getLong(24))
        }
        if (!isNull(PUBLISH_FIRST)) publish += publishRow(RideId(getText(1)))
    }
    current?.let { records += it.copy(publish = publish.toList()) }
    return records
}

private fun SQLiteStatement.ride(): Ride =
    Ride(
        id = RideId(getText(1)),
        recordedBy = Vocabularies.device.value(getText(2)),
        example = Vocabularies.flag(getLong(3)),
        startedUtcUs = getLong(4),
        options =
            RideOptions(
                sampleRate = Vocabularies.sampleRate(getLong(5)),
                indoor = Vocabularies.flag(getLong(6)),
                useWatch = Vocabularies.flag(getLong(7)),
                saveToHealth = Vocabularies.flag(getLong(8)),
                recordGps = Vocabularies.flag(getLong(9)),
                distanceSource = Vocabularies.distanceSource.value(getText(10)),
                batteryMethod = Vocabularies.batteryMethod.value(getText(11)),
            ),
        phase = Vocabularies.phase.value(getText(12)),
        interrupted = Vocabularies.flag(getLong(13)),
        checkpoint = RideTiming(getLong(14), getLong(15), getLong(16)),
        end = timing(17),
    )

private fun SQLiteStatement.publishRow(rideId: RideId): HealthWork =
    HealthWork(
        rideId = rideId,
        scope = HealthWorkScope.Publish(Vocabularies.component.value(getText(PUBLISH_FIRST))),
        state = Vocabularies.componentState.value(getText(PUBLISH_FIRST + 1)),
        attempts = getLong(PUBLISH_FIRST + 2).toInt(),
        cursor = textOrNull(PUBLISH_FIRST + 3),
        dueUtcUs = longOrNull(PUBLISH_FIRST + 4),
        reason = textOrNull(PUBLISH_FIRST + 5)?.let(Vocabularies.reason::value),
    )

internal fun SQLiteStatement.workRow(rideId: RideId, index: Int): HealthWork {
    val scope =
        when (getText(index)) {
            "publish" -> HealthWorkScope.Publish(Vocabularies.component.value(getText(index + 1)))
            "readback" -> HealthWorkScope.Readback(HealthMetric(getText(index + 1)))
            else -> throw ConstraintViolation("task ${getText(index)}")
        }
    return HealthWork(
        rideId = rideId,
        scope = scope,
        state = Vocabularies.componentState.value(getText(index + 2)),
        attempts = getLong(index + 3).toInt(),
        cursor = textOrNull(index + 4),
        dueUtcUs = longOrNull(index + 5),
        reason = textOrNull(index + 6)?.let(Vocabularies.reason::value),
    )
}
