@file:Suppress("MissingPermission")

package app.powerlog.platform.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import app.powerlog.application.port.Clock
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.LocationSource
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.recording.LocationOriginal

class AndroidLocationSource(
    private val context: Context,
    private val looper: Looper,
    private val clock: Clock,
    private val msl: (Location) -> Unit = { AltitudeConverterCompat.addMslAltitudeToLocation(context, it) },
) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(looper)
    private var events: LocationEvents? = null
    private var listening = false
    private var reported: LocationProviderState? = null

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = received(listOf(location))

            override fun onLocationChanged(locations: MutableList<Location>) = received(locations.toList())

            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) report(LocationProviderState.waiting)
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) report(LocationProviderState.servicesOff)
            }
        }

    override fun attach(locationEvents: LocationEvents) {
        events = locationEvents
    }

    override fun start() {
        if (listening) return
        reported = null
        request()
    }

    override fun pause() {
        remove()
    }

    override fun resume() {
        if (!listening) request()
    }

    override fun stop() {
        remove()
    }

    private fun request() {
        val manager = manager ?: return post(LocationProviderState.unavailable)
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, looper)
            listening = true
            post(
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationProviderState.waiting
                else LocationProviderState.servicesOff
            )
        } catch (_: SecurityException) {
            post(LocationProviderState.denied)
        } catch (_: IllegalArgumentException) {
            post(LocationProviderState.unavailable)
        }
    }

    private fun remove() {
        if (!listening) return
        listening = false
        manager?.removeUpdates(listener)
    }

    private fun received(locations: List<Location>) {
        val at = clock.read()
        if (!listening) return
        val batch = locations.map(::original)
        report(LocationProviderState.available)
        events?.fixesReceived(batch, at)
    }

    private fun post(state: LocationProviderState) {
        handler.post { report(state) }
    }

    private fun report(state: LocationProviderState) {
        if (reported == state) return
        reported = state
        events?.providerChanged(state)
    }

    internal fun original(location: Location): LocationOriginal {
        val altitude = location.altitude.takeIf { location.hasAltitude() && it.isFinite() }
        val (mslM, mslAccuracy) = if (altitude == null) null to null else mslOf(location)
        return LocationOriginal(
            utcUs = location.time * 1_000L,
            acquiredMonoUs = location.elapsedRealtimeNanos / 1_000L,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalAccuracyM = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
            altitudeM = altitude,
            altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
            verticalAccuracyM =
                location.verticalAccuracyMeters.toDouble().takeIf { location.hasVerticalAccuracy() && it.isFinite() },
            mslM = mslM,
            mslVerticalAccuracyM = mslAccuracy,
            speedMps = location.speed.toDouble().takeIf { location.hasSpeed() && it.isFinite() },
            speedAccuracyMps = location.speedAccuracyMetersPerSecond.toDouble().takeIf { location.hasSpeedAccuracy() },
            courseDeg = location.bearing.toDouble().takeIf { location.hasBearing() },
            courseAccuracyDeg = location.bearingAccuracyDegrees.toDouble().takeIf { location.hasBearingAccuracy() },
        )
    }

    private fun mslOf(location: Location): Pair<Double?, Double?> {
        val converted = Location(location)
        LocationCompat.removeMslAltitude(converted)
        LocationCompat.removeMslAltitudeAccuracy(converted)
        val converts = runCatching { msl(converted) }.isSuccess
        val altitude =
            if (converts && LocationCompat.hasMslAltitude(converted))
                LocationCompat.getMslAltitudeMeters(converted).takeIf { it.isFinite() }
            else null
        val accuracy =
            if (altitude != null && LocationCompat.hasMslAltitudeAccuracy(converted))
                LocationCompat.getMslAltitudeAccuracyMeters(converted).toDouble().takeIf { it.isFinite() }
            else null
        return altitude to accuracy
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}
