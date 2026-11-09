package app.powerlog.data

import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.RidePhase

internal class SqlHealthRepository(private val archive: SqlArchive) : HealthRepository {
    override suspend fun outcome(rideId: RideId): StoreResult<RideHealth?> = archive.read { db ->
        db.statement(
            "SELECT health_provider, health_outcome, health_reason, health_workout_id FROM ride " +
                "WHERE uuid = ? AND deleted_utc_us IS NULL"
        ) {
            it.bindText(1, rideId.value)
            if (it.step()) it.rideHealth(0) else null
        }
    }

    override suspend fun work(rideId: RideId): StoreResult<List<HealthWork>> = archive.read { db ->
        db.statement(
            "SELECT w.task, w.scope, w.state, w.attempts, w.cursor, w.due_utc_us, w.reason FROM ride r " +
                "JOIN health_work w ON w.ride_id = r.id WHERE r.uuid = ? AND r.deleted_utc_us IS NULL " +
                "ORDER BY w.task, w.scope"
        ) {
            it.bindText(1, rideId.value)
            buildList { while (it.step()) add(it.workRow(rideId, 0)) }
        }
    }

    override suspend fun dueRides(nowUtcUs: Long): StoreResult<List<RideId>> = archive.read { db ->
        db.statement(DUE_RIDES) {
            it.bindLong(1, nowUtcUs)
            it.bindText(2, Vocabularies.phase.name(RidePhase.completed))
            it.bindText(3, Vocabularies.device.name(Device.androidPhone))
            it.bindText(4, Vocabularies.outcome.name(HealthOutcome.pending))
            val rides = LinkedHashSet<RideId>()
            while (it.step()) rides += RideId(it.getText(0))
            rides.toList()
        }
    }

    override suspend fun deferredRides(): StoreResult<List<RideId>> = archive.read { db ->
        db.statement(DEFERRED_RIDES) {
            it.bindText(1, Vocabularies.componentState.name(ComponentState.pending))
            buildList { while (it.step()) add(RideId(it.getText(0))) }
        }
    }
}

private const val DEFERRED_RIDES =
    "SELECT r.uuid FROM ride r WHERE r.deleted_utc_us IS NULL AND EXISTS (SELECT 1 FROM health_work w " +
        "WHERE w.ride_id = r.id AND w.task = 'readback' AND w.state = ?1 AND w.due_utc_us IS NULL) " +
        "ORDER BY r.started_utc_us, r.uuid"

private const val DUE_RIDES =
    "SELECT uuid FROM (" +
        "SELECT r.uuid, 0 AS part, MIN(w.due_utc_us) AS due, r.started_utc_us AS started " +
        "FROM health_work w JOIN ride r ON r.id = w.ride_id " +
        "WHERE w.due_utc_us IS NOT NULL AND w.due_utc_us <= ?1 AND r.deleted_utc_us IS NULL GROUP BY r.id " +
        "UNION ALL " +
        "SELECT uuid, 1, NULL, started_utc_us FROM ride " +
        "WHERE deleted_utc_us IS NULL AND phase = ?2 AND recorded_by = ?3 AND health_outcome = ?4 " +
        "AND NOT EXISTS (SELECT 1 FROM health_work w WHERE w.ride_id = ride.id)" +
        ") ORDER BY part, due, started, uuid"
