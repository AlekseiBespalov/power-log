package app.powerlog.kit

import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason

internal object DiagnosticsTexts {
    fun traceStatus(state: TraceState, events: Int, stopReason: TraceStopReason?): String {
        val status =
            when {
                state == TraceState.capturing -> "Capturing"
                events > 0 -> "Capture stopped"
                else -> "No capture"
            }
        val limit =
            when (stopReason) {
                TraceStopReason.timeLimit -> " · Time limit reached"
                TraceStopReason.sizeLimit -> " · Size limit reached"
                TraceStopReason.manual,
                null -> ""
            }
        return status + (if (events > 0) " · $events events" else "") + limit
    }
}
