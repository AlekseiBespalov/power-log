package app.powerlog.core.protocol

enum class Role {
    phone,
    watch,
}

data class OwnerTiming(val timestamp: ProtocolTimestamp, val elapsedSeconds: Double, val timerSeconds: Double)

data class OwnerSnapshot(
    val owner: Role,
    val ownerRevision: Long,
    val effectiveAt: ProtocolTimestamp,
    val phase: String,
    val healthOutcome: String,
    val healthWorkoutID: String?,
    val stopCutoff: ProtocolTimestamp?,
    val timing: OwnerTiming?,
    val healthReason: String?,
)

class Event
internal constructor(
    val eventId: String,
    val workoutId: String,
    val kind: String,
    val source: String,
    val timestamp: ProtocolTimestamp,
    val elapsedSeconds: Double?,
    val payloadJson: String,
    internal val json: Obj,
) {
    val canonicalUtf8: String
        get() = JsonCodec.encode(json)

    val canonicalBytes: ByteArray
        get() = canonicalUtf8.encodeToByteArray()
}
