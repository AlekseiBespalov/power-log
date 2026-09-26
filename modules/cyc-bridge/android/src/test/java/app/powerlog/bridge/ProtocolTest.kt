package app.powerlog.bridge

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
    fun sharedRequestsAndCRC() {
        assertEquals(
            fixture.getJSONObject("crc").getInt("expected"),
            CycProtocol.crc(fixture.getJSONObject("crc").getString("ascii").toByteArray()),
        )
        for ((key, read) in
            listOf(
                "identity" to CycProtocol.Read.IDENTITY,
                "selective" to CycProtocol.Read.TELEMETRY,
            )) assertArrayEquals(
            bytes(fixture.getJSONObject("requests").getString(key)),
            CycProtocol.request(read),
        )
    }

    @Test
    fun fragmentedAndCorruptedFrames() {
        val frame = fixture.getJSONArray("telemetry").getJSONObject(3)
        val wire = bytes(frame.getString("frameHex"))
        for (size in 1..wire.size) {
            val decoder = CycProtocol.Decoder()
            val packets = wire.toList().chunked(size).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(1, packets.size)
            assertArrayEquals(bytes(frame.getString("payloadHex")), packets[0])
        }
        val framing = fixture.getJSONObject("framing")
        val result =
            CycProtocol.Decoder()
                .feed(
                    bytes("aabbcc" + framing.getString("corrupted") + framing.getString("single"))
                )
        assertEquals(1, result.size)
        assertArrayEquals(byteArrayOf(4), result.single())
    }

    @Test
    fun sameIdentityAndMeasurementsAsTypeScript() {
        val identity =
            CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        assertEquals(fixture.getJSONObject("identity").getString("controllerModel"), identity.model)
        val frames = fixture.getJSONArray("telemetry")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            if (!frame.has("expected") || frame.optLong("mask") != CycProtocol.MASK.toLong())
                continue
            val values = CycProtocol.telemetry(bytes(frame.getString("payloadHex")), identity)
            val expected = frame.getJSONObject("expected")
            for (key in expected.keys()) if (key in values)
                assertEquals(key, expected.getDouble(key), values.getValue(key), 0.000001)
        }
        val full = bytes(frames.getJSONObject(2).getString("payloadHex"))
        assertThrows(IllegalArgumentException::class.java) {
            CycProtocol.telemetry(full.copyOf(full.size - 1), identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CycProtocol.telemetry(full + byteArrayOf(0), identity)
        }
    }

    @Test
    fun unknownProfilesNeverAcquireSpeedUnits() {
        val model = CycProtocol.Identity("X6_Pro", "260101A", "5.3")
        val values =
            CycProtocol.telemetry(
                bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("payloadHex")),
                model,
            )
        assertFalse(values.containsKey("controllerSpeedMps"))
        for (label in listOf("X60 20250604", "X6 20250604a", "X6 20250604/extra")) assertThrows(
            IllegalStateException::class.java
        ) {
            CycProtocol.identity(byteArrayOf(111, 5, 3) + label.toByteArray() + byteArrayOf(0))
        }
    }
}
