package app.powerlog.kit

import app.powerlog.domain.core.failure.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTextsFixtureTest {
    private val failures = fixture("watch/texts.json")["failures"].fields()

    private val phoneToWatch =
        setOf(
            FailureCode.ownerForeign,
            FailureCode.rideActive,
            FailureCode.actionPending,
            FailureCode.settingsSaveFailed,
            FailureCode.settingsUnreadable,
            FailureCode.bikeRequired,
            FailureCode.gpsRequired,
            FailureCode.watchNotInstalled,
            FailureCode.locationDenied,
            FailureCode.healthDenied,
            FailureCode.healthLocked,
            FailureCode.healthUnavailable,
            FailureCode.actionUnavailable,
            FailureCode.watchLaunchFailed,
            FailureCode.watchBusy,
            FailureCode.watchStartUnconfirmed,
            FailureCode.startUnconfirmed,
            FailureCode.startInterrupted,
            FailureCode.startCancelled,
            FailureCode.startExpired,
            FailureCode.watchVersionMismatch,
            FailureCode.watchMessageInvalid,
            FailureCode.uiUnavailable,
            FailureCode.rideChanged,
            FailureCode.storageBusy,
            FailureCode.storageFull,
            FailureCode.storageFailed,
            FailureCode.unknown,
        )

    @Test
    fun theWatchTextsAreKitsWatchVariantForEveryCodeThePhoneMaySend() {
        assertEquals(phoneToWatch.map { it.name }.toSet(), failures.keys)
        for (code in phoneToWatch) {
            val text = Texts.failure(code, null, TextPlatform.watch, null)
            val row = failures.getValue(code.name)
            assertEquals(text.message, row["message"].text(), code.name)
            assertEquals(text.nextStep, row["nextStep"].text(), code.name)
        }
    }
}
