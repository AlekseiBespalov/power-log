package app.powerlog.data

import androidx.sqlite.SQLiteStatement
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.HealthTask
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.time.RideTiming
import kotlinx.coroutines.flow.Flow

internal class SqlArchiveWriter(private val archive: SqlArchive) : ArchiveWriter {
    override val commits: Flow<CommitNotice> = archive.notices.commits
    override val historyCommits: Flow<CommitNotice> = archive.notices.historyCommits

    override suspend fun commit(batch: WriteBatch): StoreResult<Commit> {
        val applied =
            archive.write(
                keep = { it.commit is Commit.Committed },
                committed = { result ->
                    archive.historyRevision = result.historyRevision
                    result.notice?.let { archive.notices.emit(it, result.historyMoved) }
                },
            ) { db ->
                SqlBatch(db, batch, archive.historyRevision).apply()
            }
        return when (applied) {
            is StoreResult.Ok -> StoreResult.Ok(applied.value.commit)
            is StoreResult.Failed -> applied
        }
    }
}

private class Applied(
    val commit: Commit,
    val notice: CommitNotice?,
    val historyRevision: Long,
    val historyMoved: Boolean,
)

private class SqlBatch(
    private val db: SqlConnection,
    private val batch: WriteBatch,
    private val historyRevision: Long,
) {
    private val state = BatchState(batch, ::load)
    private val nextEventSeq = HashMap<Long, Long>()

    fun apply(): Applied {
        batch.closedRide?.let { closed ->
            state.closedRideAnswer(closed)?.let {
                return unchanged(it)
            }
        }
        for (mutation in batch.mutations) {
            val gone = apply(mutation)
            if (gone != null) return unchanged(Commit.RideGone(gone))
        }
        for (ride in state.settle()) update(ride)
        val history = if (state.historyMoved) historyRevision + 1 else historyRevision
        if (state.historyMoved)
            db.statement("UPDATE app_state SET history_revision = ? WHERE k = 1") {
                it.bindLong(1, history)
                it.step()
            }
        return Applied(state.committed(history), state.notice(history), history, state.historyMoved)
    }

    private fun unchanged(commit: Commit) = Applied(commit, null, historyRevision, historyMoved = false)

    private fun apply(mutation: Mutation): RideId? {
        when (mutation) {
            is Mutation.CreateRide -> create(mutation.ride, mutation.health)
            is Mutation.SetPhase -> state.setPhase(live(mutation.rideId) ?: return mutation.rideId, mutation.phase)
            is Mutation.Checkpoint -> state.checkpoint(live(mutation.rideId) ?: return mutation.rideId, mutation.timing)
            is Mutation.EndRide -> state.endRide(live(mutation.rideId) ?: return mutation.rideId, mutation.end)
            is Mutation.MarkRideDeleted -> state.markDeleted(mutation.rideId, mutation.atUtcUs)
            is Mutation.OpenControllerSession -> {
                val ride = live(mutation.session.rideId) ?: return mutation.session.rideId
                insertSession(ride.stored, mutation.session)
                state.factsInserted(ride)
            }
            is Mutation.AppendTelemetry -> {
                val ride = live(mutation.rideId) ?: return mutation.rideId
                if (mutation.rows.rows > 0) {
                    insertTelemetry(ride.stored, mutation.rows)
                    state.factsInserted(ride)
                }
            }
            is Mutation.AppendLocations -> {
                val ride = live(mutation.rideId) ?: return mutation.rideId
                if (mutation.fixes.isNotEmpty()) {
                    for (fix in mutation.fixes) insertLocation(ride.stored, fix)
                    state.factsInserted(ride)
                }
            }
            is Mutation.AppendEvents -> {
                val ride = live(mutation.rideId) ?: return mutation.rideId
                if (mutation.events.isNotEmpty()) {
                    for (event in mutation.events) insertEvent(ride.stored, event)
                    state.factsInserted(ride)
                }
            }
            is Mutation.AppendHealthSamples -> {
                val ride = live(mutation.rideId) ?: return mutation.rideId
                var inserted = false
                for (sample in mutation.samples) if (insertSample(ride.stored, sample)) inserted = true
                if (inserted) state.factsInserted(ride)
            }
            is Mutation.SaveHealthOutcome ->
                state.saveHealth(live(mutation.rideId) ?: return mutation.rideId, mutation.health)
            is Mutation.PutHealthWork ->
                for (work in mutation.items) {
                    val ride = live(work.rideId) ?: return work.rideId
                    if (putWork(ride.stored, work)) state.publishChanged(ride)
                }
            is Mutation.RemoveHealthWork -> {
                val ride = live(mutation.rideId) ?: return mutation.rideId
                if (removeWork(ride.stored, mutation.task)) state.publishChanged(ride)
            }
            is Mutation.RememberController ->
                db.statement(
                    "INSERT OR REPLACE INTO known_controller (bike_id, decoder, model, firmware, protocol) " +
                        "VALUES (?, ?, ?, ?, ?)"
                ) {
                    it.bindText(1, mutation.bikeId.value)
                    it.bindText(2, mutation.identity.decoder.value)
                    it.bindText(3, mutation.identity.model)
                    it.bindText(4, mutation.identity.firmware)
                    it.bindText(5, mutation.identity.protocol)
                    it.step()
                }
            is Mutation.SelectBike ->
                db.statement("UPDATE app_state SET selected_bike = ? WHERE k = 1") {
                    it.bindNullable(1, mutation.bikeId?.value)
                    it.step()
                }
        }
        return null
    }

    private fun live(rideId: RideId): RideState<Long>? = state.live(rideId)

    private fun load(rideId: RideId): RideState<Long>? =
        db.statement(
            "SELECT id, phase, interrupted, cp_utc_us, cp_elapsed_us, cp_timer_us, end_utc_us, end_elapsed_us, " +
                "end_timer_us, health_provider, health_outcome, health_reason, health_workout_id, data_revision, " +
                "deleted_utc_us FROM ride WHERE uuid = ?"
        ) {
            it.bindText(1, rideId.value)
            if (!it.step()) return@statement null
            RideState(
                rideId = rideId,
                stored = it.getLong(0),
                created = false,
                phase = Vocabularies.phase.value(it.getText(1)),
                interrupted = Vocabularies.flag(it.getLong(2)),
                checkpoint = RideTiming(it.getLong(3), it.getLong(4), it.getLong(5)),
                end = it.timing(6),
                health = it.rideHealth(9),
                dataRevision = it.getLong(13),
                deletedUtcUs = it.longOrNull(14),
            )
        }

    private fun create(ride: Ride, health: RideHealth) {
        val id =
            db.statement(
                "INSERT INTO ride (uuid, recorded_by, example, started_utc_us, sample_hz, indoor, use_watch, " +
                    "save_to_health, record_gps, distance_source, battery_method, phase, interrupted, cp_utc_us, " +
                    "cp_elapsed_us, cp_timer_us, end_utc_us, end_elapsed_us, end_timer_us, health_provider, " +
                    "health_outcome, health_reason, health_workout_id, data_revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
            ) {
                it.bindText(1, ride.id.value)
                it.bindText(2, Vocabularies.device.name(ride.recordedBy))
                it.bindFlag(3, ride.example)
                it.bindLong(4, ride.startedUtcUs)
                it.bindLong(5, ride.options.sampleRate.hz.toLong())
                it.bindFlag(6, ride.options.indoor)
                it.bindFlag(7, ride.options.useWatch)
                it.bindFlag(8, ride.options.saveToHealth)
                it.bindFlag(9, ride.options.recordGps)
                it.bindText(10, Vocabularies.distanceSource.name(ride.options.distanceSource))
                it.bindText(11, Vocabularies.batteryMethod.name(ride.options.batteryMethod))
                it.bindText(12, Vocabularies.phase.name(ride.phase))
                it.bindFlag(13, ride.interrupted)
                it.bindTiming(14, ride.checkpoint)
                it.bindNullableTiming(17, ride.end)
                it.bindHealth(20, health)
                it.step()
                db.long("SELECT last_insert_rowid()")
            }
        state.created(
            RideState(
                rideId = ride.id,
                stored = id,
                created = true,
                phase = ride.phase,
                interrupted = ride.interrupted,
                checkpoint = ride.checkpoint,
                end = ride.end,
                health = health,
                dataRevision = 1,
                deletedUtcUs = null,
            )
        )
    }

    private fun update(ride: RideState<Long>) {
        db.statement(
            "UPDATE ride SET phase = ?, interrupted = ?, cp_utc_us = ?, cp_elapsed_us = ?, cp_timer_us = ?, " +
                "end_utc_us = ?, end_elapsed_us = ?, end_timer_us = ?, health_provider = ?, health_outcome = ?, " +
                "health_reason = ?, health_workout_id = ?, data_revision = ?, deleted_utc_us = ? WHERE id = ?"
        ) {
            it.bindText(1, Vocabularies.phase.name(ride.phase))
            it.bindFlag(2, ride.interrupted)
            it.bindTiming(3, ride.checkpoint)
            it.bindNullableTiming(6, ride.end)
            it.bindHealth(9, ride.health)
            it.bindLong(13, ride.dataRevision)
            it.bindNullable(14, ride.deletedUtcUs)
            it.bindLong(15, ride.stored)
            it.step()
        }
    }

    private fun insertSession(ride: Long, session: ControllerSession) {
        db.statement(
            "INSERT INTO controller_session (ride_id, session_no, decoder, model, firmware, protocol) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ) {
            it.bindLong(1, ride)
            it.bindLong(2, session.sessionNo.toLong())
            it.bindNullable(3, session.identity?.decoder?.value)
            it.bindNullable(4, session.identity?.model)
            it.bindNullable(5, session.identity?.firmware)
            it.bindNullable(6, session.identity?.protocol)
            it.step()
        }
    }

    private fun insertTelemetry(ride: Long, rows: TelemetryRows) {
        requireTelemetryColumns(rows.quanta.keys)
        val columns = TELEMETRY_METRICS.map { rows.quanta[it] }
        db.statement(INSERT_TELEMETRY) {
            for (row in 0 until rows.rows) {
                it.bindLong(1, ride)
                it.bindLong(2, rows.elapsedUs[row].toLong())
                it.bindLong(3, rows.sessionNo[row].toLong())
                it.bindLong(4, rows.seq[row].toLong())
                it.bindLong(5, rows.utcUs[row].toLong())
                it.bindNullable(6, rows.activeInterval[row].takeIf { interval -> interval != 0 }?.toLong())
                it.bindLong(7, rows.run[row].toLong())
                columns.forEachIndexed { index, values ->
                    val value = values?.get(row)
                    it.bindNullable(8 + index, if (value == null || value.isNaN()) null else value.toLong())
                }
                it.step()
                it.reset()
            }
        }
    }

    private fun insertLocation(ride: Long, fix: LocationFix) {
        db.statement(
            "INSERT INTO location (ride_id, elapsed_us, seq, utc_us, active_interval, run, barrier, lat, lon, h_acc_m, " +
                "altitude_m, altitude_datum, v_acc_m, msl_m, msl_v_acc_m, speed_mps, speed_acc_mps, course_deg, " +
                "course_acc_deg) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ) {
            it.bindLong(1, ride)
            it.bindLong(2, fix.elapsedUs)
            it.bindLong(3, fix.seq)
            it.bindLong(4, fix.utcUs)
            it.bindNullable(5, fix.activeInterval?.toLong())
            it.bindLong(6, fix.run.toLong())
            it.bindFlag(7, fix.barrier)
            it.bindDouble(8, fix.latitude)
            it.bindDouble(9, fix.longitude)
            it.bindNullable(10, fix.horizontalAccuracyM)
            it.bindNullable(11, fix.altitudeM)
            it.bindText(12, Vocabularies.altitudeDatum.name(fix.altitudeDatum))
            it.bindNullable(13, fix.verticalAccuracyM)
            it.bindNullable(14, fix.mslM)
            it.bindNullable(15, fix.mslVerticalAccuracyM)
            it.bindNullable(16, fix.speedMps)
            it.bindNullable(17, fix.speedAccuracyMps)
            it.bindNullable(18, fix.courseDeg)
            it.bindNullable(19, fix.courseAccuracyDeg)
            it.step()
        }
    }

    private fun insertEvent(ride: Long, event: RideEvent) {
        val seq =
            nextEventSeq.getOrPut(ride) {
                db.statement("SELECT max(seq) FROM ride_event WHERE ride_id = ?") {
                    it.bindLong(1, ride)
                    it.step()
                    (it.longOrNull(0) ?: 0L) + 1
                }
            }
        nextEventSeq[ride] = seq + 1
        db.statement(
            "INSERT INTO ride_event (ride_id, seq, event_id, kind, utc_us, elapsed_us) VALUES (?, ?, ?, ?, ?, ?)"
        ) {
            it.bindLong(1, ride)
            it.bindLong(2, seq)
            it.bindText(3, event.id.value)
            it.bindText(4, Vocabularies.eventKind.name(event.kind))
            it.bindLong(5, event.utcUs)
            it.bindLong(6, event.elapsedUs)
            it.step()
        }
    }

    private fun insertSample(ride: Long, sample: HealthSample): Boolean {
        val stored =
            db.statement("SELECT 1 FROM health_sample WHERE ride_id = ? AND sample_key = ?") {
                it.bindLong(1, ride)
                it.bindText(2, sample.sampleKey)
                it.step()
            }
        if (stored) return false
        db.statement(
            "INSERT INTO health_sample (ride_id, elapsed_us, producer, sample_key, utc_us, start_utc_us, end_utc_us, " +
                "active_interval, metric, value, representation, sample_count, data_origin, parent_id, member) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ) {
            it.bindLong(1, ride)
            it.bindLong(2, sample.elapsedUs)
            it.bindText(3, Vocabularies.producer.name(sample.producer))
            it.bindText(4, sample.sampleKey)
            it.bindLong(5, sample.utcUs)
            it.bindNullable(6, sample.startUtcUs)
            it.bindNullable(7, sample.endUtcUs)
            it.bindNullable(8, sample.activeInterval?.toLong())
            it.bindNullable(9, sample.metric?.wire)
            it.bindNullable(10, sample.value)
            it.bindText(11, Vocabularies.representation.name(sample.representation))
            it.bindNullable(12, sample.sampleCount?.toLong())
            it.bindText(13, sample.dataOrigin)
            it.bindNullable(14, sample.parentId)
            it.bindNullable(15, sample.member?.toLong())
            it.step()
        }
        return true
    }

    private fun putWork(ride: Long, work: HealthWork): Boolean {
        val task = work.task()
        val scope = work.scopeName()
        val state = Vocabularies.componentState.name(work.state)
        val reason = work.reason?.let(Vocabularies.reason::name)
        val before =
            db.statement("SELECT state, reason FROM health_work WHERE ride_id = ? AND task = ? AND scope = ?") {
                it.bindLong(1, ride)
                it.bindText(2, task.name)
                it.bindText(3, scope)
                if (it.step()) it.getText(0) to it.textOrNull(1) else null
            }
        db.statement(
            "INSERT OR REPLACE INTO health_work (ride_id, task, scope, state, attempts, cursor, due_utc_us, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ) {
            it.bindLong(1, ride)
            it.bindText(2, task.name)
            it.bindText(3, scope)
            it.bindText(4, state)
            it.bindLong(5, work.attempts.toLong())
            it.bindNullable(6, work.cursor)
            it.bindNullable(7, work.dueUtcUs)
            it.bindNullable(8, reason)
            it.step()
        }
        return task == HealthTask.publish && before != (state to reason)
    }

    private fun removeWork(ride: Long, task: HealthTask?): Boolean {
        val publish =
            (task == null || task == HealthTask.publish) &&
                db.statement("SELECT EXISTS (SELECT 1 FROM health_work WHERE ride_id = ? AND task = ?)") {
                    it.bindLong(1, ride)
                    it.bindText(2, HealthTask.publish.name)
                    it.step() && it.getLong(0) == 1L
                }
        if (task == null)
            db.statement("DELETE FROM health_work WHERE ride_id = ?") {
                it.bindLong(1, ride)
                it.step()
            }
        else
            db.statement("DELETE FROM health_work WHERE ride_id = ? AND task = ?") {
                it.bindLong(1, ride)
                it.bindText(2, task.name)
                it.step()
            }
        return publish
    }

    private companion object {
        val INSERT_TELEMETRY =
            "INSERT INTO telemetry (ride_id, elapsed_us, session_no, seq, utc_us, active_interval, run, " +
                TELEMETRY_METRICS.joinToString(", ") { it.column() } +
                ") VALUES (" +
                List(7 + TELEMETRY_METRICS.size) { "?" }.joinToString(", ") +
                ")"
    }
}

internal fun HealthWork.task(): HealthTask =
    when (scope) {
        is HealthWorkScope.Publish -> HealthTask.publish
        is HealthWorkScope.Readback -> HealthTask.readback
    }

internal fun HealthWork.scopeName(): String =
    when (val scope = scope) {
        is HealthWorkScope.Publish -> Vocabularies.component.name(scope.component)
        is HealthWorkScope.Readback -> scope.metric.wire
    }

private fun SQLiteStatement.bindTiming(index: Int, timing: RideTiming) {
    bindLong(index, timing.utcUs)
    bindLong(index + 1, timing.elapsedUs)
    bindLong(index + 2, timing.timerUs)
}

private fun SQLiteStatement.bindNullableTiming(index: Int, timing: RideTiming?) {
    bindNullable(index, timing?.utcUs)
    bindNullable(index + 1, timing?.elapsedUs)
    bindNullable(index + 2, timing?.timerUs)
}

private fun SQLiteStatement.bindHealth(index: Int, health: RideHealth) {
    bindNullable(index, health.provider?.let(Vocabularies.provider::name))
    bindText(index + 1, Vocabularies.outcome.name(health.outcome))
    bindNullable(index + 2, health.reason?.let(Vocabularies.reason::name))
    bindNullable(index + 3, health.workoutId)
}
