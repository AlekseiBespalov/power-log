package app.powerlog.platform.device

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.text.format.DateFormat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.util.LocalePreferences
import app.powerlog.application.port.DeviceConditions
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.DeviceSnapshot
import app.powerlog.application.port.ExitReport
import app.powerlog.application.port.ThermalLevel
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.LocaleFacts
import app.powerlog.domain.settings.MeasurementSystem
import java.util.Locale

class AndroidDeviceInfo(private val context: Context) : DeviceInfo {
    override fun snapshot(): DeviceSnapshot {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val locale = Locale.getDefault()
        return DeviceSnapshot(
            platform = Platform.android,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkLevel = Build.VERSION.SDK_INT,
            appVersion = packageInfo.versionName.orEmpty(),
            appBuild = PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
            uses24h = DateFormat.is24HourFormat(context),
            locale = locale.toLanguageTag(),
            localeFacts = facts(locale),
        )
    }

    override fun conditions(): DeviceConditions {
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceConditions(
            powerSave = power?.isPowerSaveMode,
            thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus?.let(::thermal) else null,
            processCpuUs = Process.getElapsedCpuTime() * 1_000L,
            footprintBytes = Debug.getPss() * 1_024L,
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted,
            standbyBucket = context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket,
        )
    }

    override fun previousExit(): ExitReport? {
        if (Build.VERSION.SDK_INT < 30) return null
        val exit =
            context
                .getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull() ?: return null
        return ExitReport(exit.timestamp * 1_000L, reason(exit.reason), exit.status, exit.importance)
    }

    internal companion object {
        fun facts(locale: Locale): LocaleFacts =
            LocaleFacts(
                region = locale.country.ifEmpty { null },
                measurementSystem =
                    when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                        LocaleData.MeasurementSystem.US -> MeasurementSystem.us
                        LocaleData.MeasurementSystem.UK -> MeasurementSystem.uk
                        LocaleData.MeasurementSystem.SI -> MeasurementSystem.metric
                        else -> null
                    },
                temperature =
                    when (LocalePreferences.getTemperatureUnit(locale, false)) {
                        LocalePreferences.TemperatureUnit.CELSIUS -> TemperatureUnit.celsius
                        LocalePreferences.TemperatureUnit.FAHRENHEIT -> TemperatureUnit.fahrenheit
                        else -> null
                    },
            )

        // PowerManager.THERMAL_STATUS_*: NONE, LIGHT, MODERATE, SEVERE, then CRITICAL, EMERGENCY and SHUTDOWN.
        fun thermal(status: Int): ThermalLevel =
            when (status) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.nominal
                PowerManager.THERMAL_STATUS_LIGHT,
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.fair
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.serious
                else -> ThermalLevel.critical
            }

        fun reason(reason: Int): String =
            when (reason) {
                ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                ApplicationExitInfo.REASON_SIGNALED -> "signaled"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
                ApplicationExitInfo.REASON_OTHER -> "other"
                ApplicationExitInfo.REASON_FREEZER -> "freezer"
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
                ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
                else -> "unknown"
            }
    }
}
