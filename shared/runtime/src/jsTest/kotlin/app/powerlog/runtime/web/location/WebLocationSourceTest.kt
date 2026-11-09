package app.powerlog.runtime.web.location

import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebLocationSourceTest {
    private val clock = FakeClock(monoUs = 5_000)
    private val queue = ArrayDeque<() -> Unit>()
    private val geolocation = FakeGeolocation()
    private val events = Recorded()
    private val source =
        WebLocationSource(geolocation.unsafeCast<Geolocation>(), clock, queue::addLast).apply { attach(events) }

    @Test
    fun startWatchesWithHighAccuracyAndNoCachedFixAndPauseAndResumeClearAndWatchAgain() {
        source.start()
        source.start()
        assertEquals(1, geolocation.watches.size)
        val options = geolocation.watches.single().options
        assertEquals(true, options.enableHighAccuracy)
        assertEquals(0, options.maximumAge)
        assertEquals(15_000, options.timeout)

        source.pause()
        assertEquals(listOf(1), geolocation.cleared)
        source.resume()
        assertEquals(2, geolocation.watches.size)
        source.stop()
        assertEquals(listOf(1, 2), geolocation.cleared)
    }

    @Test
    fun aFixIsDeliveredWithTheBrowsersValuesStampedInTheCallback() {
        source.start()
        clock.advanceTo(9_000)
        geolocation.position(
            js(
                "({ timestamp: 1767225600123.456, coords: { latitude: 52.5, longitude: 13.4, accuracy: 80," +
                    " altitude: 41.5, altitudeAccuracy: 6, heading: 270, speed: 5.5 } })"
            )
        )
        clock.advanceTo(20_000)
        drain()

        val (batch, at) = events.fixes.single()
        assertEquals(ClockReading(9_000, FakeClock.START_UTC_US + 4_000), at)
        assertEquals(
            LocationOriginal(
                utcUs = 1_767_225_600_123_456L,
                acquiredMonoUs = null,
                latitude = 52.5,
                longitude = 13.4,
                horizontalAccuracyM = 80.0,
                altitudeM = 41.5,
                altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
                verticalAccuracyM = 6.0,
                mslM = null,
                mslVerticalAccuracyM = null,
                speedMps = 5.5,
                speedAccuracyMps = null,
                courseDeg = 270.0,
                courseAccuracyDeg = null,
            ),
            batch.single(),
        )
        assertEquals(listOf(LocationProviderState.waiting, LocationProviderState.available), events.providers)
    }

    @Test
    fun missingAndNotANumberValuesBecomeNull() {
        source.start()
        geolocation.position(
            js(
                "({ timestamp: 1767225600000, coords: { latitude: 1, longitude: 2, accuracy: 10," +
                    " altitude: null, altitudeAccuracy: null, heading: NaN, speed: null } })"
            )
        )
        drain()

        val fix = events.fixes.single().first.single()
        assertNull(fix.altitudeM)
        assertNull(fix.verticalAccuracyM)
        assertNull(fix.courseDeg)
        assertNull(fix.speedMps)
    }

    @Test
    fun oldDuplicateAndInaccurateFixesAreAllDeliveredInOrder() {
        source.start()
        geolocation.position(js("({ timestamp: 1767225600000, coords: { latitude: 1, longitude: 2, accuracy: 80 } })"))
        geolocation.position(js("({ timestamp: 1767225600000, coords: { latitude: 1, longitude: 2, accuracy: 80 } })"))
        geolocation.position(js("({ timestamp: 1000, coords: { latitude: 1, longitude: 2, accuracy: 5 } })"))
        drain()

        assertEquals(
            listOf(1_767_225_600_000_000L, 1_767_225_600_000_000L, 1_000_000L),
            events.fixes.map {
                it.first.single().utcUs
            },
        )
    }

    @Test
    fun errorCodesReportTheProvider() {
        source.start()
        geolocation.error(1)
        geolocation.error(2)
        geolocation.error(3)
        drain()

        assertEquals(
            listOf(
                LocationProviderState.waiting,
                LocationProviderState.denied,
                LocationProviderState.unavailable,
                LocationProviderState.waiting,
            ),
            events.providers,
        )
    }

    @Test
    fun aPositionFromAClearedWatchIsDropped() {
        source.start()
        val first = geolocation.watches.single()
        source.stop()
        first.success(js("({ timestamp: 1, coords: { latitude: 1, longitude: 2, accuracy: 5 } })"))
        drain()

        assertTrue(events.fixes.isEmpty())
    }

    @Test
    fun nothingArrivesInsideTheCall() {
        source.start()
        assertTrue(events.providers.isEmpty())
        drain()
        assertEquals(listOf(LocationProviderState.waiting), events.providers)
    }

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }

    private class Recorded : LocationEvents {
        val fixes = mutableListOf<Pair<List<LocationOriginal>, ClockReading>>()
        val providers = mutableListOf<LocationProviderState>()

        override fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading) {
            fixes += batch to at
        }

        override fun providerChanged(state: LocationProviderState) {
            providers += state
        }
    }
}

internal class FakeGeolocation {
    class Watch(val id: Int, val success: (dynamic) -> Unit, val error: (dynamic) -> Unit, val options: dynamic)

    val watches = mutableListOf<Watch>()
    val cleared = mutableListOf<Int>()
    val currentRequests = mutableListOf<Pair<(dynamic) -> Unit, (dynamic) -> Unit>>()

    @JsName("watchPosition")
    fun watchPosition(success: (dynamic) -> Unit, error: (dynamic) -> Unit, options: dynamic): Int {
        val watch = Watch(watches.size + 1, success, error, options)
        watches += watch
        return watch.id
    }

    @JsName("clearWatch")
    fun clearWatch(id: Int) {
        cleared += id
    }

    @JsName("getCurrentPosition")
    fun getCurrentPosition(success: (dynamic) -> Unit, error: (dynamic) -> Unit, options: dynamic) {
        currentRequests += success to error
    }

    fun position(position: dynamic) {
        watches.last().success(position)
    }

    fun error(code: Int) {
        val error: dynamic = js("({})")
        error.code = code
        watches.last().error(error)
    }
}
