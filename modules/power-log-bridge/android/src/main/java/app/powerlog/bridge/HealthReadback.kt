package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import app.powerlog.core.health.Component
import app.powerlog.core.health.ComponentResult
import app.powerlog.core.health.ComponentState
import app.powerlog.core.health.HealthIds
import app.powerlog.core.health.HealthReason
import java.time.Instant

internal class HealthReadback(
    private val store: RideStore,
    private val access: HealthConnectAccess,
    private val now: () -> Long = { timeUs(Instant.now().toString()) },
) {
    private fun scope(id: String): String {
        val m = store.metadata(id)
        return "${m.str("startedAt")}/${m.str("endedAt") }"
    }

    fun reset(id: String) {
        store.writableDatabase.delete("health_query_progress", "ride=?", arrayOf(id))
        store.deferHealthReads(id, false)
    }

    private fun eligible(grants: Set<String>, mode: app.powerlog.core.health.ExecutionMode) =
        mode != app.powerlog.core.health.ExecutionMode.background ||
            access.backgroundReadSupported && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grants

    fun readDenied(
        id: String,
        type: kotlin.reflect.KClass<out Record>,
        grants: Set<String>,
        mode: app.powerlog.core.health.ExecutionMode,
    ) {
        if (!eligible(grants, mode)) {
            store.deferHealthReads(id, true)
            return
        }
        for (requested in types) {
            if (
                type == ExerciseSessionRecord::class ||
                    requested == type ||
                    HealthPermission.getReadPermission(requested) !in grants
            )
                store.setHealthProgress(
                    id,
                    HealthQueryProgress("healthConnect", metric(requested), scope(id), "denied"),
                )
        }
    }

    suspend fun wake(id: String, wake: HealthWake) {
        val m = store.metadata(id)
        if (m["endedAt"] == null) return
        val grants = access.granted()
        if (!eligible(grants, wake.executionMode)) {
            store.deferHealthReads(id, true)
            return
        }
        store.deferHealthReads(id, false)
        for (type in types) {
            if (!store.exists(id)) return
            val metric = metric(type)
            val progress = HealthQueryProgress("healthConnect", metric, scope(id), null)
            var previous = store.healthProgress(id, progress)
            if (
                previous?.get("cursor") in listOf("denied", "scopeIncomplete") ||
                    (previous?.get("attempts") as? Number)?.toInt()?.let { it >= 10 } == true
            )
                continue
            if (
                previous?.get("cursor") == "complete" &&
                    ((previous["nextRefreshUs"] as? String)?.toLong() ?: Long.MAX_VALUE) > now()
            )
                continue
            if (HealthPermission.getReadPermission(type) !in grants) {
                store.setHealthProgress(id, progress.copy(cursor = "denied"))
                continue
            }
            if (!wake.admit(app.powerlog.core.health.CallBucket.read, 0)) return
            if (previous?.get("cursor") == "complete") {
                val refresh = (previous?.get("nextRefreshUs") as? String)?.toLong() ?: Long.MAX_VALUE
                if (refresh > now()) continue
                store.setHealthProgress(id, progress)
                previous = null
            }
            var complete = previous?.let { requireNotNull(it["scopeComplete"] as? Boolean) } ?: true
            try {
                val token = (previous?.get("cursor") as? String)?.takeUnless { it == "denied" }
                val page =
                    access.read(
                        type,
                        HealthRecords.instant(timeUs(m.str("startedAt"))),
                        HealthRecords.instant(timeUs(m.str("endedAt"))).let {
                            if (timeUs(m.str("endedAt")) % 1000 != 0L) it.plusMillis(1) else it
                        },
                        token,
                        false,
                    )
                if (!store.exists(id)) return
                require(page.records.size <= 128)
                complete = complete && page.scopeComplete
                if (!complete) store.setHealthProgress(id, progress.copy(cursor = token), scopeComplete = false)
                run {
                    for (record in page.records) {
                        import(id, record, token)
                    }
                    store.clearHealthQuota(id, "readback:$metric")
                    store.setHealthProgress(
                        id,
                        progress.copy(cursor = page.next ?: if (complete) "complete" else "scopeIncomplete"),
                        nextRefreshUs =
                            if (page.next == null)
                                listOf(900_000_000L, 3_600_000_000L)
                                    .map { timeUs(m.str("endedAt")) + it }
                                    .firstOrNull { it > now() }
                            else null,
                        scopeComplete = complete,
                    )
                }
            } catch (error: SecurityException) {
                if (!store.exists(id)) return
                store.setHealthProgress(id, progress.copy(cursor = "denied"))
            } catch (error: Exception) {
                if (!store.exists(id)) return
                val attempts = (previous?.get("attempts") as? Number)?.toInt() ?: 0
                if (HealthFailures.quota(error)) {
                    store.setHealthProgress(
                        id,
                        progress.copy(cursor = previous?.get("cursor") as? String),
                        attempts,
                        error.message,
                        scopeComplete = complete,
                    )
                    store.healthQuotaWait(id, "readback:$metric", now())
                } else {
                    store.clearHealthQuota(id, "readback:$metric")
                    store.setHealthProgress(
                        id,
                        progress.copy(cursor = previous?.get("cursor") as? String),
                        attempts + 1,
                        error.message,
                        scopeComplete = complete,
                    )
                    val retry = app.powerlog.core.health.HealthBudget.retry(attempts + 1, false, null, now())
                    val budget = store.healthBudget(app.powerlog.core.health.CallBucket.read, now())
                    store.putHealthBudget(
                        budget.copy(nextEligibleUs = retry.delayUs?.let { now() + it }),
                        app.powerlog.core.health.CallBucket.read,
                        now(),
                    )
                }
                return
            }
        }
    }

    fun result(id: String, revision: Long): ComponentResult {
        if (store.metadata(id)["endedAt"] == null)
            return ComponentResult(
                Component.readback,
                revision,
                true,
                ComponentState.pending,
                null,
                emptyList(),
            )
        val cursors = types.map {
            store.healthProgress(
                id,
                HealthQueryProgress("healthConnect", metric(it), scope(id), null),
            )
        }
        val pending = cursors.any {
            it == null ||
                it["cursor"] !in listOf("complete", "denied", "scopeIncomplete") &&
                    (it["attempts"] as? Number)?.toInt()?.let { n -> n >= 10 } != true
        }
        val denied = cursors.any { it?.get("cursor") == "denied" }
        val failure = cursors.any {
            it?.get("cursor") == "scopeIncomplete" ||
                (it?.get("attempts") as? Number)?.toInt()?.let { n -> n >= 10 } == true
        }
        return ComponentResult(
            Component.readback,
            revision,
            true,
            if (pending) ComponentState.pending
            else if (denied || failure) ComponentState.unavailable else ComponentState.saved,
            if (denied) HealthReason.permission else if (failure) HealthReason.readbackUnavailable else null,
            emptyList(),
        )
    }

    fun import(id: String, record: Record, nativeCursor: String? = null) {
        if (publishedByPowerLog(record)) return
        val providerId = record.metadata.id.lowercase()
        val origin = record.metadata.dataOrigin.packageName
        val measure = metric(record::class)
        // Import each provider record once. Refresh discovers later records, not provider edit/deletion history.
        if (
            store.select(
                "SELECT 1 FROM health_imported_records WHERE ride=? AND data_origin=? AND provider_id=?",
                arrayOf(id, origin, providerId),
            ) {
                it.moveToFirst()
            }
        )
            return
        data class Sample(val a: Instant, val b: Instant, val value: Double?, val unit: String)
        val samples =
            when (record) {
                is HeartRateRecord ->
                    record.samples.map { Sample(it.time, it.time, it.beatsPerMinute.toDouble(), "bpm") }
                is ActiveCaloriesBurnedRecord ->
                    listOf(Sample(record.startTime, record.endTime, record.energy.inKilocalories, "kcal"))
                is RespiratoryRateRecord -> listOf(Sample(record.time, record.time, record.rate, "breaths/min"))
                is OxygenSaturationRecord -> listOf(Sample(record.time, record.time, record.percentage.value, "%"))
                is HeartRateVariabilityRmssdRecord ->
                    listOf(Sample(record.time, record.time, record.heartRateVariabilityMillis, "ms"))
                is PowerRecord -> record.samples.map { Sample(it.time, it.time, it.power.inWatts, "W") }
                is CyclingPedalingCadenceRecord ->
                    record.samples.map { Sample(it.time, it.time, it.revolutionsPerMinute, "rpm") }
                is SpeedRecord -> record.samples.map { Sample(it.time, it.time, it.speed.inMetersPerSecond, "m/s") }
                is DistanceRecord -> listOf(Sample(record.startTime, record.endTime, record.distance.inMeters, "m"))
                is ExerciseSessionRecord -> listOf(Sample(record.startTime, record.endTime, null, "association"))
                else -> error("Unsupported Health readback type")
            }
        if (samples.isEmpty()) return
        store.transaction {
            if (!store.exists(id)) return@transaction
            var sequence = store.producerHighWater(id, "phone")
            samples.withIndex().chunked(128).forEach { page ->
                val originals = page.mapNotNull { (index, sample) ->
                    val event = stableEventId("health:healthConnect:$origin:$providerId:$index")
                    if (store.existingHealthOriginal(id, event) != null) return@mapNotNull null
                    val start = timeUs(sample.a.toString())
                    val end = timeUs(sample.b.toString())
                    val spans = store.healthCollectionIntervals(id, start, end)
                    HealthOriginal(
                        Origin("healthConnect", origin, providerId, event, "phone", ++sequence),
                        measure,
                        sample.unit,
                        when (record) {
                            is ExerciseSessionRecord -> "workoutAssociation"
                            is ActiveCaloriesBurnedRecord,
                            is DistanceRecord -> "intervalRecord"
                            is HeartRateRecord,
                            is PowerRecord,
                            is CyclingPedalingCadenceRecord,
                            is SpeedRecord -> "rawSeries"
                            else -> "rawQuantity"
                        },
                        sample.a.toString(),
                        sample.b.toString(),
                        spans.firstOrNull()?.startUs ?: start,
                        spans.lastOrNull()?.endUs ?: end,
                        sample.value,
                        Association.RideWindow(0, elapsedUs(store.timing(id).elapsed)),
                        memberIndex = index.toLong(),
                        device = record.metadata.device?.toString(),
                        parentMetadata =
                            if (record is ExerciseSessionRecord)
                                mapOf(
                                    "exerciseType" to record.exerciseType,
                                    "title" to record.title,
                                    "queryMeasure" to measure,
                                )
                            else null,
                        collectionMapped = spans.isNotEmpty(),
                        collectionSegment = spans.firstOrNull()?.segment ?: 0,
                    )
                }
                store.appendHealthPage(
                    id,
                    originals,
                    HealthQueryProgress("healthConnect", measure, scope(id), nativeCursor),
                )
            }
            store.writableDatabase.execSQL(
                "INSERT INTO health_imported_records VALUES(?,?,?)",
                arrayOf(id, origin, providerId),
            )
        }
    }

    fun nextRefreshUs(id: String): Long? =
        store.select(
            "SELECT min(CAST(json_extract(payload,'$.nextRefreshUs') AS INTEGER)) FROM health_query_progress WHERE ride=? AND measure!='publicationParent' AND json_extract(payload,'$.cursor')='complete'",
            arrayOf(id),
        ) {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    companion object {
        val types =
            listOf(
                HeartRateRecord::class,
                ActiveCaloriesBurnedRecord::class,
                RespiratoryRateRecord::class,
                OxygenSaturationRecord::class,
                HeartRateVariabilityRmssdRecord::class,
                PowerRecord::class,
                CyclingPedalingCadenceRecord::class,
                SpeedRecord::class,
                DistanceRecord::class,
                ExerciseSessionRecord::class,
            )

        fun metric(type: kotlin.reflect.KClass<out Record>) =
            when (type) {
                HeartRateRecord::class -> "heartRate"
                ActiveCaloriesBurnedRecord::class -> "activeEnergy"
                RespiratoryRateRecord::class -> "respiratoryRate"
                OxygenSaturationRecord::class -> "oxygenSaturation"
                HeartRateVariabilityRmssdRecord::class -> "hrvRMSSD"
                PowerRecord::class -> "riderPower"
                CyclingPedalingCadenceRecord::class -> "cyclingCadence"
                SpeedRecord::class -> "cyclingSpeed"
                DistanceRecord::class -> "cyclingDistance"
                ExerciseSessionRecord::class -> "association"
                else -> error("Unsupported Health readback record")
            }

        fun publishedByPowerLog(record: Record): Boolean =
            record.metadata.clientRecordId?.let { runCatching { HealthIds.syncId(it) }.isSuccess } == true
    }
}
