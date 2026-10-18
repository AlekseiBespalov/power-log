package app.powerlog.core.protocol

object StartWindow {
    const val deadlineUs = 45_000_000L
    const val clockAllowanceUs = 15_000_000L
    const val acceptUs = deadlineUs + clockAllowanceUs

    fun expired(requestedAt: ProtocolTimestamp, now: ProtocolTimestamp): Boolean =
        now.comparisonUs - requestedAt.comparisonUs > acceptUs
}
