package app.powerlog.core.analytics

import app.powerlog.core.presentation.*
import kotlin.test.*

class StartDistanceTest {
    @Test
    fun selectionResolvesFromFrozenOptionsRatherThanTheFirstAvailableSample() {
        assertEquals("gps:phone", DistancePolicy.resolveAtStart("auto", true, false))
        assertEquals("controller", DistancePolicy.resolveAtStart("auto", false, false))
        assertEquals("controller", DistancePolicy.resolveAtStart("auto", true, true))
        assertEquals("controller", DistancePolicy.resolveAtStart("controller", true, false))
        assertFailsWith<IllegalArgumentException> { DistancePolicy.resolveAtStart("gps:phone", false, false) }
        assertFailsWith<IllegalArgumentException> { DistancePolicy.resolveAtStart("gps:watch", true, false) }
    }

    @Test
    fun watchRidesKeepPhoneGPSAndPhoneArchiveWhileChoosingWatchHealthWriter() {
        val options = RideOptions.resolve(RequestedRideOptions(useWatch = true), PlatformCapabilities.iphone(true))
        assertEquals("phone", options.recordingOwner)
        assertEquals("watch", options.healthWriter)
        assertEquals("gps:phone", options.distanceSource)
        assertTrue(options.recordGPS)
        assertEquals("check", SetupPresentation.permission(null, RequestedRideOptions(useWatch = true)).action)
        assertEquals(
            "none",
            SetupPresentation.permission(null, RequestedRideOptions(useWatch = true, recordGPS = false)).action,
        )
        assertNull(options.copy(saveToHealth = false).healthWriter)
    }
}
