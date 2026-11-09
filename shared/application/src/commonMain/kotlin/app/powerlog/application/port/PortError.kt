package app.powerlog.application.port

import app.powerlog.domain.core.failure.PlatformCode

public data class PortError(public val kind: PortErrorKind, public val platform: PlatformCode? = null)

public enum class PortErrorKind {
    denied,
    poweredOff,
    unsupported,
    notReady,
    unknownBike,
    connectionFailed,
    linkLost,
    writeFailed,
    searchFailed,
    cancelled,
    noUserActivation,
    uiUnavailable,
    locked,
    busy,
    rateLimited,
    invalid,
    notAllowedInBackground,
    notReachable,
    launchFailed,
    unreadable,
    failed,
}
