package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelemetryCaptureInstrumentedTest {
    @Test
    fun telemetryOriginalRetainsAcquisitionAndRejectsConflictsAfterNativeReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture =
            JSONObject(
                    instrumentation.context.assets.open("telemetry/admission.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONObject("retainedOriginal")
        val context = instrumentation.targetContext
        val name = "telemetry-${UUID.randomUUID()}.sqlite"
        var store = RideStore(context, name)
        try {
            val ride =
                store.create(
                    RideOptions(false, false, false),
                    RecordingClockReading(fixture.getString("timestamp"), 100000),
                )
            val profile = fixture.getJSONObject("identity")
            val identity =
                CycProtocol.restoreIdentity(
                    profile.getString("controllerModel"),
                    profile.getString("firmwareLabel"),
                    profile.getString("controllerProtocol"),
                )
            val metrics =
                fixture.getJSONObject("metrics").let { row -> row.keys().asSequence().associateWith(row::getDouble) }
            val evidence =
                TelemetryCaptureEvidence(
                    ride,
                    fixture.getString("observationId"),
                    fixture.getString("captureSessionId"),
                    fixture.getString("clockEpoch"),
                    fixture.getString("connectionEpoch"),
                    fixture.getString("sequence").toLong(),
                    Timestamps.parse(fixture.getString("timestamp")),
                    fixture.getDouble("acquisitionMonotonic"),
                    fixture.getDouble("sourceElapsedSeconds"),
                    CycProtocol.restoreReading(metrics, identity),
                )
            val capture = TelemetryCapture(store)
            assertTrue(capture.prepare(evidence).commitOriginal)
            store.transaction {
                store.insert(
                    ride,
                    10.0,
                    evidence.timestamp.original,
                    "telemetry",
                    true,
                    0,
                    metrics,
                    "${identity.controllerModel}|${identity.firmwareLabel}|${identity.controllerProtocol}",
                    evidence.connectionEpoch,
                    OriginalIdentity(evidence.observationId, "controller", evidence.sequence),
                    OriginalEvidence(10.0, evidence.acquisitionMonotonic, evidence.clockEpoch),
                    detail = evidence.detail(),
                )
            }
            capture.committed(evidence)
            store.close()
            store = RideStore(context, name)
            val reopened = store.telemetryOriginal(ride, evidence.observationId)!!
            assertEquals(evidence, reopened)
            val restored = TelemetryCapture(store)
            assertEquals(TelemetryCaptureOutcome.replay, restored.prepare(reopened).outcome)
            assertEquals(
                TelemetryCaptureOutcome.conflict,
                restored.prepare(reopened.copy(acquisitionMonotonic = 101.0)).outcome,
            )
            assertEquals(1L, store.count(ride))
            assertEquals(6, store.readableDatabase.version)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
