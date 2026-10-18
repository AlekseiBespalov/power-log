package app.powerlog.bridge

import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.*

internal fun TelemetryReading.values(): Map<String, Double> = metrics.mapKeys { it.key.name }

internal fun TelemetryCaptureEvidence.detail(): Payload =
    mapOf(
        "captureSessionId" to captureSessionId,
        "clockEpoch" to clockEpoch,
        "observationSequence" to sequence.toString(),
        "sourceElapsedSeconds" to sourceElapsedSeconds,
        "clockDiscontinuitySeconds" to clockDiscontinuitySeconds,
    )

internal fun Observation.telemetryEvidence(ride: String): TelemetryCaptureEvidence {
    require(kind == "telemetry")
    val profile = identity.split('|')
    require(profile.size == 3)
    val restored = TelemetryRestoration.restore(profile[0], profile[1], profile[2], values)
    val sequenceText = requireNotNull(evidence["observationSequence"] as? String)
    val sequence = sequenceText.toLong()
    require(sequence.toString() == sequenceText)
    return TelemetryCaptureEvidence(
        ride.removePrefix("live-"),
        requireNotNull(original).eventId,
        evidence.str("captureSessionId"),
        evidence.str("clockEpoch"),
        epoch,
        sequence,
        Timestamps.parse(timestamp),
        requireNotNull(evidence["acquisitionMonotonic"] as? Number).toDouble(),
        requireNotNull(evidence["sourceElapsedSeconds"] as? Number).toDouble(),
        requireNotNull(restored.reading),
        evidence["clockDiscontinuitySeconds"]?.let { requireNotNull(it as? Number).toDouble() },
    )
}

internal class TelemetryCapture(private val store: RideStore) {
    private val committed = mutableMapOf<String, TelemetryCaptureState>()

    fun prepare(
        evidence: TelemetryCaptureEvidence,
        retained: TelemetryCaptureEvidence? = null,
        storageRide: String = evidence.rideId,
    ): TelemetryCaptureDecision {
        val state =
            retained?.let { TelemetryCaptureState(evidence.rideId, it) }
                ?: committed[evidence.rideId]
                ?: TelemetryCaptureState(evidence.rideId)
        val stored = store.telemetryOriginal(storageRide, evidence.observationId)
        return TelemetryCaptureAdmission.admit(state, evidence, stored)
    }

    fun committed(evidence: TelemetryCaptureEvidence) {
        committed[evidence.rideId] = TelemetryCaptureState(evidence.rideId, evidence)
    }

    fun reset() = committed.clear()
}
