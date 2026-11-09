package app.powerlog.data

import app.powerlog.application.store.RideCursor
import app.powerlog.application.store.RideRecord
import app.powerlog.application.store.RideRecordPage
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.ids.RideId

internal class SqlRideRepository(private val archive: SqlArchive) : RideRepository {
    override suspend fun ride(rideId: RideId): StoreResult<RideRecord?> = archive.read { db ->
        db.statement(
            "SELECT $RIDE_COLUMNS, $PUBLISH_COLUMNS FROM ride r $PUBLISH_JOIN " +
                "WHERE r.uuid = ? AND r.deleted_utc_us IS NULL ORDER BY w.scope"
        ) {
            it.bindText(1, rideId.value)
            it.rideRecords().firstOrNull()
        }
    }

    override suspend fun openRide(): StoreResult<RideRecord?> = archive.read { db ->
        db.statement(
            "SELECT $RIDE_COLUMNS, $PUBLISH_COLUMNS FROM (SELECT * FROM ride WHERE deleted_utc_us IS NULL " +
                "AND phase IN ('preparing', 'running', 'paused', 'interrupted') " +
                "ORDER BY started_utc_us DESC, uuid DESC LIMIT 1) r $PUBLISH_JOIN ORDER BY w.scope"
        ) {
            it.rideRecords().firstOrNull()
        }
    }

    override suspend fun page(after: RideCursor?, limit: Int): StoreResult<RideRecordPage> = archive.read { db ->
        val continued = if (after == null) "" else "AND (started_utc_us, uuid) < (?, ?) "
        db.statement(
            "SELECT $RIDE_COLUMNS, $PUBLISH_COLUMNS FROM (SELECT * FROM ride WHERE deleted_utc_us IS NULL " +
                continued +
                "ORDER BY started_utc_us DESC, uuid DESC LIMIT ?) r $PUBLISH_JOIN " +
                "ORDER BY r.started_utc_us DESC, r.uuid DESC, w.scope"
        ) {
            var index = 1
            if (after != null) {
                it.bindLong(index++, after.startedUtcUs)
                it.bindText(index++, after.rideId.value)
            }
            it.bindLong(index, limit + 1L)
            val records = it.rideRecords()
            val rows = records.take(limit)
            val next = rows.lastOrNull()?.takeIf { records.size > limit }?.ride
            RideRecordPage(rows, next?.let { ride -> RideCursor(ride.startedUtcUs, ride.id) })
        }
    }

    override suspend fun marker(): StoreResult<Long> = archive.read { db ->
        db.long("SELECT history_revision FROM app_state WHERE k = 1")
    }
}
