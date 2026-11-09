package app.powerlog.platform.location

import android.location.Location
import android.location.LocationManager
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationCompat
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal
import app.powerlog.platform.ble.StepClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidLocationSourceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(LocationManager::class.java)
    private val clock = StepClock()
    private val events = Recorded()
    private var conversions = 0
    private val source =
        AndroidLocationSource(context, Looper.getMainLooper(), clock) { location ->
            conversions++
            LocationCompat.setMslAltitudeMeters(location, location.altitude - 47.0)
            LocationCompat.setMslAltitudeAccuracyMeters(location, 4f)
        }

    init {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        source.attach(events)
    }

    @Test
    fun startAsksTheGpsProviderEverySecondWithNoDistanceFilterAndPauseAndResumeRemoveAndAskAgain() {
        source.start()
        source.start()
        val requests = shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER)
        assertEquals(1, requests.size)
        assertEquals(1_000L, requests.single().intervalMillis)
        assertEquals(0f, requests.single().minUpdateDistanceMeters)

        source.pause()
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        source.resume()
        assertEquals(1, shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).size)
        source.stop()
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
    }

    @Test
    fun aFixCarriesTheOsValuesItsAcquisitionInstantAndTheMslAltitudeStampedWhenItsCallbackRuns() {
        source.start()
        clock.mono = 7_000
        shadowOf(manager)
            .simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    time = 1_767_225_600_123L
                    elapsedRealtimeNanos = 5_000_123_000L
                    latitude = 52.5
                    longitude = 13.4
                    accuracy = 4.5f
                    altitude = 80.0
                    verticalAccuracyMeters = 3f
                    speed = 6.25f
                    speedAccuracyMetersPerSecond = 0.5f
                    bearing = 90f
                    bearingAccuracyDegrees = 2f
                }
            )
        clock.mono = 9_000
        shadowOf(Looper.getMainLooper()).idle()

        val (batch, at) = events.fixes.single()
        assertEquals(ClockReading(9_000, StepClock().utcUs() + 9_000), at)
        assertEquals(
            LocationOriginal(
                utcUs = 1_767_225_600_123_000L,
                acquiredMonoUs = 5_000_123L,
                latitude = 52.5,
                longitude = 13.4,
                horizontalAccuracyM = 4.5,
                altitudeM = 80.0,
                altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
                verticalAccuracyM = 3.0,
                mslM = 33.0,
                mslVerticalAccuracyM = 4.0,
                speedMps = 6.25,
                speedAccuracyMps = 0.5,
                courseDeg = 90.0,
                courseAccuracyDeg = 2.0,
            ),
            batch.single(),
        )
        assertEquals(listOf(LocationProviderState.waiting, LocationProviderState.available), events.providers)
    }

    @Test
    fun theMslConversionAndTheFixRunOnTheRideLooperNotTheMainThread() {
        val ride = HandlerThread("PowerLogRide").apply { start() }
        try {
            var converted: Thread? = null
            val delivered = mutableListOf<Thread>()
            val onRide =
                AndroidLocationSource(context, ride.looper, clock) { location ->
                    converted = Thread.currentThread()
                    LocationCompat.setMslAltitudeMeters(location, 1.0)
                }
            onRide.attach(
                object : LocationEvents {
                    override fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading) {
                        delivered += Thread.currentThread()
                    }

                    override fun providerChanged(state: LocationProviderState) = Unit
                }
            )
            onRide.start()
            shadowOf(ride.looper).idle()
            shadowOf(manager)
                .simulateLocation(
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 1.0
                        longitude = 2.0
                        altitude = 12.0
                    }
                )
            shadowOf(ride.looper).idle()

            assertSame(ride, converted)
            assertEquals(listOf<Thread>(ride), delivered)
            assertTrue(converted !== Looper.getMainLooper().thread)
        } finally {
            ride.quitSafely()
        }
    }

    @Test
    fun valuesTheOsDoesNotReportAreNullAndWithoutAltitudeNothingIsConverted() {
        source.start()
        shadowOf(manager)
            .simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    time = 1_000L
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    latitude = 1.0
                    longitude = 2.0
                }
            )
        shadowOf(Looper.getMainLooper()).idle()

        val fix = events.fixes.single().first.single()
        assertNull(fix.horizontalAccuracyM)
        assertNull(fix.altitudeM)
        assertNull(fix.verticalAccuracyM)
        assertNull(fix.mslM)
        assertNull(fix.mslVerticalAccuracyM)
        assertNull(fix.speedMps)
        assertNull(fix.speedAccuracyMps)
        assertNull(fix.courseDeg)
        assertNull(fix.courseAccuracyDeg)
        assertEquals(0, conversions)
    }

    @Test
    fun aFailedConversionLeavesTheEllipsoidAltitudeAlone() {
        val failing = AndroidLocationSource(context, Looper.getMainLooper(), clock) { error("no geoid data") }
        val events = Recorded()
        failing.attach(events)
        failing.start()
        shadowOf(manager)
            .simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 1.0
                    longitude = 2.0
                    altitude = 12.0
                }
            )
        shadowOf(Looper.getMainLooper()).idle()

        val fix = events.fixes.single().first.single()
        assertEquals(12.0, fix.altitudeM)
        assertNull(fix.mslM)
    }

    @Test
    fun oldDuplicateAndInaccurateFixesArriveAsOneBatchInTheirOrder() {
        source.start()
        val fixes =
            listOf(3_000L, 3_000L, 1_000L).mapIndexed { index, time ->
                Location(LocationManager.GPS_PROVIDER).apply {
                    this.time = time
                    latitude = index.toDouble()
                    longitude = 0.0
                    accuracy = if (index == 2) 80f else 5f
                }
            }
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single().onLocationChanged(fixes)

        val batch = events.fixes.single().first
        assertEquals(listOf(3_000_000L, 3_000_000L, 1_000_000L), batch.map { it.utcUs })
        assertEquals(listOf(0.0, 1.0, 2.0), batch.map { it.latitude })
    }

    @Test
    fun turningLocationServicesOffAndOnReportsTheProvider() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        source.start()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(
                LocationProviderState.servicesOff,
                LocationProviderState.waiting,
                LocationProviderState.servicesOff,
            ),
            events.providers,
        )
    }

    @Test
    fun aFixAfterStopIsDropped() {
        source.start()
        source.stop()
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events.fixes.isEmpty())
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
