package app.powerlog.kit

import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail

internal object FailureTexts {
    private const val START_AGAIN = "Start again."
    private const val CHECK_RIDE = "Check the current ride, then try again."
    private const val TRY_AGAIN_KEPT = "Try again. Your recorded data is kept."
    private const val ZIP_AVAILABLE = "The ride-data ZIP is still available."
    private const val SAME_BUILD = "Install the same Power Log build on your iPhone and Apple Watch"
    private const val NEARBY_DEVICES_SDK = 31

    private val base: Map<FailureCode, FailureText> =
        mapOf(
            FailureCode.bikeRequired to FailureText("Connect your bike to start.", ""),
            FailureCode.rideActive to
                FailureText("A ride is already in progress.", "Finish or discard it before starting another."),
            FailureCode.actionPending to FailureText("Another ride action is still running.", "Wait for it to finish."),
            FailureCode.actionUnavailable to FailureText("This action isn't available right now.", CHECK_RIDE),
            FailureCode.rideChanged to FailureText("This ride changed.", CHECK_RIDE),
            FailureCode.startUnconfirmed to FailureText("Power Log couldn't confirm the start.", START_AGAIN),
            FailureCode.startInterrupted to FailureText("The ride didn't start.", START_AGAIN),
            FailureCode.startCancelled to FailureText("This start was cancelled before the ride began.", START_AGAIN),
            FailureCode.startExpired to FailureText("This start arrived too late to begin the ride.", START_AGAIN),
            FailureCode.ownerForeign to
                FailureText("Another browser tab is recording this ride.", "Use that tab to control it."),
            FailureCode.settingsUnreadable to
                FailureText("Power Log couldn't read your saved settings.", "Reset settings to use the defaults."),
            FailureCode.rideInProgress to FailureText("Finish or discard this ride first.", ""),
            FailureCode.exportInProgress to FailureText("Wait for the export to finish.", ""),
            FailureCode.locationDenied to
                FailureText(
                    "Power Log can't use your location.",
                    "Allow location in Settings, or turn off GPS for this ride.",
                ),
            FailureCode.gpsRequired to
                FailureText(
                    "This distance source needs GPS recording.",
                    "Turn on GPS route, or choose another distance source in Settings.",
                ),
            FailureCode.healthUnavailable to
                FailureText("Health isn't available on this device.", "Turn off Save to Health, then start again."),
            FailureCode.healthDenied to
                FailureText(
                    "Power Log can't save to Health.",
                    "Allow it in the Health app, or turn off Save to Health.",
                ),
            FailureCode.healthLocked to
                FailureText("Health data is unavailable while this device is locked.", "Unlock it, then try again."),
            FailureCode.watchNotInstalled to
                FailureText(
                    "Power Log isn't installed on your Apple Watch.",
                    "Install it from the Watch app on your iPhone, or turn off Apple Watch in Settings.",
                ),
            FailureCode.watchLaunchFailed to
                FailureText(
                    "Power Log couldn't open on your Apple Watch.",
                    "Unlock your Apple Watch and keep it near your iPhone, then start again.",
                ),
            FailureCode.watchBusy to
                FailureText(
                    "Your Apple Watch is still busy with another ride.",
                    "Finish or discard it on your Apple Watch, then start again.",
                ),
            FailureCode.watchStartUnconfirmed to
                FailureText(
                    "Your Apple Watch didn't confirm the start.",
                    "Open Power Log on your Apple Watch, keep it unlocked and nearby, then start again.",
                ),
            FailureCode.watchVersionMismatch to
                FailureText(
                    "Power Log on your Apple Watch is a different version.",
                    "$SAME_BUILD, or turn off Apple Watch in Settings.",
                ),
            FailureCode.watchMessageInvalid to
                FailureText("Your Apple Watch sent something Power Log couldn't read.", "$SAME_BUILD."),
            FailureCode.bluetoothDenied to
                FailureText("Power Log can't use Bluetooth.", "Allow Bluetooth for Power Log in Settings."),
            FailureCode.bluetoothOff to FailureText("Bluetooth is off.", "Turn on Bluetooth, then search again."),
            FailureCode.bluetoothNotReady to FailureText("Bluetooth isn't ready yet.", "Try again in a moment."),
            FailureCode.bluetoothUnsupported to FailureText("This device doesn't support Bluetooth Low Energy.", ""),
            FailureCode.bluetoothUnavailable to FailureText("Bluetooth isn't available in this build.", ""),
            FailureCode.bluetoothSearchFailed to FailureText("Bluetooth search failed.", "Try again."),
            FailureCode.bikeNotFound to
                FailureText(
                    "No CYC X6 or X12 controller found.",
                    "Turn on your bike's display, keep your phone nearby, then search again.",
                ),
            FailureCode.bikeUnavailable to
                FailureText(
                    "Power Log couldn't connect to your bike.",
                    "Close other apps connected to the bike, turn its display off and on, then connect again.",
                ),
            FailureCode.bikeReconnecting to FailureText("Connection to your bike was lost.", "Reconnecting…"),
            FailureCode.controllerNoReply to
                FailureText(
                    "This device didn't reply.",
                    "If it's a CYC X6 or X12 controller, turn on the bike's display and connect again.",
                ),
            FailureCode.controllerUnsupported to
                FailureText("This isn't a supported controller.", "Power Log works with CYC X6 and X12."),
            FailureCode.firmwareUnsupported to
                FailureText(
                    "This controller's firmware isn't supported yet.",
                    "Share diagnostics from Settings so support can be added.",
                ),
            FailureCode.differentBike to FailureText("Finish this ride before connecting a different bike.", ""),
            FailureCode.disconnectFirst to FailureText("Disconnect before scanning for another controller.", ""),
            FailureCode.storageBusy to FailureText("Power Log is busy saving your ride.", "Try again in a moment."),
            FailureCode.storageFull to FailureText("This device is out of storage.", "Free up space, then try again."),
            FailureCode.storageFailed to FailureText("Power Log couldn't save this change.", TRY_AGAIN_KEPT),
            FailureCode.readFailed to
                FailureText(
                    "Power Log couldn't read this ride.",
                    "Try again, and report it if this keeps happening.",
                ),
            FailureCode.chartCapacity to FailureText("Choose a smaller range with fewer chart discontinuities.", ""),
            FailureCode.rideDeleted to FailureText("This ride was deleted.", "Choose another ride in History."),
            FailureCode.exportNotReady to FailureText("This ride isn't ready to export yet.", ""),
            FailureCode.exportChanged to FailureText("This ride changed while it was exporting.", "Export it again."),
            FailureCode.exportLimit to FailureText("This ride is too large for this file.", ZIP_AVAILABLE),
            FailureCode.exportNoRecords to
                FailureText(
                    "This ride has no power, cadence, heart rate, GPS or distance readings for this file.",
                    ZIP_AVAILABLE,
                ),
            FailureCode.exportSinkFailed to FailureText("Power Log could not write the export file.", ""),
            FailureCode.exportLeftScreen to
                FailureText("The export stopped because Power Log left the screen.", "Export again."),
            FailureCode.importUnreadable to
                FailureText(
                    "Power Log couldn't open this CSV.",
                    "Choose telemetry.csv from a Power Log ride-data ZIP.",
                ),
            FailureCode.settingsSaveFailed to FailureText("Your settings weren’t saved.", "Try again."),
            FailureCode.uiUnavailable to
                FailureText("Power Log couldn't open this screen.", "Open Power Log, then try again."),
            FailureCode.apiMismatch to FailureText("", "Install the current Power Log build."),
            FailureCode.unknown to FailureText("Something went wrong.", TRY_AGAIN_KEPT),
        )

    private val unreadableStore =
        FailureText("Power Log can't open your saved rides.", "Reinstall the app to start over.")
    private val unavailableStore =
        FailureText("Power Log couldn't open your rides.", "Free up some storage space, then open Power Log again.")

    fun of(code: FailureCode, detail: RefusalDetail?, platform: TextPlatform, sdkLevel: Int?): FailureText =
        when (code) {
            FailureCode.storageRefused ->
                when {
                    detail != RefusalDetail.unreadable -> unavailableStore
                    platform == TextPlatform.web ->
                        unreadableStore.copy(nextStep = "Clear this site's data to start over.")
                    else -> unreadableStore
                }
            FailureCode.bluetoothDenied ->
                if (platform != TextPlatform.android) base.getValue(code)
                else
                    base
                        .getValue(code)
                        .copy(
                            nextStep =
                                if (sdkLevel != null && sdkLevel < NEARBY_DEVICES_SDK)
                                    "Allow Location for Power Log in Settings."
                                else "Allow Nearby devices for Power Log in Settings."
                        )
            FailureCode.bluetoothUnavailable ->
                if (platform != TextPlatform.web) base.getValue(code)
                else
                    FailureText(
                        "Web Bluetooth is unavailable.",
                        "Use Chrome on Android, supported desktop Chrome / Edge, or the iPhone app.",
                    )
            FailureCode.healthDenied ->
                if (platform != TextPlatform.watch) base.getValue(code)
                else base.getValue(code).copy(nextStep = "Allow it in the Health app, then start again.")
            FailureCode.settingsUnreadable ->
                if (platform != TextPlatform.watch) base.getValue(code)
                else
                    base
                        .getValue(code)
                        .copy(nextStep = "Open Settings on your iPhone and reset them, then start again.")
            else -> base.getValue(code)
        }
}
