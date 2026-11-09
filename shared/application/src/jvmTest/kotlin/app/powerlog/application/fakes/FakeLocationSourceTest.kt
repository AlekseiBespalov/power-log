package app.powerlog.application.fakes

import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeLocationSourceTest {
    private val queue = FakePortQueue()
    private val clock = FakeClock()
    private val source = FakeLocationSource(clock, queue::post)
    private val batches = mutableListOf<Pair<List<LocationOriginal>, ClockReading>>()
    private val providers = mutableListOf<LocationProviderState>()

    private val events =
        object : LocationEvents {
            override fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading) {
                batches += batch to at
            }

            override fun providerChanged(state: LocationProviderState) {
                providers += state
            }
        }

    @Test
    fun commandsNeedAttachFirstAndMoveTheMode() {
        assertFailsWith<IllegalStateException> { source.start() }
        source.attach(events)

        source.start()
        source.pause()
        assertEquals(FakeLocationSource.Mode.paused, source.mode)
        source.resume()
        source.stop()

        assertEquals(listOf("start", "pause", "resume", "stop"), source.commands)
        assertEquals(FakeLocationSource.Mode.stopped, source.mode)
    }

    @Test
    fun aBatchIsDeliveredAsGivenInOrderStampedWhenItArrives() {
        source.attach(events)
        source.start()
        val coarse = fix(utcUs = 2_000, accuracyM = 80.0)
        val old = fix(utcUs = 1_000, accuracyM = 5.0)
        clock.advanceTo(3_000)

        source.fixes(listOf(coarse, old, old))
        clock.advance(500)
        assertTrue(batches.isEmpty())
        queue.drain()

        assertEquals(listOf(listOf(coarse, old, old) to ClockReading(3_000, FakeClock.START_UTC_US + 3_000)), batches)
    }

    @Test
    fun providerChangesArriveInOrder() {
        source.attach(events)

        source.provider(LocationProviderState.denied)
        source.provider(LocationProviderState.servicesOff)
        source.provider(LocationProviderState.waiting)
        queue.drain()

        assertEquals(
            listOf(LocationProviderState.denied, LocationProviderState.servicesOff, LocationProviderState.waiting),
            providers,
        )
    }

    private fun fix(utcUs: Long, accuracyM: Double) =
        LocationOriginal(
            utcUs = utcUs,
            acquiredMonoUs = null,
            latitude = 51.5,
            longitude = -0.1,
            horizontalAccuracyM = accuracyM,
            altitudeM = null,
            altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
            verticalAccuracyM = null,
            mslM = null,
            mslVerticalAccuracyM = null,
            speedMps = null,
            speedAccuracyMps = null,
            courseDeg = null,
            courseAccuracyDeg = null,
        )
}
