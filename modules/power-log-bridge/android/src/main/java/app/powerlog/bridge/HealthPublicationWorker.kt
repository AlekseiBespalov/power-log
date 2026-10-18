package app.powerlog.bridge

import android.content.Context
import androidx.work.*
import java.time.Instant
import java.util.concurrent.TimeUnit

open class HealthPublicationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (applicationContext.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false)
            return Result.retry()
        val id = inputData.getString("ride") ?: return Result.failure()
        val store = RideStore.shared(applicationContext)
        return try {
            if (!store.exists(id)) return Result.success()
            val exporter = publisher(store)
            exporter.save(id)
            val next = if (exporter.writesPending(id)) exporter.nextWriteUs(id) else exporter.nextRefreshUs(id)
            if (next != null && store.exists(id)) continueAt(id, next)
            Result.success()
        } catch (error: Exception) {
            if (!store.exists(id)) return Result.success()
            android.util.Log.e("PowerLog", "Health work failed", error)
            if (runAttemptCount < 3) Result.retry()
            else {
                runCatching {
                    store.healthJob(id)?.let { job ->
                        if (job.status in setOf("ready", "submitted"))
                            store.putHealthJob(
                                job.copy(
                                    status = if (job.status == "submitted") "uncertain" else "failed",
                                    reason = if (job.status == "submitted") "ambiguous" else "transientFailure",
                                )
                            )
                    }
                }
                    .onFailure { android.util.Log.e("PowerLog", "Could not retain Health failure", it) }
                Result.failure()
            }
        } finally {
            RecordingEngine.healthCompleted()
        }
    }

    internal open fun publisher(store: RideStore) = HealthExport(applicationContext, store)

    internal open fun continueAt(id: String, next: Long?) {
        schedule(applicationContext, id, next, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    companion object {
        fun schedule(
            context: Context,
            id: String,
            resumeAtUs: Long? = null,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val delay = resumeAtUs?.let { maxOf(0, (it - timeUs(Instant.now().toString())) / 1000) } ?: 0
            val request =
                OneTimeWorkRequestBuilder<HealthPublicationWorker>()
                    .setInputData(workDataOf("ride" to id))
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork("PowerLogHealth:$id", policy, request)
        }
    }
}
