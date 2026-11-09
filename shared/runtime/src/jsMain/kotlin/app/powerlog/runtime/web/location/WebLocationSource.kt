package app.powerlog.runtime.web.location

import app.powerlog.application.port.Clock
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.LocationSource
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.recording.LocationOriginal
import kotlin.math.floor

internal class WebLocationSource(
    private val geolocation: Geolocation,
    private val clock: Clock,
    private val post: (() -> Unit) -> Unit,
) : LocationSource {
    private var events: LocationEvents? = null
    private var watch: Int? = null
    private var generation = 0
    private var reported: LocationProviderState? = null

    override fun attach(locationEvents: LocationEvents) {
        events = locationEvents
    }

    override fun start() {
        if (watch != null) return
        reported = null
        report(LocationProviderState.waiting)
        watch()
    }

    override fun pause() {
        clear()
    }

    override fun resume() {
        if (watch == null) watch()
    }

    override fun stop() {
        clear()
    }

    private fun watch() {
        val current = ++generation
        watch =
            try {
                geolocation.watchPosition(
                    { position -> if (current == generation) received(position) },
                    { error -> if (current == generation) report(provider(error.code)) },
                    options(),
                )
            } catch (_: dynamic) {
                report(LocationProviderState.unavailable)
                null
            }
    }

    private fun clear() {
        generation++
        watch?.let { id -> runCatching { geolocation.clearWatch(id) } }
        watch = null
    }

    private fun received(position: GeolocationPosition) {
        val at = clock.read()
        val fix = original(position)
        report(LocationProviderState.available)
        if (fix != null) post { events?.fixesReceived(listOf(fix), at) }
    }

    private fun report(state: LocationProviderState) {
        if (reported == state) return
        reported = state
        post { events?.providerChanged(state) }
    }

    internal companion object {
        private fun options(): dynamic = js("({ enableHighAccuracy: true, maximumAge: 0, timeout: 15000 })")

        // GeolocationPositionError codes: 1 PERMISSION_DENIED, 2 POSITION_UNAVAILABLE, 3 TIMEOUT.
        fun provider(code: Int): LocationProviderState =
            when (code) {
                1 -> LocationProviderState.denied
                3 -> LocationProviderState.waiting
                else -> LocationProviderState.unavailable
            }

        fun original(position: GeolocationPosition): LocationOriginal? {
            val timestamp = position.timestamp
            if (!timestamp.isFinite()) return null
            val coords = position.coords
            return LocationOriginal(
                utcUs = floor(timestamp * 1_000.0).toLong(),
                acquiredMonoUs = null,
                latitude = coords.latitude,
                longitude = coords.longitude,
                horizontalAccuracyM = finite(coords.accuracy),
                altitudeM = finite(coords.altitude),
                altitudeDatum = AltitudeDatum.wgs84Ellipsoid,
                verticalAccuracyM = finite(coords.altitudeAccuracy),
                mslM = null,
                mslVerticalAccuracyM = null,
                speedMps = finite(coords.speed),
                speedAccuracyMps = null,
                courseDeg = finite(coords.heading),
                courseAccuracyDeg = null,
            )
        }

        private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }
    }
}

internal external interface Geolocation {
    fun watchPosition(
        success: (GeolocationPosition) -> Unit,
        error: (GeolocationPositionError) -> Unit,
        options: dynamic,
    ): Int

    fun clearWatch(id: Int)
}

internal external interface GeolocationPosition {
    val coords: GeolocationCoordinates
    val timestamp: Double
}

internal external interface GeolocationCoordinates {
    val latitude: Double
    val longitude: Double
    val accuracy: Double?
    val altitude: Double?
    val altitudeAccuracy: Double?
    val heading: Double?
    val speed: Double?
}

internal external interface GeolocationPositionError {
    val code: Int
}
