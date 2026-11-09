package app.powerlog.platform.device

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.PowerManager
import app.powerlog.application.port.ThermalLevel
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.MeasurementSystem
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidDeviceInfoTest {
    private val context = RuntimeEnvironment.getApplication()
    private val info = AndroidDeviceInfo(context)

    @Test
    fun theSnapshotNamesTheDeviceTheOsAndTheLocale() {
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setModel("SM-S908B")
        ShadowBuild.setVersionRelease("15")
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        try {
            val snapshot = info.snapshot()
            assertEquals(Platform.android, snapshot.platform)
            assertEquals("samsung", snapshot.manufacturer)
            assertEquals("SM-S908B", snapshot.model)
            assertEquals("15", snapshot.osVersion)
            assertEquals(35, snapshot.sdkLevel)
            assertEquals("en-US", snapshot.locale)
            assertEquals("US", snapshot.localeFacts.region)
            assertEquals(MeasurementSystem.us, snapshot.localeFacts.measurementSystem)
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun localeFactsMapTheMeasurementSystemAndAnExplicitTemperatureUnit() {
        assertEquals(MeasurementSystem.uk, AndroidDeviceInfo.facts(Locale.UK).measurementSystem)
        assertEquals(MeasurementSystem.metric, AndroidDeviceInfo.facts(Locale.GERMANY).measurementSystem)
        assertNull(AndroidDeviceInfo.facts(Locale.GERMANY).temperature)
        assertNull(AndroidDeviceInfo.facts(Locale.forLanguageTag("und")).region)
        assertEquals(
            TemperatureUnit.fahrenheit,
            AndroidDeviceInfo.facts(Locale.forLanguageTag("de-DE-u-mu-fahrenhe")).temperature,
        )
        assertEquals(
            TemperatureUnit.celsius,
            AndroidDeviceInfo.facts(Locale.forLanguageTag("en-US-u-mu-celsius")).temperature,
        )
    }

    @Test
    fun conditionsReadPowerSaveThermalCpuAndMemory() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsPowerSaveMode(true)
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        val conditions = info.conditions()
        assertEquals(true, conditions.powerSave)
        assertEquals(ThermalLevel.serious, conditions.thermal)
        assertTrue(conditions.processCpuUs!! >= 0)
        assertTrue(conditions.footprintBytes!! >= 0)
    }

    @Test
    fun thermalStatusesFoldIntoFourLevels() {
        assertEquals(ThermalLevel.nominal, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(ThermalLevel.fair, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(ThermalLevel.fair, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(ThermalLevel.serious, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(ThermalLevel.critical, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(ThermalLevel.critical, AndroidDeviceInfo.thermal(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun thePreviousExitIsTheNewestReasonInLowerCaseWithoutItsPrefix() {
        assertNull(info.previousExit())
        val activity = shadowOf(context.getSystemService(ActivityManager::class.java))
        activity.addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE)
                .setStatus(6)
                .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
                .setTimestamp(1_793_520_000_123L)
                .build()
        )
        val exit = info.previousExit()!!
        assertEquals("crash_native", exit.reason)
        assertEquals(6, exit.status)
        assertEquals(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, exit.importance)
        assertEquals(1_793_520_000_123_000L, exit.atUtcUs)
    }

    @Test
    fun everyExitReasonHasItsName() {
        assertEquals("anr", AndroidDeviceInfo.reason(ApplicationExitInfo.REASON_ANR))
        assertEquals("low_memory", AndroidDeviceInfo.reason(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("package_updated", AndroidDeviceInfo.reason(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("unknown", AndroidDeviceInfo.reason(ApplicationExitInfo.REASON_UNKNOWN))
    }
}
