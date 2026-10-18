package app.powerlog.core

import app.powerlog.core.telemetry.TelemetryTiming

object StreamStatus {
    const val controllerStaleSeconds = 6.0
    const val heartStaleSeconds = 15.0
    const val gpsStaleSeconds = 10.0

    private val vocabulary =
        setOf(
            "off",
            "waiting",
            "receiving",
            "backfilled",
            "weak",
            "stale",
            "paused",
            "denied",
            "restricted",
            "notDetermined",
            "unavailable",
        )

    private val staleStatuses = setOf("waiting", "receiving", "weak", "stale")
    private val activePhases = setOf("preparing", "running", "paused", "interrupted", "finishing")
    private val availablePhases = setOf("running", "paused")
    private val connectedDisplays = setOf("live", "held")

    fun normalized(status: String?): String = status?.takeIf { it in vocabulary } ?: "waiting"

    fun location(accuracy: Double?): String =
        when {
            accuracy == null || !accuracy.isFinite() || accuracy < 0 -> "unavailable"
            accuracy > 50 -> "weak"
            else -> "receiving"
        }

    fun resolve(requested: Boolean, phase: String, age: Double?, staleAfter: Double, status: String?): String {
        if (!requested) return "off"
        if (phase == "paused") return "paused"
        val value = if (status != null) normalized(status) else if (age == null) "waiting" else "receiving"
        if (age == null && (value == "receiving" || value == "weak")) return "waiting"
        if (value in staleStatuses && age != null && age >= staleAfter) return "stale"
        return value
    }

    fun controller(requested: Boolean, phase: String, age: Double?): String =
        resolve(requested && active(phase), phase, age, controllerStaleSeconds, null)

    fun active(phase: String): Boolean = phase in activePhases

    fun gps(requested: Boolean, phase: String, age: Double?, accuracy: Double?, provider: String?): String =
        resolve(requested, phase, age, gpsStaleSeconds, provider ?: if (age == null) "waiting" else location(accuracy))

    fun heart(requested: Boolean, phase: String, age: Double?, provider: String? = null): String =
        resolve(requested, phase, age, heartStaleSeconds, provider)

    fun available(age: Double?, cutoff: Double, phase: String): Boolean =
        phase in availablePhases && age != null && age.isFinite() && age >= 0 && age < cutoff

    fun controllerDisplay(age: Double?, phase: String): String =
        when {
            !available(age, controllerStaleSeconds, phase) -> "unavailable"
            age!! <= TelemetryTiming.telemetryTimeoutSeconds -> "live"
            else -> "held"
        }

    fun controllerConnection(age: Double?, phase: String): String =
        if (phase == "paused") "paused"
        else if (available(age, controllerStaleSeconds, phase)) "normal" else "reconnectError"

    fun connectionDisplay(connection: String, age: Double?): String {
        if (connection != "connected" && connection != "reconnecting") return "unavailable"
        val display = controllerDisplay(age, "running")
        return if (connection == "reconnecting" && display == "live") "held" else display
    }

    fun connectionDeadline(connection: String, acquiredAt: Double?, now: Double): Double? {
        if (acquiredAt == null || connectionDisplay(connection, now - acquiredAt) == "unavailable") return null
        return acquiredAt +
            if (connection == "connected" && now - acquiredAt <= TelemetryTiming.telemetryTimeoutSeconds)
                TelemetryTiming.telemetryTimeoutSeconds + 0.001
            else controllerStaleSeconds
    }

    fun connectionLabel(connection: String, display: String): String =
        when {
            display in connectedDisplays -> "Connected"
            connection == "reconnecting" -> "Reconnecting…"
            connection == "connected" -> "Waiting for data"
            connection == "connecting" -> "Connecting…"
            connection == "scanning" -> "Searching…"
            else -> "Not connected"
        }
}
