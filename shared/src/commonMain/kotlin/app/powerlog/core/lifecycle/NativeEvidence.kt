package app.powerlog.core.lifecycle

import app.powerlog.core.HealthIdentity
import app.powerlog.core.presentation.RideAction
import app.powerlog.core.protocol.OwnerTiming
import app.powerlog.core.protocol.ProtocolTimestamp
import kotlin.math.abs

data class LifecycleNativeEventTiming(val timing: OwnerTiming, val uncertainEpoch: Boolean)

object LifecycleNativeEvidence {
    @Throws(IllegalArgumentException::class)
    fun identity(
        key: LifecycleKey,
        action: RideAction,
        timestamp: ProtocolTimestamp,
        nativeTimerSeconds: Double?,
        originalId: String?,
    ): String {
        require(originalId == null || HealthIdentity.validRide(originalId))
        require(nativeTimerSeconds == null || nativeTimerSeconds.isFinite() && nativeTimerSeconds >= 0)
        if (originalId != null) return originalId
        val input =
            "native-event:${key.rideId}:${action.name}:${timestamp.original}" +
                (nativeTimerSeconds?.let { ":${it.toBits()}" } ?: "")
        val hex = app.powerlog.core.Sha256.hash(input.encodeToByteArray()).take(32).toCharArray()
        hex[12] = '5'
        hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
        val text = hex.concatToString()
        return "${text.take(8)}-${text.substring(8,12)}-${text.substring(12,16)}-${text.substring(16,20)}-${text.substring(20)}"
    }

    @Throws(IllegalArgumentException::class)
    fun eventTiming(
        reference: OwnerTiming,
        observation: OwnerTiming,
        timestamp: ProtocolTimestamp,
        nativeTimerSeconds: Double,
        preceding: OwnerTiming?,
    ): LifecycleNativeEventTiming {
        validateTiming(reference)
        validateTiming(observation)
        require(nativeTimerSeconds.isFinite() && nativeTimerSeconds >= (preceding?.timerSeconds ?: 0.0))
        val timer = minOf(nativeTimerSeconds, observation.timerSeconds)
        val lower = maxOf(preceding?.elapsedSeconds ?: 0.0, timer)
        require(timer >= (preceding?.timerSeconds ?: 0.0) && lower <= observation.elapsedSeconds)
        fun delta(from: ProtocolTimestamp, to: ProtocolTimestamp) =
            (to.comparisonUs - from.comparisonUs).toDouble() / 1_000_000
        val current = observation.elapsedSeconds + delta(observation.timestamp, timestamp)
        val discrepancy =
            delta(reference.timestamp, observation.timestamp) - (observation.elapsedSeconds - reference.elapsedSeconds)
        val retained = reference.elapsedSeconds + delta(reference.timestamp, timestamp)
        if (current in lower..observation.elapsedSeconds)
            return LifecycleNativeEventTiming(
                OwnerTiming(timestamp, current, timer),
                abs(discrepancy) > 1.0 && retained in lower..observation.elapsedSeconds,
            )
        if (abs(discrepancy) <= 1.0)
            return LifecycleNativeEventTiming(
                OwnerTiming(timestamp, current.coerceIn(lower, observation.elapsedSeconds), timer),
                false,
            )
        val elapsed =
            if (retained in lower..observation.elapsedSeconds) retained
            else
                ((preceding?.elapsedSeconds ?: reference.elapsedSeconds) + timer -
                        (preceding?.timerSeconds ?: reference.timerSeconds))
                    .coerceIn(lower, observation.elapsedSeconds)
        return LifecycleNativeEventTiming(OwnerTiming(timestamp, elapsed, timer), true)
    }

    fun applyUncertainty(
        state: LifecycleState,
        transition: LifecycleTransition,
        uncertainEpoch: Boolean,
    ): LifecycleTransition {
        if (!uncertainEpoch || transition.rejection != null) return transition
        val mutation = transition.mutation ?: return transition
        val ride = mutation.state.ride ?: return transition
        if (ride.key != state.ride?.key || ride.uncertainEpoch) return transition
        return LifecycleTransition(
            state,
            mutation.copy(state = mutation.state.copy(ride = ride.copy(uncertainEpoch = true))),
            transition.outcome,
            effects = transition.afterCommit(true).effects,
        )
    }
}
