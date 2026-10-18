package app.powerlog.bridge

import app.powerlog.core.presentation.SampleRates
import app.powerlog.core.telemetry.StorageColumns
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.floor

internal val metricBits = StorageColumns.androidMetricBits

internal fun availabilityBits(metrics: Collection<String>): Long =
    metrics.fold(0L) { bits, metric ->
        bits or (1L shl metricBits.getValue(metric))
    }

internal fun canonicalInt64(value: Any): Long {
    require(value is String && Regex("0|-?[1-9][0-9]*").matches(value))
    return value.toLong()
}

internal fun timeUs(timestamp: String): Long {
    require(timestamp.length <= 40)
    val parts =
        Regex(
                "([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})T([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})(?:\\.([0-9]{1,9}))?(?:Z|\\+00:00)"
            )
            .matchEntire(timestamp)
            ?.groupValues ?: throw IllegalArgumentException("Invalid UTC timestamp.")
    val year = parts[1].toInt()
    val month = parts[2].toInt()
    val day = parts[3].toInt()
    val hour = parts[4].toInt()
    val minute = parts[5].toInt()
    val second = parts[6].toInt()
    require(month in 1..12 && day in 1..31 && hour in 0..24 && minute in 0..59 && second in 0..59)
    val whole =
        LocalDate.of(year, month, 1).plusDays(day - 1L).atStartOfDay().toEpochSecond(ZoneOffset.UTC) +
            hour * 3600L +
            minute * 60L +
            second
    require(whole in 946684800L until 4102444800L)
    val fractionUs = parts[7].padEnd(9, '0').toLong() / 1000
    return Math.addExact(Math.multiplyExact(whole, 1_000_000), fractionUs)
}

internal fun elapsedUs(seconds: Double): Long {
    val scaled = floor(seconds * 1_000_000)
    require(scaled.isFinite() && scaled >= Long.MIN_VALUE.toDouble() && scaled < Long.MAX_VALUE.toDouble())
    return scaled.toLong()
}

internal data class OriginalIdentity(val eventId: String, val producer: String, val sequence: Long) {
    init {
        require(eventId == UUID.fromString(eventId).toString())
        require(producer in setOf("controller", "phone", "watch"))
        require(sequence > 0)
    }

    fun payload(): Payload =
        mapOf("eventId" to eventId, "producer" to producer, "producerSequence" to sequence.toString())
}

internal data class OriginalEvidence(
    val originalElapsedSeconds: Double? = null,
    val acquisitionMonotonic: Double? = null,
    val clockEpoch: String? = null,
    val interruptionIndex: Int = 0,
) {
    init {
        require(originalElapsedSeconds == null || originalElapsedSeconds.isFinite())
        require(acquisitionMonotonic == null || acquisitionMonotonic.isFinite())
        require(interruptionIndex >= 0)
    }

    fun payload(): Payload =
        mapOf(
            "originalElapsedSeconds" to originalElapsedSeconds,
            "acquisitionMonotonic" to acquisitionMonotonic,
            "clockEpoch" to clockEpoch,
            "interruptionIndex" to interruptionIndex,
        )
}

internal data class RideRecord(
    val id: String,
    val startedAt: String,
    val indoor: Boolean,
    val saveToHealth: Boolean,
    val recordGPS: Boolean,
    val sampleHz: Int,
    val example: Boolean,
    val ownerEndpointId: String,
    val interrupted: Boolean = false,
    val endedAt: String? = null,
    val healthComponents: List<app.powerlog.core.health.ComponentResult> = emptyList(),
    val distanceSource: String = if (recordGPS && !indoor) "gps:phone" else "controller",
    val batteryConsumptionSource: String = "controller",
) {
    init {
        require(id.removePrefix("live-") == UUID.fromString(id.removePrefix("live-")).toString())
        require(ownerEndpointId == UUID.fromString(ownerEndpointId).toString())
        timeUs(startedAt)
        endedAt?.let(::timeUs)
        require(distanceSource in setOf("gps:phone", "controller"))
        require(batteryConsumptionSource in setOf("controller", "calculated"))
        require(SampleRates.accepts(sampleHz.toDouble())) { "sampleHz must be 2, 4, or 8 Hz." }
    }

    fun payload(): Payload =
        mapOf(
                "schemaVersion" to 1,
                "id" to id,
                "startedAt" to startedAt,
                "indoor" to indoor,
                "watchEnabled" to false,
                "saveToHealth" to saveToHealth,
                "healthProvider" to "healthConnect",
                "recordGPS" to recordGPS,
                "storage" to "native",
                "interrupted" to interrupted,
                "healthComponents" to
                    healthComponents.map {
                        org.json.JSONObject(app.powerlog.core.health.HealthCodec.encodeComponentResult(it)).map()
                    },
                "sampleHz" to sampleHz,
                "distanceSource" to distanceSource,
                "batteryConsumptionSource" to batteryConsumptionSource,
                "healthWriter" to "phone",
                "example" to example,
                "endedAt" to endedAt,
                "recordingOwner" to "phone",
                "ownerDevice" to "androidPhone",
                "healthPublisher" to "phone",
                "ownerEndpointId" to ownerEndpointId,
                "publisherEndpointId" to ownerEndpointId,
            )
            .filterValues { it != null }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun read(data: Payload): RideRecord {
            require(
                listOf("healthKitState", "healthExport", "healthReason", "componentResults", "exportEligible").none {
                    it in data
                }
            ) {
                "Stored derived Health state is incompatible."
            }
            val rate = data["sampleHz"] as? Number
            require(rate != null && SampleRates.accepts(rate.toDouble())) {
                "The stored sampleHz is missing or invalid."
            }
            fun flag(key: String): Boolean = data[key] as? Boolean ?: error("Missing stored $key.")
            val fixed =
                mapOf(
                    "watchEnabled" to false,
                    "recordingOwner" to "phone",
                    "ownerDevice" to "androidPhone",
                    "healthPublisher" to "phone",
                    "healthWriter" to "phone",
                    "publisherEndpointId" to data["ownerEndpointId"],
                    "healthProvider" to "healthConnect",
                    "storage" to "native",
                )
            require(fixed.all { (key, value) -> data[key] == value }) { "Invalid stored owner roles or ride options." }
            return RideRecord(
                data.str("id"),
                data.str("startedAt"),
                flag("indoor"),
                flag("saveToHealth"),
                flag("recordGPS"),
                rate.toInt(),
                flag("example"),
                data.str("ownerEndpointId"),
                flag("interrupted"),
                data["endedAt"] as? String,
                (data["healthComponents"] as? List<*>)?.map {
                    app.powerlog.core.health.HealthCodec.decodeComponentResult(json(it as Payload).toString())
                } ?: error("Missing stored Health components."),
                data.str("distanceSource"),
                data.str("batteryConsumptionSource"),
            )
        }
    }
}
