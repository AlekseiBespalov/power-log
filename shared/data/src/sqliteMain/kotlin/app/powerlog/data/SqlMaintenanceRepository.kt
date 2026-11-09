package app.powerlog.data

import androidx.sqlite.SQLiteStatement
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.Step
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.ids.RideId

internal class SqlMaintenanceRepository(private val archive: SqlArchive, private val cache: SqlCache) :
    MaintenanceRepository {
    override suspend fun pendingDeletions(): StoreResult<List<RideId>> = archive.read { db ->
        db.statement("SELECT uuid FROM ride WHERE deleted_utc_us IS NOT NULL ORDER BY deleted_utc_us, id") {
            buildList { while (it.step()) add(RideId(it.getText(0))) }
        }
    }

    override suspend fun cleanupStep(rideId: RideId, maxRows: Int): StoreResult<Step> = archive.write { db ->
        val ride =
            db.statement("SELECT id, deleted_utc_us IS NOT NULL FROM ride WHERE uuid = ?") {
                it.bindText(1, rideId.value)
                if (it.step()) it.getLong(0) to (it.getLong(1) == 1L) else null
            } ?: return@write Step.done
        if (!ride.second) throw ConstraintViolation("cleanup of a ride that is not deleted")
        val table = CLEANUP_ORDER.firstOrNull { db.holds(it, ride.first) }
        if (table == null) {
            db.statement("DELETE FROM ride WHERE id = ?") {
                it.bindLong(1, ride.first)
                it.step()
            }
            Step.done
        } else {
            db.deleteFirst(table, ride.first, maxOf(1, maxRows))
            Step.more
        }
    }

    override suspend fun reclaimStep(): StoreResult<Step> {
        val archiveLeft =
            when (val reclaimed = archive.job { it.reclaim() }) {
                is StoreResult.Ok -> reclaimed.value
                is StoreResult.Failed -> return reclaimed
            }
        val cacheLeft = cache.maintenance { it.reclaim() } ?: 0L
        if (archiveLeft > 0 || cacheLeft > 0) return StoreResult.Ok(Step.more)
        return when (val checkpointed = checkpoint(truncate = true)) {
            is StoreResult.Ok -> StoreResult.Ok(Step.done)
            is StoreResult.Failed -> checkpointed
        }
    }

    override suspend fun checkpoint(truncate: Boolean): StoreResult<Unit> {
        val sql = "PRAGMA wal_checkpoint(${if (truncate) "TRUNCATE" else "PASSIVE"})"
        val archived = archive.job { it.execute(sql) }
        cache.maintenance { it.execute(sql) }
        return archived
    }
}

// A table's key after ride_id, in index order; health_sample's is health_sample_time with its rowid.
private class CleanupTable(val name: String, val key: List<String>, val textKey: Boolean = false)

private val CLEANUP_ORDER =
    listOf(
        CleanupTable("health_sample", listOf("elapsed_us", "id")),
        CleanupTable("location", listOf("elapsed_us", "seq")),
        CleanupTable("ride_event", listOf("seq")),
        CleanupTable("telemetry", listOf("elapsed_us", "session_no", "seq")),
        CleanupTable("controller_session", listOf("session_no")),
        CleanupTable("health_work", listOf("task", "scope"), textKey = true),
    )

// incremental_vacuum answers a row for each page it frees, so it runs to its end.
private fun SqlConnection.reclaim(): Long {
    statement("PRAGMA incremental_vacuum(${StorePolicy.vacuumPages})") { while (it.step()) {} }
    return long("PRAGMA freelist_count")
}

private fun SqlConnection.holds(table: CleanupTable, ride: Long): Boolean =
    statement("SELECT EXISTS (SELECT 1 FROM ${table.name} WHERE ride_id = ?)") {
        it.bindLong(1, ride)
        it.step() && it.getLong(0) == 1L
    }

// Deletes the table's first rows of the ride up to the key of its maxRows-th: the plain bound on the first key column
// is the index seek, and the row-value term keeps the rows at that value past the key.
private fun SqlConnection.deleteFirst(table: CleanupTable, ride: Long, maxRows: Int) {
    val columns = table.key.joinToString(", ")
    val last =
        statement("SELECT $columns FROM ${table.name} WHERE ride_id = ? ORDER BY $columns LIMIT 1 OFFSET ?") {
            it.bindLong(1, ride)
            it.bindLong(2, maxRows - 1L)
            if (it.step())
                List<Any>(table.key.size) { index -> if (table.textKey) it.getText(index) else it.getLong(index) }
            else null
        }
    if (last == null) {
        statement("DELETE FROM ${table.name} WHERE ride_id = ?") {
            it.bindLong(1, ride)
            it.step()
        }
        return
    }
    val first = table.key.first()
    val rest =
        if (table.key.size == 1) "" else " AND ($columns) <= (${table.key.indices.joinToString(", ") { "?${it + 2}" }})"
    statement("DELETE FROM ${table.name} WHERE ride_id = ?1 AND $first <= ?2$rest") {
        it.bindLong(1, ride)
        last.forEachIndexed { index, value -> it.bindKey(index + 2, value) }
        it.step()
    }
}

private fun SQLiteStatement.bindKey(index: Int, value: Any) {
    if (value is String) bindText(index, value) else bindLong(index, value as Long)
}
