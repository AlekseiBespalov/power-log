package app.powerlog.bridge

internal data class Origin(
    val provider: String?,
    val dataOrigin: String?,
    val providerObservationId: String?,
    val originEventId: String,
    val producer: String,
    val sequence: Long,
    val originRevision: String? = null,
) {
    init {
        OriginalIdentity(originEventId, producer, sequence)
        require(provider == null || provider in setOf("appleHealth", "healthConnect"))
    }

    fun payload(): Payload =
        mapOf(
            "provider" to provider,
            "dataOrigin" to dataOrigin,
            "providerObservationId" to providerObservationId,
            "originEventId" to originEventId,
            "producer" to producer,
            "sequence" to sequence.toString(),
            "originRevision" to originRevision,
        )
}

internal sealed interface Association {
    fun payload(): Payload

    data class Workout(val identity: String) : Association {
        override fun payload(): Payload = mapOf("kind" to "workout", "identity" to identity)
    }

    data class RideWindow(val startUs: Long, val endUs: Long) : Association {
        init {
            require(endUs >= startUs)
        }

        override fun payload(): Payload =
            mapOf(
                "kind" to "rideWindow",
                "startUs" to startUs.toString(),
                "endUs" to endUs.toString(),
            )
    }
}

internal data class HealthOriginal(
    val origin: Origin,
    val measure: String,
    val unit: String,
    val representation: String,
    val originalStartTimestamp: String,
    val originalEndTimestamp: String,
    val collectionUs: Long,
    val endUs: Long,
    val value: Double?,
    val association: Association,
    val memberIndex: Long? = null,
    val logicalKey: String? = null,
    val device: String? = null,
    val lastModifiedTimestamp: String? = null,
    val targetEventId: String? = null,
    val parentMetadata: Payload? = null,
    val collectionMapped: Boolean = true,
    val collectionSegment: Int = 0,
) {
    init {
        require(origin.provider == "healthConnect" && !origin.dataOrigin.isNullOrBlank())
        require(
            measure in
                setOf(
                    "heartRate",
                    "activeEnergy",
                    "respiratoryRate",
                    "oxygenSaturation",
                    "hrvRMSSD",
                    "riderPower",
                    "cyclingCadence",
                    "cyclingSpeed",
                    "cyclingDistance",
                    "association",
                    "tombstone",
                )
        )
        require(
            representation in
                setOf(
                    "rawQuantity",
                    "rawSeries",
                    "intervalRecord",
                    "liveCumulative",
                    "finalWorkoutTotal",
                    "workoutAssociation",
                    "healthTombstone",
                )
        )
        when (measure) {
            "heartRate" -> require(unit == "bpm") { "Heart rate requires its declared bpm unit." }
            "activeEnergy" -> require(unit == "kcal") { "Energy requires its declared kcal unit." }
        }
        require(timeUs(originalEndTimestamp) >= timeUs(originalStartTimestamp))
        require(endUs >= collectionUs && (memberIndex == null || memberIndex >= 0) && collectionSegment >= 0)
        require(value == null || value.isFinite())
        lastModifiedTimestamp?.let(::timeUs)
        targetEventId?.let { require(it == java.util.UUID.fromString(it).toString()) }
        require(measure != "tombstone" || representation == "healthTombstone" && value == null && targetEventId != null)
    }

    fun payload(): Payload =
        mapOf(
            "origin" to origin.payload(),
            "measure" to measure,
            "unit" to unit,
            "representation" to representation,
            "originalStartTimestamp" to originalStartTimestamp,
            "originalEndTimestamp" to originalEndTimestamp,
            "comparisonUs" to timeUs(originalStartTimestamp).toString(),
            "collectionUs" to collectionUs.toString(),
            "endUs" to endUs.toString(),
            "value" to value,
            "valueBits" to value?.toRawBits()?.toULong()?.toString(16)?.padStart(16, '0'),
            "association" to association.payload(),
            "memberIndex" to memberIndex?.toString(),
            "targetEventId" to targetEventId,
            "logicalKey" to logicalKey,
            "device" to device,
            "lastModifiedTimestamp" to lastModifiedTimestamp,
            "parentMetadata" to parentMetadata,
        ) + if (collectionMapped) emptyMap() else mapOf("collectionMapped" to false)

    fun readings(): Map<String, Double> =
        if (value == null || !collectionMapped) emptyMap()
        else
            when (measure) {
                "heartRate" -> mapOf("heartRateBpm" to value)
                "activeEnergy" -> mapOf("activeEnergyKcal" to value)
                else -> emptyMap()
            }
}

internal data class HealthChange(
    val targetEventId: String,
    val originalEventId: String,
    val deleted: Boolean,
) {
    init {
        require(targetEventId == java.util.UUID.fromString(targetEventId).toString())
        require(
            originalEventId == java.util.UUID.fromString(originalEventId).toString() && originalEventId != targetEventId
        )
    }
}

internal data class HealthQueryProgress(
    val provider: String,
    val measure: String,
    val scope: String,
    val cursor: String?,
    val memberCursor: Long = 0,
) {
    init {
        require(provider == "healthConnect" && scope.isNotEmpty() && memberCursor >= 0)
    }

    fun payload(): Payload = mapOf("cursor" to cursor, "memberCursor" to memberCursor.toString())
}
