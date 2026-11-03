package app.powerlog.core.presentation

import app.powerlog.core.telemetry.ControllerRequest

private const val somethingWentWrong = "Something went wrong."
private const val tryAgainKept = "Try again. Your recorded data is kept."

enum class RideFailure(val message: String, val nextStep: String, val androidNextStep: String = nextStep) {
    watchLaunchFailed(
        "Power Log couldn't open on your Apple Watch.",
        "Unlock your Apple Watch and keep it near your iPhone, then start again.",
    ),
    watchNotInstalled(
        "Power Log isn't installed on your Apple Watch.",
        "Install it from the Watch app on your iPhone, or turn off Apple Watch in Settings.",
    ),
    watchStartUnconfirmed(
        "Your Apple Watch didn't confirm the start.",
        "Open Power Log on your Apple Watch, keep it unlocked and nearby, then start again.",
    ),
    watchBusy(
        "Your Apple Watch is still busy with another ride.",
        "Finish or discard it on your Apple Watch, then start again.",
    ),
    startUnconfirmed("Power Log couldn't confirm the start.", "Start again."),
    startCancelled("This start was cancelled before the ride began.", "Start again."),
    startExpired("This start arrived too late to begin the ride.", "Start again."),
    startInterrupted("The ride didn't start.", "Start again."),
    bikeRequired("Connect your bike to start.", ""),
    ownerUnresolved(
        "Power Log is still checking your last ride.",
        "Open Power Log on the device that recorded it, then start again.",
    ),
    rideFinishing("Power Log is still finishing your last ride.", "Wait a moment, then start again."),
    rideActive("A ride is already in progress.", "Finish or discard it before starting another."),
    actionPending("Another ride action is still running.", "Wait for it to finish."),
    discardPending("Discard hasn't finished yet.", "Retry Discard."),
    rideChanged("This ride changed.", "Check the current ride, then try again."),
    ownerForeign("This ride is being recorded somewhere else.", "Finish it where it is recording."),
    finishConfirmationRequired("Finish needs your confirmation.", "Confirm Finish to end the ride."),
    actionUnavailable("This action isn't available right now.", "Check the current ride, then try again."),
    finishUnconfirmed(
        "Your Apple Watch hasn't confirmed Finish yet.",
        "Keep it nearby. You can also finish on the Watch; your ride is kept.",
    ),
    ownerUnconfirmed(
        "Power Log couldn't confirm the recording.",
        "Open Power Log on the device that recorded the ride, then try again.",
    ),
    recordingInterrupted("Recording was interrupted.", "Finish the retained recording or discard it."),
    sessionUnavailable(
        "The recording session ended unexpectedly.",
        "Finish or discard the ride to keep what was recorded.",
    ),
    syncPending(
        "This ride is still syncing between your watch and phone.",
        "Open Power Log on both, and keep them close together.",
    ),
    watchDataRejected("Watch data couldn't be synced.", "Finish without Watch data to keep the rest."),
    needsAttention("This ride needs attention.", "Tap Retry. Your recorded data is kept."),
    healthDenied("Power Log can't save to Health.", "Allow it in the Health app, or turn off Save to Health."),
    healthLocked("Health data is unavailable while this device is locked.", "Unlock it, then try again."),
    bluetoothDenied(
        "Power Log can't use Bluetooth.",
        "Allow Bluetooth for Power Log in Settings.",
        "Allow Nearby devices for Power Log in Settings.",
    ),
    locationDenied("Power Log can't use your location.", "Allow location in Settings, or turn off GPS for this ride."),
    bluetoothOff("Bluetooth is off.", "Turn on Bluetooth, then search again."),
    bluetoothNotReady("Bluetooth isn't ready yet.", "Try again in a moment."),
    bluetoothUnsupported("This device doesn't support Bluetooth Low Energy.", ""),
    bluetoothUnavailable("Bluetooth isn't available in this build.", ""),
    bikeReconnecting("Connection to your bike was lost.", "Reconnecting…"),
    bikeUnavailable(
        "Power Log couldn't connect to your bike.",
        "Close other apps connected to the bike, turn its display off and on, then connect again.",
    ),
    bikeNotFound(
        "No CYC X6 or X12 controller found.",
        "Turn on your bike's display, keep your phone nearby, then search again.",
    ),
    bluetoothSearchFailed("Bluetooth search failed.", "Try again."),
    controllerUnsupported("This isn't a supported controller.", "Power Log works with CYC X6 and X12."),
    controllerNoReply(
        "This device didn't reply.",
        "If it's a CYC X6 or X12 controller, turn on the bike's display and connect again.",
    ),
    firmwareUnsupported(
        "This controller's firmware isn't supported yet.",
        "Share diagnostics from Settings so support can be added.",
    ),
    storageBusy("Power Log is busy saving your ride.", "Try again in a moment."),
    storageFull("This device is out of storage.", "Free up space, then try again."),
    storageFailed("Power Log couldn't save this change.", tryAgainKept),
    rideDeleted("This ride was deleted.", "Choose another ride in History."),
    ownerEvidenceRejected(somethingWentWrong, tryAgainKept),
    captureRejected(somethingWentWrong, tryAgainKept),
    cutoffBeforeStart(somethingWentWrong, tryAgainKept),
    unknown(somethingWentWrong, tryAgainKept);

    val text: String
        get() = text(nextStep)

    val androidText: String
        get() = text(androidNextStep)

    private fun text(step: String): String = listOf(message, step).filter { it.isNotEmpty() }.joinToString(" ")
}

object Failures {
    fun present(code: String?): RideFailure = RideFailure.entries.firstOrNull { it.name == code } ?: RideFailure.unknown

    fun controllerRejection(request: ControllerRequest): RideFailure =
        if (request == ControllerRequest.identity) RideFailure.controllerUnsupported
        else RideFailure.firmwareUnsupported
}
