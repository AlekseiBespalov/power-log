package app.powerlog.domain.core.failure

import kotlinx.serialization.Serializable

@Serializable
public enum class FailureCode(public val retryable: Boolean) {
    bikeRequired(false),
    rideActive(false),
    actionPending(false),
    actionUnavailable(false),
    rideChanged(false),
    startUnconfirmed(true),
    startInterrupted(false),
    startCancelled(false),
    startExpired(false),
    ownerForeign(false),
    settingsUnreadable(false),
    rideInProgress(false),
    exportInProgress(false),
    locationDenied(false),
    gpsRequired(false),
    healthUnavailable(false),
    healthDenied(false),
    healthLocked(false),
    watchNotInstalled(false),
    watchLaunchFailed(true),
    watchBusy(false),
    watchStartUnconfirmed(true),
    watchVersionMismatch(false),
    watchMessageInvalid(false),
    bluetoothDenied(false),
    bluetoothOff(false),
    bluetoothNotReady(true),
    bluetoothUnsupported(false),
    bluetoothUnavailable(false),
    bluetoothSearchFailed(true),
    bikeNotFound(true),
    bikeUnavailable(true),
    bikeReconnecting(false),
    controllerNoReply(true),
    controllerUnsupported(false),
    firmwareUnsupported(false),
    differentBike(false),
    disconnectFirst(false),
    storageBusy(true),
    storageFull(false),
    storageFailed(true),
    storageRefused(false),
    readFailed(true),
    chartCapacity(false),
    rideDeleted(false),
    exportNotReady(false),
    exportChanged(true),
    exportLimit(false),
    exportNoRecords(false),
    exportSinkFailed(true),
    exportLeftScreen(true),
    importUnreadable(false),
    settingsSaveFailed(true),
    uiUnavailable(true),
    syncBeepFailed(false),
    apiMismatch(false),
    unknown(true),
}

@Serializable
public enum class RefusalDetail {
    unreadable,
    unavailable,
}

@Serializable public data class Availability(public val enabled: Boolean, public val reason: FailureCode?)

public data class PlatformCode(public val domain: String, public val code: String)
