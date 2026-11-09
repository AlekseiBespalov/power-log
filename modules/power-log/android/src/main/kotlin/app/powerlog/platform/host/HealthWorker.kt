package app.powerlog.platform.host

import android.content.Context
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.platform.scheduler.AndroidBackgroundScheduler
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class HealthWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (applicationContext.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return Result.retry()
        val rideId = inputData.getString(AndroidBackgroundScheduler.RIDE) ?: return Result.failure()
        val host = PowerLogAndroid.host ?: return Result.retry()
        val result = suspendCancellableCoroutine { done ->
            host.runHealthWork(rideId, runAttemptCount) { result -> if (done.isActive) done.resume(result) }
        }
        return when (result) {
            HealthWorkResult.success -> Result.success()
            HealthWorkResult.retry -> Result.retry()
            HealthWorkResult.failure -> Result.failure()
        }
    }
}
