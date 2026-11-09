package app.powerlog.platform.scheduler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.Clock
import app.powerlog.application.port.ScheduleKind
import java.util.concurrent.TimeUnit

class AndroidBackgroundScheduler(
    private val context: Context,
    private val worker: Class<out ListenableWorker>,
    private val clock: Clock,
) : BackgroundScheduler {
    override fun schedule(rideId: String, notBeforeUtcUs: Long?, policy: ScheduleKind) {
        val delayMs = notBeforeUtcUs?.let { maxOf(0, (it - clock.utcUs()) / 1_000) } ?: 0
        val request =
            OneTimeWorkRequest.Builder(worker)
                .setInputData(workDataOf(RIDE to rideId))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(rideId), existing(policy), request)
    }

    override fun cancel(rideId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(rideId))
    }

    private fun existing(policy: ScheduleKind): ExistingWorkPolicy =
        when (policy) {
            ScheduleKind.keep -> ExistingWorkPolicy.KEEP
            ScheduleKind.replace -> ExistingWorkPolicy.REPLACE
            ScheduleKind.afterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }

    companion object {
        const val RIDE = "ride"
        private const val BACKOFF_SECONDS = 10L

        fun name(rideId: String): String = "PowerLogHealth:$rideId"
    }
}
