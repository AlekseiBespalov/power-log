package app.powerlog.bridge

import android.app.ActivityManager
import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import app.powerlog.core.health.*
import java.time.Instant
import kotlinx.coroutines.runBlocking

internal data class HealthExportResult(
    val state: String,
    val written: Int = 0,
    val omitted: Int = 0,
    val reason: String? = null,
    val components: List<ComponentResult> = emptyList(),
)

internal class HealthWake(
    private val store: RideStore,
    private val mode: () -> ExecutionMode,
    private val now: () -> Long,
) {
    val executionMode
        get() = mode()

    private val started = now()
    var calls = 0
        private set

    fun admit(bucket: CallBucket, bytes: Long): Boolean {
        val time = now()
        if (HealthBudget.wakeCalls(1, calls, started, time) == 0) return false
        val budget = store.healthBudget(bucket, time)
        if (budget.nextEligibleUs?.let { it > time } == true) return false
        val execution = mode()
        val contributions = HealthBudget.prune(budget.calls.toTypedArray(), time)
        val admission = HealthBudget.admit(contributions, bucket, execution, bytes, time)
        if (!admission.allowed) {
            store.putHealthBudget(
                PublicationBudget(
                    Provider.healthConnect,
                    contributions.toList(),
                    admission.resumeAtUs,
                ),
                bucket,
                time,
            )
            return false
        }
        store.putHealthBudget(
            PublicationBudget(
                Provider.healthConnect,
                contributions.toList() + BudgetCall(time, bucket, execution, bytes),
                null,
            ),
            bucket,
            time,
        )
        calls++
        return true
    }
}

internal class HealthExport(
    private val context: Context,
    private val store: RideStore,
    access: HealthConnectAccess = AndroidHealthConnectAccess(context),
    private val now: () -> Long = { timeUs(Instant.now().toString()) },
    private val mode: () -> ExecutionMode = { nativeMode() },
    providerTimeoutMillis: Long = 10_000,
) {
    private val access = BoundedHealthConnectAccess(access, providerTimeoutMillis)
    private val plans = HealthPlans(context, store)
    private val readback = HealthReadback(store, this.access, now)
    val available
        get() = access.available

    fun writesPending(id: String, requiresRead: Boolean = false): Boolean =
        !requiresRead &&
            store.exists(id) &&
            store.metadata(id).flag("saveToHealth") &&
            store.metadata(id)["endedAt"] != null &&
            store.healthJob(id)?.let { it.status == "ready" && it.phase != "done" } == true

    fun nextEligibleUs(bucket: CallBucket): Long? = store.healthBudget(bucket, now()).nextEligibleUs

    fun nextWriteUs(id: String): Long =
        maxOf(now() + 1_000_000L, store.healthJob(id)?.resumeAtUs ?: 0, nextEligibleUs(CallBucket.write) ?: 0)

    fun nextRefreshUs(id: String): Long? {
        if (!store.exists(id) || !available || store.healthReadsDeferred(id)) return null
        val metadata = store.metadata(id)
        if (metadata["endedAt"] == null || timeUs(metadata.str("endedAt")) <= timeUs(metadata.str("startedAt")))
            return null
        return if (readback.result(id, store.sourceHealthRevision(id)).state == ComponentState.pending)
            maxOf(now() + 1_000_000L, nextEligibleUs(CallBucket.read) ?: 0)
        else readback.nextRefreshUs(id)
    }

    fun essentialPermissions() = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

    fun permissions(gps: Boolean) = buildSet {
        for (type in writeTypes) {
            add(HealthPermission.getWritePermission(type))
            add(HealthPermission.getReadPermission(type))
        }
        for (type in HealthReadback.types) add(HealthPermission.getReadPermission(type))
        if (gps) add(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE)
        if (access.backgroundReadSupported) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        if (access.historyReadSupported) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
    }

    fun granted(): Set<String> = runBlocking { access.granted() }

    fun status(): Payload {
        val grants = granted()
        fun authorization(permissions: Set<String>) = permissions.associateWith {
            if (it in grants) "authorized" else "denied"
        }
        return mapOf(
            "available" to available,
            "provider" to "healthConnect",
            "backgroundReadSupported" to access.backgroundReadSupported,
            "historyReadSupported" to access.historyReadSupported,
            "requiredWrites" to essentialPermissions().toList(),
            "unsupportedReadTypes" to
                listOf(
                        "hrvSDNN",
                        "physicalEffort",
                        "cyclingFTP",
                        "workoutEffort",
                        "estimatedWorkoutEffort",
                    )
                    .associateWith { "providerUnsupported" },
            "writeAuthorization" to
                authorization(
                    writeTypes.map { HealthPermission.getWritePermission(it) }.toSet() +
                        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
                ),
            "readAuthorization" to
                authorization(
                    buildSet {
                        for (type in writeTypes + HealthReadback.types) add(HealthPermission.getReadPermission(type))
                        if (access.backgroundReadSupported)
                            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                        if (access.historyReadSupported) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
                    }
                ),
        )
    }

    fun save(id: String): HealthExportResult = RideStore.withHealthExecution {
        try {
            runBlocking { wake(id) }
        } catch (error: Exception) {
            // Local Discard may finish while an already submitted provider call is returning.
            if (!store.exists(id)) HealthExportResult("notRequested") else throw error
        }
    }

    fun refresh(id: String) = RideStore.withHealthExecution {
        if (store.exists(id)) readback.reset(id)
    }

    // Only the explicit retry action can repeat a confirmed, no-effect write failure.
    fun repair(id: String) = RideStore.withHealthExecution {
        if (!store.exists(id)) return@withHealthExecution
        val job = store.healthJob(id)
        if (
            store.metadata(id).flag("saveToHealth") &&
                job?.status == "failed" &&
                job.reason in setOf("permission", "quota", "locked", "transientFailure")
        )
            store.putHealthJob(job.copy(status = "ready", reason = null, resumeAtUs = null, attempts = 0))
        readback.reset(id)
    }

    private suspend fun wake(id: String): HealthExportResult {
        if (!store.exists(id)) return HealthExportResult("notRequested")
        val metadata = store.metadata(id)
        if (metadata["endedAt"] == null)
            return HealthExportResult(if (metadata.flag("saveToHealth")) "pending" else "notRequested")
        val retainedJob = store.healthJob(id)
        if (!metadata.flag("saveToHealth") || retainedJob == null) {
            if (available && timeUs(metadata.str("endedAt")) > timeUs(metadata.str("startedAt")))
                readback.wake(id, HealthWake(store, mode, now))
            return complete(id)
        }
        var job: AndroidHealthJob = retainedJob
        if (timeUs(metadata.str("endedAt")) <= timeUs(metadata.str("startedAt"))) {
            store.putHealthJob(job.copy(status = "failed", reason = "clock"))
            return complete(id)
        }
        // A process may have died after provider submission. Never replay that batch.
        if (job.status == "submitted") {
            job = job.copy(status = "uncertain", reason = "ambiguous")
            store.putHealthJob(job)
        }
        if (!available) {
            if (job.status == "ready") store.putHealthJob(job.copy(status = "failed", reason = "providerUnsupported"))
            return complete(id)
        }
        val grants = access.granted()
        val wake = HealthWake(store, mode, now)
        var plannedPages = 0
        while (
            store.exists(id) &&
                plannedPages++ < 4 &&
                job.status == "ready" &&
                job.phase != "done" &&
                (job.resumeAtUs ?: 0) <= now()
        ) {
            val page =
                try {
                    plans.page(id, job, HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in grants)
                } catch (error: HealthProjectionException) {
                    store.putHealthJob(job.copy(status = "failed", reason = "invalid"))
                    break
                }
            val planned = page.records.map(Publication::plan)
            val (records, denied) =
                planned.partition { HealthPermission.getWritePermission(HealthRecords.type(it.component)) in grants }
            job = job.copy(omittedComponents = job.omittedComponents + denied.map { it.component.wire })
            if (denied.any { it.component == Component.workout }) {
                store.putHealthJob(job.copy(status = "failed", reason = "permission"))
                break
            }
            if (records.isEmpty()) {
                job = job.copy(phase = page.phase, position = page.position)
                store.putHealthJob(job)
                continue
            }
            val bytes = records.sumOf {
                ((it.frozenContent as NormalizedContent.Present).projection as AdapterProjection.HealthConnect)
                    .encodedBytes
            }
            if (!wake.admit(CallBucket.write, bytes)) break
            // One cursor is sufficient: retain no per-record payloads, versions, fences or mutation log.
            // Discard can win after planning; only an admitted submission may reach the provider.
            if (!store.putHealthJob(job.copy(status = "submitted", reason = null)))
                return HealthExportResult("notRequested")
            try {
                val ids = access.insert(records.map { HealthRecords.native(it) }).providerIds
                if (!store.exists(id)) return HealthExportResult("notRequested")
                check(ids.size == records.size) { "Incomplete Health Connect receipt" }
                job =
                    job.copy(
                        phase = page.phase,
                        position = page.position,
                        status = "ready",
                        resumeAtUs = null,
                        written = job.written + ids.size,
                        attempts = 0,
                        writtenComponents = job.writtenComponents + records.map { it.component.wire },
                        workoutId = if (job.phase == "workout") ids.single() else job.workoutId,
                    )
                store.putHealthJob(job)
            } catch (error: Exception) {
                if (!store.exists(id)) return HealthExportResult("notRequested")
                val reason =
                    when {
                        HealthFailures.quota(error) -> "quota"
                        error is SecurityException -> "permission"
                        error is IllegalArgumentException -> "invalid"
                        else -> "ambiguous"
                    }
                val retryQuota = reason == "quota" && job.attempts < 3
                job =
                    job.copy(
                        status = if (reason == "ambiguous") "uncertain" else if (retryQuota) "ready" else "failed",
                        reason = reason,
                        attempts = job.attempts + 1,
                        resumeAtUs = if (retryQuota) now() + 60_000_000L else null,
                    )
                store.putHealthJob(job)
                break
            }
        }
        job = store.healthJob(id) ?: return HealthExportResult("notRequested")
        if (job.phase == "done" && job.status == "ready") store.putHealthJob(job.copy(status = "saved"))
        // Collection is useful even when Health publication was denied or uncertain.
        readback.wake(id, wake)
        return complete(id)
    }

    private fun complete(id: String): HealthExportResult {
        if (!store.exists(id)) return HealthExportResult("notRequested")
        val metadata = store.metadata(id)
        val revision = store.sourceHealthRevision(id)
        val readFailure =
            when {
                !available -> HealthReason.readbackUnavailable
                timeUs(metadata.str("endedAt")) <= timeUs(metadata.str("startedAt")) -> HealthReason.clock
                else -> null
            }
        val reads =
            if (readFailure == null) readback.result(id, revision)
            else
                ComponentResult(
                    Component.readback,
                    revision,
                    true,
                    ComponentState.unavailable,
                    readFailure,
                    emptyList(),
                )
        val job = store.healthJob(id)
        if (!metadata.flag("saveToHealth") || job == null) {
            val previous = store.healthComponents(id).filter { it.component != Component.readback }
            val result = HealthExportResult(metadata.str("healthKitState"), components = previous + reads)
            store.healthStatus(id, result)
            return result
        }
        val state =
            when (job.status) {
                "saved" -> ComponentState.saved
                "failed" -> ComponentState.failed
                "uncertain",
                "submitted" -> ComponentState.unavailable
                else -> ComponentState.pending
            }
        val reason = job.reason?.let { name -> HealthReason.entries.firstOrNull { it.name == name } }
        val components =
            job.writtenComponents
                .filter { it != Component.workout.wire && it !in job.omittedComponents }
                .map { wire ->
                    ComponentResult(
                        Component.entries.first { it.wire == wire },
                        job.pinRevision,
                        true,
                        ComponentState.saved,
                        null,
                        emptyList(),
                    )
                } +
                job.omittedComponents
                    .filter { it != Component.workout.wire }
                    .map { wire ->
                        ComponentResult(
                            Component.entries.first { it.wire == wire },
                            job.pinRevision,
                            true,
                            ComponentState.unavailable,
                            HealthReason.permission,
                            emptyList(),
                        )
                    } +
                listOf(
                    ComponentResult(
                        Component.workout,
                        job.pinRevision,
                        true,
                        state,
                        reason,
                        listOfNotNull(job.workoutId),
                    ),
                    reads,
                )
        val result =
            HealthExportResult(
                when (state) {
                    ComponentState.saved -> "saved"
                    ComponentState.failed -> "failed"
                    ComponentState.unavailable -> "unavailable"
                    else -> "pending"
                },
                job.written,
                reason = job.reason,
                components = components,
            )
        store.healthStatus(id, result)
        return result
    }

    companion object {
        val writeTypes =
            listOf(
                ExerciseSessionRecord::class,
                PowerRecord::class,
                CyclingPedalingCadenceRecord::class,
                SpeedRecord::class,
                DistanceRecord::class,
            )

        private fun nativeMode(): ExecutionMode {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                ExecutionMode.foreground
            else ExecutionMode.background
        }

        fun clockFailure(start: Instant, end: Instant): HealthExportResult? =
            if (end > start) null
            else
                HealthExportResult(
                    "unavailable",
                    reason =
                        "Health Connect is unavailable for this ride because its clock cutoff is not after its start.",
                )
    }
}
