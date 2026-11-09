package app.powerlog.kit

import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitFacadeTest {
    private val options: dynamic =
        js(
            "({ sampleRate: '8', indoor: false, useWatch: false, saveToHealth: true, recordGps: true, " +
                "distanceSource: 'gps:phone', batteryMethod: 'controller' })"
        )

    @Test
    fun textsTakeSerialNamesAndWireObjects() {
        val failure = textsFailure("bluetoothDenied", null, "android", 30)
        assertEquals("Power Log can't use Bluetooth. Allow Location for Power Log in Settings.", failure.text)
        assertEquals("Something went wrong.", textsFailure("noSuchCode", null, "ios", null).message)
        assertEquals(
            "Power Log can't open your saved rides. Clear this site's data to start over.",
            textsFailure("storageRefused", "unreadable", "web", null).text,
        )
        assertEquals("Running", textsPhaseChip("running"))
        assertEquals("Weak GPS signal", textsSource("gps", "weak", options, "appleHealth")?.label)
        assertEquals("warning", textsSource("gps", "weak", options, null)?.tone)
        assertNull(textsSource("gps", "noSuchState", options, null))
        assertEquals("GPS · Browser", textsDistanceSource("gps:phone", true))
        assertEquals("8 Hz", textsChoice("sampleRate", "8", "web"))
        assertEquals("Connected", textsConnection("connected", "live"))
    }

    @Test
    fun healthTextsReadTheHealthView() {
        val view: dynamic =
            js(
                "({ provider: 'healthConnect', writer: 'phone', outcome: 'failed', reason: 'quota', components: " +
                    "[{ component: 'workout', state: 'saved', reason: null }, " +
                    "{ component: 'rider-power', state: 'failed', reason: 'permission' }, " +
                    "{ component: 'riderPower', state: 'failed', reason: 'permission' }] })"
            )
        assertEquals("Health Connect not saved", textsHealth(view))
        assertEquals("Health Connect is temporarily limiting requests.", textsHealthReason(view))
        val components = view.components.unsafeCast<Array<dynamic>>()
        assertContentEquals(
            arrayOf("Rider power not saved: Allow Power Log to save workouts in Health Connect permissions."),
            textsHealthComponents(components, "healthConnect"),
        )
        assertEquals(
            "Health Connect not saved · 1 item waiting for permission: Allow Power Log to save workouts in " +
                "Health Connect permissions.",
            textsHistoryHealthLine(view, components),
        )
        val settings: dynamic =
            js(
                "({ sampleRate: '2', distance: 'auto', battery: 'controller', indoor: false, useWatch: false, " +
                    "saveToHealth: true, recordGps: null })"
            )
        val effective: dynamic =
            js(
                "({ sampleRate: '2', indoor: false, useWatch: false, saveToHealth: false, recordGps: true, " +
                    "distanceSource: 'gps:phone', batteryMethod: 'controller' })"
            )
        assertEquals(
            "Phone-recorded rides cannot be saved to Health on this device. This ride will stay in Power Log.",
            textsHealthSetup(effective, settings, null),
        )
        assertNull(textsHealthSetup(null, settings, null))
    }

    @Test
    fun formatsTakeMicrosecondsAsNumbers() {
        assertEquals("1:01:01", formatDuration(3_661_000_000.0))
        assertEquals("—", formatDuration(null))
        assertEquals("−00:02.250", formatElapsedPrecise(-2_250_000.0))
        assertEquals("2026-11-02T14:05:06.789Z", formatIsoTimestamp(1_793_628_306_789_999.0))
        assertEquals("22.4", formatMetric("speedMps", 10.0, "imperial", "celsius"))
        assertEquals("+0.20", formatDifference("batteryVoltageV", 36.5, 36.7, "metric", "celsius"))
        assertEquals("1.61", formatDistance(1_609.344, "metric"))
        assertEquals("mph", unitsDisplay("controllerSpeedMps", "imperial", "fahrenheit")?.symbol)
        assertEquals("CYC X6", formatBikeName("X6", null, null))
    }

    @Test
    fun theCatalogSubstitutesTheRawSpeedSignalAndListsEveryAppearance() {
        assertContentEquals(
            arrayOf("humanPowerW", "speedRaw"),
            metricCatalogShown(arrayOf("humanPowerW", "controllerSpeedMps"), arrayOf("speedRaw", "humanPowerW")),
        )
        assertEquals("controllerSpeedMps", metricCatalogStored("speedRaw"))
        val appearances = metricAppearances()
        assertEquals(35, appearances.size)
        assertEquals(33, appearances.count { it.selectable })
        assertEquals("rider", metricAppearance("humanPowerW")?.group)
        assertEquals("Rider power", appearances.first { it.id == "humanPowerW" }.label)
    }

    @Test
    fun ridesDatesAndCountsFormatThroughIntl() {
        val utcUs = Date.UTC(2026, 10, 2, 12, 5) * 1_000
        val sameYear = rideDate(utcUs, utcUs, uses24h = true, locale = "en-US")
        assertTrue(Regex("^[A-Z][a-z]{2}, Nov \\d{1,2} · \\d{2}:\\d{2}$").matches(sameYear), sameYear)
        val otherYear = rideDate(utcUs, utcUs + 400.0 * 86_400_000_000, uses24h = false, locale = "en-US")
        assertTrue(
            Regex("^[A-Z][a-z]{2}, Nov \\d{1,2}, 2026 · \\d{1,2}:\\d{2}\\s?[AP]M$").matches(otherYear),
            otherYear,
        )
        assertEquals("1,234", integer(1_234.0, "en-US"))
        assertEquals("1.234", integer(1_234.0, "de-DE"))
    }
}
