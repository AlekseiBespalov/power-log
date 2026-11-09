package app.powerlog.domain.diagnostics

import kotlinx.serialization.Serializable

@Serializable
public enum class TraceState {
    idle,
    capturing,
    stopped,
}

@Serializable
public enum class TraceStopReason {
    manual,
    timeLimit,
    sizeLimit,
}
