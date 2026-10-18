package app.powerlog.core.telemetry

import app.powerlog.core.HealthIdentity
import app.powerlog.core.protocol.ProtocolTimestamp
import app.powerlog.core.protocol.Timestamps
import kotlin.math.abs

data class TelemetryCaptureEvidence
@Throws(TelemetryException::class)
constructor(
    val rideId: String,
    val observationId: String,
    val captureSessionId: String,
    val clockEpoch: String,
    val connectionEpoch: String,
    val sequence: Long,
    val timestamp: ProtocolTimestamp,
    val acquisitionMonotonic: Double,
    val sourceElapsedSeconds: Double,
    val telemetry: TelemetryReading,
    val clockDiscontinuitySeconds: Double? = null,
) {
    init {
        if (
            !listOf(rideId, observationId, captureSessionId, clockEpoch, connectionEpoch)
                .all(HealthIdentity::validRide) ||
                sequence <= 0 ||
                !acquisitionMonotonic.isFinite() ||
                acquisitionMonotonic < 0 ||
                !sourceElapsedSeconds.isFinite() ||
                sourceElapsedSeconds < 0 ||
                sourceElapsedSeconds > acquisitionMonotonic ||
                (clockDiscontinuitySeconds != null && !clockDiscontinuitySeconds.isFinite()) ||
                telemetry.identity == null ||
                !validTimestamp(timestamp)
        )
            throw TelemetryException(TelemetryRejection.invalidEvidence)
    }
}

private fun validTimestamp(timestamp: ProtocolTimestamp): Boolean =
    try {
        Timestamps.parse(timestamp.original) == timestamp
    } catch (_: IllegalArgumentException) {
        false
    }

data class TelemetryCaptureState
@Throws(TelemetryException::class)
constructor(
    val rideId: String,
    val latest: TelemetryCaptureEvidence? = null,
) {
    init {
        if (!HealthIdentity.validRide(rideId) || (latest != null && latest.rideId != rideId))
            throw TelemetryException(TelemetryRejection.invalidEvidence)
    }

    @Throws(TelemetryException::class)
    fun adopt(rideId: String): TelemetryCaptureState =
        if (rideId == this.rideId) this else TelemetryCaptureState(rideId)

    fun age(now: Double): Double? =
        if (now.isFinite()) latest?.let { maxOf(0.0, now - it.acquisitionMonotonic) } else null
}

enum class TelemetryCaptureOutcome {
    admitted,
    replay,
    older,
    conflict,
    wrongRide,
    wrongEpoch,
}

data class TelemetryCaptureDecision(val outcome: TelemetryCaptureOutcome, val next: TelemetryCaptureState) {
    val commitOriginal: Boolean
        get() = outcome == TelemetryCaptureOutcome.admitted
}

object TelemetryCaptureAdmission {
    @Throws(IllegalArgumentException::class)
    fun discontinuity(
        previousWallClockSeconds: Double,
        wallClockSeconds: Double,
        previousAcquisitionMonotonic: Double,
        acquisitionMonotonic: Double,
    ): Double? {
        require(
            previousWallClockSeconds.isFinite() &&
                wallClockSeconds.isFinite() &&
                previousAcquisitionMonotonic.isFinite() &&
                acquisitionMonotonic.isFinite() &&
                previousAcquisitionMonotonic >= 0 &&
                acquisitionMonotonic >= previousAcquisitionMonotonic
        )
        val difference =
            (wallClockSeconds - previousWallClockSeconds) - (acquisitionMonotonic - previousAcquisitionMonotonic)
        require(difference.isFinite())
        return difference.takeIf { abs(it) > 0.25 }
    }

    fun admit(
        state: TelemetryCaptureState,
        evidence: TelemetryCaptureEvidence,
        storedOriginal: TelemetryCaptureEvidence?,
    ): TelemetryCaptureDecision {
        fun result(outcome: TelemetryCaptureOutcome) = TelemetryCaptureDecision(outcome, state)
        if (evidence.rideId != state.rideId) return result(TelemetryCaptureOutcome.wrongRide)
        val previous = state.latest
        val existing = storedOriginal ?: previous?.takeIf { it.observationId == evidence.observationId }
        if (existing != null) {
            if (existing.observationId != evidence.observationId || existing != evidence)
                return result(TelemetryCaptureOutcome.conflict)
            return result(TelemetryCaptureOutcome.replay)
        }
        if (previous != null) {
            if (evidence.clockEpoch != previous.clockEpoch) return result(TelemetryCaptureOutcome.wrongEpoch)
            if (evidence.captureSessionId == previous.captureSessionId && evidence.sequence <= previous.sequence)
                return result(TelemetryCaptureOutcome.older)
            if (evidence.acquisitionMonotonic < previous.acquisitionMonotonic)
                return result(TelemetryCaptureOutcome.older)
        }
        return TelemetryCaptureDecision(TelemetryCaptureOutcome.admitted, state.copy(latest = evidence))
    }
}
