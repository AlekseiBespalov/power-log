package app.powerlog.bridge

import android.content.Context
import android.location.Location
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat

internal fun interface MslConversion {
    fun convert(location: Location)
}

internal fun mslConversion(context: Context): MslConversion = MslConversion {
    AltitudeConverterCompat.addMslAltitudeToLocation(context, it)
}

internal data class AltitudeEvidence(
    val originalAltitudeDatum: String,
    val originalAltitudeMeters: Double?,
    val originalVerticalAccuracyM: Double?,
    val altitudeMeters: Double?,
    val verticalAccuracyM: Double?,
) {
    init {
        require(originalAltitudeDatum in setOf("msl", "wgs84Ellipsoid", "unknown"))
        require(
            listOf(originalAltitudeMeters, originalVerticalAccuracyM, altitudeMeters, verticalAccuracyM).all {
                it == null || it.isFinite()
            }
        )
    }

    fun payload(): Payload =
        mapOf(
            "originalAltitudeDatum" to originalAltitudeDatum,
            "originalAltitudeMeters" to originalAltitudeMeters,
            "originalVerticalAccuracyM" to originalVerticalAccuracyM,
            "altitudeMeters" to altitudeMeters,
            "verticalAccuracyM" to verticalAccuracyM,
        )

    fun readings(): Map<String, Double> = buildMap {
        altitudeMeters?.let { put("altitudeMeters", it) }
        verticalAccuracyM?.let { put("verticalAccuracyM", it) }
    }

    companion object {
        fun capture(location: Location, conversion: MslConversion): AltitudeEvidence {
            val original = location.altitude.takeIf { location.hasAltitude() && it.isFinite() }
            val accuracy =
                location.verticalAccuracyMeters.toDouble().takeIf { location.hasVerticalAccuracy() && it.isFinite() }
            val converted = Location(location)
            LocationCompat.removeMslAltitude(converted)
            LocationCompat.removeMslAltitudeAccuracy(converted)
            val success = original != null && runCatching { conversion.convert(converted) }.isSuccess
            val msl =
                if (success && LocationCompat.hasMslAltitude(converted))
                    LocationCompat.getMslAltitudeMeters(converted).takeIf { it.isFinite() }
                else null
            val mslAccuracy =
                if (msl != null && LocationCompat.hasMslAltitudeAccuracy(converted))
                    LocationCompat.getMslAltitudeAccuracyMeters(converted).toDouble().takeIf { it.isFinite() }
                else null
            return AltitudeEvidence("wgs84Ellipsoid", original, accuracy, msl, mslAccuracy)
        }
    }
}
