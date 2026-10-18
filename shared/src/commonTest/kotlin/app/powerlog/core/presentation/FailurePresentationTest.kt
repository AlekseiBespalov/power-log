package app.powerlog.core.presentation

import app.powerlog.core.RideActions
import app.powerlog.core.analytics.J
import app.powerlog.core.protocol.Rejection
import app.powerlog.core.protocol.StartWindow
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
            assertEquals("${failure.message} ${failure.nextStep}".trimEnd(), failure.text)
            assertPlain(failure.text, failure.name)
            assertTrue(failure.message.endsWith("."), failure.name)
            assertTrue(failure.nextStep.isEmpty() || failure.nextStep.endsWith("."), failure.name)
        }
        assertEquals(
            "Power Log couldn't open on your Apple Watch. " +
                "Unlock your Apple Watch and keep it near your iPhone, then start again.",
            RideFailure.watchLaunchFailed.text,
        )
        assertEquals("Watch data couldn't be synced.", RideFailure.watchDataRejected.message)
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
