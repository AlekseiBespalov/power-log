package app.powerlog.core.presentation

import app.powerlog.core.RideActions
import app.powerlog.core.analytics.J
import app.powerlog.core.protocol.Rejection
import app.powerlog.core.protocol.StartWindow
import app.powerlog.core.telemetry.ControllerRequest
import kotlin.test.*

class FailurePresentationTest {
    private val f = presentationFixture("failures")

    private fun assertPlain(text: String, context: String) {
        assertTrue(text.isNotBlank(), context)
        for (marker in f["forbidden"].list().map { it.text() } + Rejection.entries.map { it.name }) assertFalse(
            marker in text,
            "$context contains $marker",
        )
    }

    @Test
    fun failureVocabularyFixture() {
        val vocabulary = f["vocabulary"].list()
        assertEquals(RideFailure.entries.map { it.name }, vocabulary.map { it["code"].text() })
        for (row in vocabulary) {
            val failure = Failures.present(row["code"].text())
            assertEquals(row["message"].text(), failure.message)
            assertEquals(row["nextStep"].text(), failure.nextStep)
            assertEquals(row["androidNextStep"].optionalText() ?: failure.nextStep, failure.androidNextStep)
            assertEquals("${failure.message} ${failure.nextStep}".trimEnd(), failure.text)
            assertEquals("${failure.message} ${failure.androidNextStep}".trimEnd(), failure.androidText)
            assertPlain(failure.text, failure.name)
            assertPlain(failure.androidText, failure.name)
            assertTrue(failure.message.endsWith("."), failure.name)
            assertTrue(
                failure.nextStep.isEmpty() || failure.nextStep.endsWith(".") || failure.nextStep.endsWith("…"),
                failure.name,
            )
        }
        assertEquals(
            "Power Log couldn't open on your Apple Watch. " +
                "Unlock your Apple Watch and keep it near your iPhone, then start again.",
            RideFailure.watchLaunchFailed.text,
        )
        assertEquals("Watch data couldn't be synced.", RideFailure.watchDataRejected.message)
    }

    @Test
    fun bluetoothPermissionTextNamesEachPlatformsPermission() {
        assertEquals(
            "Power Log can't use Bluetooth. Allow Bluetooth for Power Log in Settings.",
            RideFailure.bluetoothDenied.text,
        )
        assertEquals(
            "Power Log can't use Bluetooth. Allow Nearby devices for Power Log in Settings.",
            RideFailure.bluetoothDenied.androidText,
        )
        assertEquals(RideFailure.bikeNotFound.text, RideFailure.bikeNotFound.androidText)
    }

    @Test
    fun connectionFailuresUseRiderWording() {
        assertEquals(
            "No CYC X6 or X12 controller found. " +
                "Turn on your bike's display, keep your phone nearby, then search again.",
            RideFailure.bikeNotFound.text,
        )
        assertEquals("Bluetooth search failed. Try again.", RideFailure.bluetoothSearchFailed.text)
        assertEquals(
            "Install it from the Watch app on your iPhone, or turn off Apple Watch in Settings.",
            RideFailure.watchNotInstalled.nextStep,
        )
    }

    @Test
    fun connectionLossAndBluetoothStatesShowOneTextOnEveryPlatform() {
        val texts =
            mapOf(
                RideFailure.bikeReconnecting to "Connection to your bike was lost. Reconnecting…",
                RideFailure.bikeUnavailable to
                    "Power Log couldn't connect to your bike. " +
                        "Close other apps connected to the bike, turn its display off and on, then connect again.",
                RideFailure.bluetoothOff to "Bluetooth is off. Turn on Bluetooth, then search again.",
                RideFailure.bluetoothNotReady to "Bluetooth isn't ready yet. Try again in a moment.",
                RideFailure.bluetoothUnsupported to "This device doesn't support Bluetooth Low Energy.",
                RideFailure.bluetoothUnavailable to "Bluetooth isn't available in this build.",
            )
        for ((failure, text) in texts) {
            assertEquals(text, failure.text, failure.name)
            assertEquals(text, failure.androidText, failure.name)
            assertPlain(text, failure.name)
        }
    }

    @Test
    fun controllerRejectionsSeparateUnsupportedControllersFromUnsupportedFirmware() {
        assertEquals(RideFailure.controllerUnsupported, Failures.controllerRejection(ControllerRequest.identity))
        assertEquals(RideFailure.firmwareUnsupported, Failures.controllerRejection(ControllerRequest.telemetry))
        assertEquals(
            "This isn't a supported controller. Power Log works with CYC X6 and X12.",
            RideFailure.controllerUnsupported.text,
        )
        assertEquals(
            "This controller's firmware isn't supported yet. Share diagnostics from Settings so support can be added.",
            RideFailure.firmwareUnsupported.text,
        )
    }

    @Test
    fun controllerSpeedNoteAppearsOnlyForIdentitiesWithoutAKnownSpeedUnit() {
        val note = "Controller speed needs CYC X6 or X12 firmware with protocol 5.3."
        assertNull(ControllerNotes.speed("X6", "20250604", "5.3"))
        assertNull(ControllerNotes.speed("X12", "20250604", "5.3"))
        assertEquals(note, ControllerNotes.speed("X6", "20240101", "5.2"))
        assertEquals(note, ControllerNotes.speed("X12", "20260101", "6.0"))
        assertEquals(note, ControllerNotes.speed("X6_suffix", "20250604", "5.3"))
        assertNull(ControllerNotes.speed(null, null, null))
        assertNull(ControllerNotes.speed("X6", "20250604", null))
        assertNull(ControllerNotes.speed("X1", "20250604", "5.3"))
    }

    @Test
    fun failureUnknownInputsPresentGenericText() {
        for (input in f["unknownInputs"].list()) {
            val failure = Failures.present(input.optionalText())
            assertEquals(RideFailure.unknown, failure, input.toString())
            assertEquals("Something went wrong. Try again. Your recorded data is kept.", failure.text)
        }
    }

    @Test
    fun failureBridgeContractFixture() {
        val bridge = f["bridge"]
        assertEquals("failure", bridge["snapshot"]["field"].text())
        assertEquals(listOf("code", "message"), bridge["snapshot"]["shape"].list().map { it.text() })
        assertEquals(listOf("code", "message"), bridge["rejection"]["shape"].list().map { it.text() })
        val payloads =
            bridge["snapshot"]["cases"].list().mapNotNull { row ->
                val wire = row["wire"]
                assertTrue(wire["sessionBusy"] is J.Bool && wire["healthBusy"] is J.Bool)
                wire["failure"]
                    .takeUnless { it == J.Null }
                    ?.let { Triple(it["code"], it["message"], row["nativeDetail"]) }
            } + bridge["rejection"]["cases"].list().map { Triple(it["code"], it["message"], it["nativeDetail"]) }
        assertTrue(payloads.size >= 6)
        for ((code, message, detail) in payloads) {
            val failure = Failures.present(code.text())
            assertEquals(code.text(), failure.name)
            assertEquals(failure.text, message.text())
            assertPlain(message.text(), code.text())
            detail.optionalText()?.let { assertFalse(it in message.text()) }
        }
        for (row in bridge["snapshot"]["cases"].list()) {
            val wire = row["wire"]
            val endingOwner = wire["endingOwner"] == J.Bool(true)
            val context = RideStartContext(hasSession = wire["sessionBusy"].bool(), endingOwner = endingOwner)
            assertEquals(
                if (wire["sessionBusy"].bool() || endingOwner) RideFailure.rideFinishing.name else null,
                RideStarts.rejection("completed", context),
            )
        }
    }

    @Test
    fun failureCodesFromStartAndActionRules() {
        val codes = RideFailure.entries.map { it.name }.toSet()
        val starts =
            mapOf(
                RideStartContext(unresolvedOwner = true) to RideFailure.ownerUnresolved,
                RideStartContext(recovering = true) to RideFailure.ownerUnresolved,
                RideStartContext(recoveredForeignOwner = true) to RideFailure.ownerUnresolved,
                RideStartContext(hasSession = true) to RideFailure.rideFinishing,
                RideStartContext(endingOwner = true) to RideFailure.rideFinishing,
                RideStartContext(finishing = true) to RideFailure.rideFinishing,
                RideStartContext(deletionProbeInFlight = true) to RideFailure.rideFinishing,
                RideStartContext(busy = true) to RideFailure.actionPending,
            )
        for ((context, failure) in starts) assertEquals(failure.name, RideStarts.rejection("completed", context))
        assertEquals(RideFailure.rideActive.name, RideStarts.rejection("running", RideStartContext()))
        assertNull(RideStarts.rejection("completed", RideStartContext()))
        val ride = "11111111-1111-4111-8111-111111111111"
        val reasons =
            listOf(
                RideActions.rejection("pause", "paused"),
                RideActions.rejection("resume", "running"),
                RideActions.rejection("discard", "completed"),
                RideActions.rejection("recover", "running"),
                RideActions.admit(
                        ActionPresentationState(),
                        ActionRequest("a", ride, RideAction.pause),
                        "running",
                        ride,
                        foreignOwner = true,
                    )
                    .error,
                RideActions.admit(ActionPresentationState(), ActionRequest("a", ride, RideAction.stop), "running", ride)
                    .error,
                RideActions.admit(
                        ActionPresentationState(),
                        ActionRequest("a", ride, RideAction.pause),
                        "running",
                        null,
                    )
                    .error,
                RideActions.admit(
                        ActionPresentationState(),
                        ActionRequest("a", ride, RideAction.stop),
                        "running",
                        ride,
                        nativePendingAction = "discard",
                    )
                    .error,
                PresentationTimeouts.evaluate(PresentationWait.start, 0, StartWindow.deadlineUs).error,
                PresentationTimeouts.evaluate(PresentationWait.recovery, 0, PresentationTimeouts.recoveryUs).error,
                PresentationTimeouts.evaluate(PresentationWait.start, 10, 0).error,
            )
        assertEquals(
            listOf(
                "actionUnavailable",
                "actionUnavailable",
                "actionUnavailable",
                "actionUnavailable",
                "ownerForeign",
                "finishConfirmationRequired",
                "rideChanged",
                "discardPending",
                "startUnconfirmed",
                "ownerUnconfirmed",
                "unknown",
            ),
            reasons,
        )
        assertTrue(reasons.all { it in codes })
        val pending =
            RideActions.admit(ActionPresentationState(), ActionRequest("a", null, RideAction.start), "idle", null)
        val raw =
            "UnexpectedException: The operation couldn’t be completed. Unable to open URLs while locked or " +
                "passcode compliance is pending (at ExpoModulesCore/Promise.swift:56)"
        assertEquals("unknown", RideActions.complete(pending.next, "a", null, raw).error)
        assertEquals(
            "watchLaunchFailed",
            RideActions.complete(pending.next, "a", null, RideFailure.watchLaunchFailed.name).next.error,
        )
        assertEquals(PresentationTimeouts.startUs, StartWindow.deadlineUs)
    }
}
