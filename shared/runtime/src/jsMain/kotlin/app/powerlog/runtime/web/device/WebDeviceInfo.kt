package app.powerlog.runtime.web.device

import app.powerlog.application.port.DeviceConditions
import app.powerlog.application.port.DeviceInfo
import app.powerlog.application.port.DeviceSnapshot
import app.powerlog.application.port.ExitReport
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.LocaleFacts

internal class WebDeviceInfo(private val appVersion: String, private val navigator: BrowserNavigator) : DeviceInfo {
    override fun snapshot(): DeviceSnapshot {
        val brand = brand()
        val locale = navigator.language.orEmpty()
        return DeviceSnapshot(
            platform = Platform.web,
            manufacturer = brand?.brand.orEmpty(),
            model = brand?.version.orEmpty(),
            osVersion = navigator.userAgentData?.platform.orEmpty(),
            sdkLevel = null,
            appVersion = appVersion,
            appBuild = appVersion,
            uses24h = uses24h(locale),
            locale = locale,
            localeFacts = LocaleFacts(region = region(locale), measurementSystem = null, temperature = null),
        )
    }

    override fun conditions(): DeviceConditions =
        DeviceConditions(
            powerSave = null,
            thermal = null,
            processCpuUs = null,
            footprintBytes = null,
            ignoringBatteryOptimizations = null,
            backgroundRestricted = null,
            standbyBucket = null,
        )

    override fun previousExit(): ExitReport? = null

    private fun brand(): BrowserBrand? {
        val brands = navigator.userAgentData?.brands.orEmpty().filterNot { greased.containsMatchIn(it.brand) }
        return brands.firstOrNull { it.brand != "Chromium" } ?: brands.firstOrNull()
    }

    private fun uses24h(locale: String): Boolean {
        val cycle =
            try {
                Intl.DateTimeFormat(locale.ifEmpty { undefined }, hourOnly()).resolvedOptions().hourCycle
            } catch (_: dynamic) {
                null
            }
        return cycle == "h23" || cycle == "h24"
    }

    private fun region(locale: String): String? =
        try {
            Intl.Locale(locale.ifEmpty { "und" }).maximize().region
        } catch (_: dynamic) {
            null
        }

    private companion object {
        // User-agent client hints add an invented brand such as "Not_A Brand" so sites can't rely on the list
        // (GREASE, https://wicg.github.io/ua-client-hints/#grease).
        val greased = Regex("""not.?a.?brand""", RegexOption.IGNORE_CASE)

        fun hourOnly(): dynamic = js("({ hour: 'numeric' })")
    }
}

internal external interface BrowserNavigator {
    val language: String?
    val userAgentData: UserAgentData?
}

internal external interface UserAgentData {
    val brands: Array<BrowserBrand>?
    val platform: String?
}

internal external interface BrowserBrand {
    val brand: String
    val version: String
}

private external object Intl {
    class Locale(tag: String) {
        val region: String?

        fun maximize(): Locale
    }

    class DateTimeFormat(locales: String?, options: dynamic) {
        fun resolvedOptions(): ResolvedDateTimeOptions
    }
}

private external interface ResolvedDateTimeOptions {
    val hourCycle: String?
}
