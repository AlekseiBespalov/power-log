package app.powerlog.runtime.web.device

import app.powerlog.application.port.DeviceConditions
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.settings.LocaleFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDeviceInfoTest {
    @Test
    fun theSnapshotNamesTheBrowserBrandItsPlatformTheLanguageAndItsRegion() {
        val brands =
            """[{"brand":"Chromium","version":"142"},{"brand":"Google Chrome","version":"142"},""" +
                """{"brand":"Not_A Brand","version":"99"}]"""
        val info = WebDeviceInfo("0.7.0", navigator("en-GB", JSON.parse(brands), "macOS"))

        val snapshot = info.snapshot()

        assertEquals(Platform.web, snapshot.platform)
        assertEquals("Google Chrome", snapshot.manufacturer)
        assertEquals("142", snapshot.model)
        assertEquals("macOS", snapshot.osVersion)
        assertNull(snapshot.sdkLevel)
        assertEquals("0.7.0", snapshot.appVersion)
        assertEquals("0.7.0", snapshot.appBuild)
        assertEquals("en-GB", snapshot.locale)
        assertTrue(snapshot.uses24h)
        assertEquals(LocaleFacts(region = "GB", measurementSystem = null, temperature = null), snapshot.localeFacts)
    }

    @Test
    fun aTwelveHourLanguageAndALanguageWithoutRegionAreReportedAsTheyAre() {
        val snapshot = WebDeviceInfo("0.7.0", navigator("en-US", null, null)).snapshot()
        assertFalse(snapshot.uses24h)
        assertEquals("US", snapshot.localeFacts.region)
        assertEquals("", snapshot.manufacturer)
        assertEquals("", snapshot.osVersion)

        val german = WebDeviceInfo("0.7.0", navigator("de", null, null)).snapshot()
        assertTrue(german.uses24h)
        assertEquals("DE", german.localeFacts.region)
    }

    @Test
    fun aMalformedLanguageHasNoRegion() {
        assertNull(WebDeviceInfo("0.7.0", navigator("not a tag", null, null)).snapshot().localeFacts.region)
    }

    @Test
    fun theBrowserReportsNoConditionsAndNoPreviousExit() {
        val info = WebDeviceInfo("0.7.0", navigator("en-GB", null, null))
        assertEquals(DeviceConditions(null, null, null, null, null, null, null), info.conditions())
        assertNull(info.previousExit())
    }

    private fun navigator(language: String, brands: dynamic, platform: String?): BrowserNavigator {
        val navigator: dynamic = js("({})")
        navigator.language = language
        if (brands != null || platform != null) {
            val data: dynamic = js("({})")
            data.brands = brands
            data.platform = platform
            navigator.userAgentData = data
        }
        return navigator.unsafeCast<BrowserNavigator>()
    }
}
