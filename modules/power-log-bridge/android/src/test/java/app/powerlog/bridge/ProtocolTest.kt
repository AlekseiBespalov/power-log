package app.powerlog.bridge

import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ProtocolTest {
    private val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun contractIdentityAndSignedMetricsUseGattCallbacksAndTheSession() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val identities = telemetryFixture("cyc/identity").getJSONArray("cases")
        val metrics = telemetryFixture("cyc/wire-metrics")
        for (index in 0 until identities.length()) {
            val row = identities.getJSONObject(index)
            val readings = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, readings::add).use { adapter ->
                assertArrayEquals(
                    bytes(fixture.getJSONObject("requests").getString("identity")),
                    org.robolectric.Shadows.shadowOf(adapter.gatt).latestWrittenBytes,
                )
                adapter.identity(bytes(row.getString("payloadHex")))
                if (row.getBoolean("valid")) {
                    assertArrayEquals(
                        bytes(fixture.getJSONObject("requests").getString("selective")),
                        org.robolectric.Shadows.shadowOf(adapter.gatt).latestWrittenBytes,
                    )
                    val wire = controllerFrame(bytes(metrics.getString("payloadHex")))
                    for (byte in wire) adapter.notification(byteArrayOf(byte))
                    val reading = readings.single()
                    assertEquals(row.getString("model"), reading.identity!!.controllerModel)
                    assertEquals(row.getString("firmware"), reading.identity!!.firmwareLabel)
                    assertEquals(row.getBoolean("knownSpeed"), reading.identity!!.hasKnownSpeedUnit)
                    for (key in metrics.getJSONObject("expected").keys()) assertEquals(
                        key,
                        metrics.getJSONObject("expected").getDouble(key),
                        reading.values().getValue(key),
                        0.0,
                    )
                    if (row.getBoolean("knownSpeed"))
                        assertEquals(
                            row.getDouble("speedMps"),
                            reading.value(TelemetryMetric.controllerSpeedMps)!!,
                            0.0,
                        )
                    else assertNull(reading.value(TelemetryMetric.controllerSpeedMps))
                } else {
                    assertEquals("error", adapter.bluetooth.state["status"])
                    assertTrue(readings.isEmpty())
                }
            }
        }
    }

    @Test
    fun contractFramingNoiseAndMalformedTelemetryUseTheProductionAdapter() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val vectors = telemetryFixture("cyc/framing")
        val malformed = vectors.getJSONArray("malformedFrameHex")
        for (i in 0 until malformed.length()) {
            val readings = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, readings::add).use { adapter ->
                adapter.identity()
                adapter.notification(bytes(vectors.getString("noiseHex") + malformed.getString(i)))
                assertTrue(readings.isEmpty())
                adapter.notification(
                    controllerFrame(bytes(telemetryFixture("cyc/wire-metrics").getString("payloadHex")))
                )
                assertEquals(1, readings.size)
            }
        }
        val rejected = vectors.getJSONArray("rejectedPayloadHex")
        for (i in 0 until rejected.length()) {
            val readings = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, readings::add).use { adapter ->
                adapter.identity()
                adapter.notification(controllerFrame(bytes(rejected.getString(i))))
                assertTrue(readings.isEmpty())
                if (rejected.getString(i).startsWith("32")) assertEquals("error", adapter.bluetooth.state["status"])
            }
        }
    }

    @Test
    @Config(shadows = [BlockedControllerGatt::class])
    fun crcValidMalformedTelemetryMaskAndLengthStopEvenAnActiveRide() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val valid = bytes(telemetryFixture("cyc/wire-metrics").getString("payloadHex"))
        val invalidMask = valid.copyOf().apply { this[1] = (this[1].toInt() xor 1).toByte() }
        for (payload in listOf(invalidMask, valid.copyOf(valid.size - 1))) {
            val readings = mutableListOf<TelemetryReading>()
            ControllerAdapterFixture(handler, readings::add).use { adapter ->
                adapter.identity()
                adapter.notification(controllerFrame(payload))
                assertTrue(readings.isEmpty())
                assertEquals("error", adapter.bluetooth.state["status"])
                assertEquals("Bike returned an invalid controller reply.", adapter.bluetooth.state["error"])
                assertNull(adapter.bluetooth.deviceId)
                assertFalse(adapterSession(adapter.bluetooth).state.cancellationPending)
                assertTrue(
                    org.robolectric.shadow.api.Shadow.extract<BlockedControllerGatt>(adapter.gatt).disconnectRequested
                )
                assertTrue(org.robolectric.Shadows.shadowOf(adapter.gatt).isClosed)
                completeCancellation(adapter.bluetooth, handler)
                assertTrue(org.robolectric.Shadows.shadowOf(adapter.gatt).isClosed)
                org.robolectric.Shadows.shadowOf(handler.looper).idleFor(java.time.Duration.ofSeconds(60))
                assertEquals(1, adapter.bluetooth.diagnostics()["connectionAttempts"])
                assertEquals("error", adapter.bluetooth.state["status"])
            }
        }
    }
}
