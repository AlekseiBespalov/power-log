package app.powerlog.bridge

import android.os.Build

internal object HealthFailures {
    fun quota(error: Throwable): Boolean {
        if (
            Build.VERSION.SDK_INT >= 34 &&
                error is android.health.connect.HealthConnectException &&
                error.errorCode == android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
        )
            return true
        return error.message?.contains("quota", true) == true ||
            error.message?.contains("rate limit", true) == true ||
            error.cause?.let { quota(it) } == true
    }
}
