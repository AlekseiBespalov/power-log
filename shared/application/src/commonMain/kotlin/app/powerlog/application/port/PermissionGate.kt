package app.powerlog.application.port

import kotlinx.serialization.Serializable

public interface PermissionGate {
    public fun status(reply: Reply<PermissionStates>)

    public fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>)
}

public enum class PermissionKind {
    bluetooth,
    location,
    notifications,
}

@Serializable
public enum class PermissionState {
    granted,
    denied,
    notDetermined,
    restricted,
    unavailable,
    hidden,
}

public data class PermissionStates(
    public val bluetooth: PermissionState,
    public val location: PermissionState,
    public val preciseLocation: Boolean?,
    public val locationServicesOn: Boolean?,
    public val notifications: PermissionState?,
)
