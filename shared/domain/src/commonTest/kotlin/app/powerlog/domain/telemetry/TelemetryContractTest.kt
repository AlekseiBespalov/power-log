package app.powerlog.domain.telemetry

import app.powerlog.domain.analytics.J
import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.telemetry.cyc.*
import kotlin.test.*

internal fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun telemetryFixture(name: String): J =
    fixture("telemetry/${if (name in listOf("framing", "identity", "wire-metrics")) "cyc/" else ""}$name.json")

internal fun protocolFixture(): J = fixture("../protocol.json")

internal fun telemetry(): TelemetryReading =
    CycProtocol.withIdentity(
        CycProtocol.telemetry(hex(protocolFixture()["telemetry"].list().last()["payloadHex"].text())),
        CycProtocol.identity(hex(protocolFixture()["identity"]["payloadHex"].text())),
    )

internal fun controllerFrame(payload: ByteArray): ByteArray {
    val crc = CycProtocol.crc(payload)
    return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
}

class TelemetryContractTest {
    @Test
    fun telemetryControllerCapabilities() {
        val supplied = mutableSetOf(TelemetryMetric.humanPowerW, TelemetryMetric.controllerSpeedMps)
        val capabilities = ControllerCapabilities(supplied, true)
        supplied.clear()
        assertEquals(2, capabilities.suppliedMetrics.size)
        (capabilities.suppliedMetrics as MutableSet).clear()
        assertTrue(capabilities.supplies(TelemetryMetric.humanPowerW))
        val identity = ControllerIdentity("Example motor", "release 1", "protocol 9", capabilities)
        assertTrue(identity.hasKnownSpeedUnit)
        val values = mutableMapOf(TelemetryMetric.humanPowerW to 1.25, TelemetryMetric.controllerSpeedMps to 2.6)
        val reading = TelemetryReading(values, identity)
        values.clear()
        (reading.metrics as MutableMap).clear()
        assertEquals(1.25, reading.value(TelemetryMetric.humanPowerW))
        assertFailsWith<TelemetryException> { ControllerCapabilities(emptySet(), false) }
        for (label in listOf("", " example", "example\u0000", "x".repeat(129))) assertFailsWith<TelemetryException> {
            ControllerIdentity(label, "1", "1", capabilities)
        }
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertFailsWith<
            TelemetryException
        > {
            TelemetryReading(mapOf(TelemetryMetric.humanPowerW to invalid), identity)
        }
        assertFailsWith<TelemetryException> { TelemetryReading(emptyMap(), identity) }
        assertFailsWith<TelemetryException> { TelemetryReading(mapOf(TelemetryMetric.motorRpm to 0.0), identity) }
        for (row in telemetryFixture("identity")["cases"].list().filter { it["valid"].bool() }) {
            val decoded = CycProtocol.identity(hex(row["payloadHex"].text()))
            assertEquals(row["knownSpeed"].bool(), decoded.capabilities.hasKnownSpeedUnit)
            assertEquals(row["knownSpeed"].bool(), decoded.capabilities.supplies(TelemetryMetric.controllerSpeedMps))
            assertEquals(
                CycProtocol.fields.map { it.metric }.toSet() +
                    TelemetryMetric.motorInputPowerW +
                    if (decoded.hasKnownSpeedUnit) setOf(TelemetryMetric.controllerSpeedMps) else emptySet(),
                decoded.capabilities.suppliedMetrics,
            )
        }
        val profile = CycProtocol.restoreIdentity("X6", "20250725", "5.3")
        val forged =
            ControllerIdentity(
                profile.controllerModel,
                profile.firmwareLabel,
                profile.controllerProtocol,
                ControllerCapabilities(setOf(TelemetryMetric.humanPowerW), false),
            )
        assertFailsWith<CycException> {
            CycProtocol.restoreReading(telemetry().metrics.mapKeys { it.key.name }, forged)
        }
    }

    @Test
    fun decodingControllerRepliesChecksTheIdentityOnce() {
        var checks = 0
        CycProtocol.identityCheckObserverForTesting = { checks++ }
        try {
            val wire = protocolFixture()
            val decoder = CycControllerDecoder()
            val identity =
                assertIs<ControllerIdentityReply>(
                        decoder.feed(controllerFrame(hex(wire["identity"]["payloadHex"].text())), null).replies.single()
                    )
                    .identity
            val sample = wire["telemetry"].list().last()
            val readings =
                List(64) {
                    assertIs<ControllerReadingReply>(
                            decoder.feed(hex(sample["frameHex"].text()), identity).replies.single()
                        )
                        .reading
                }
            assertEquals(1, checks)
            val unverified =
                ControllerIdentity(
                    identity.controllerModel,
                    identity.firmwareLabel,
                    identity.controllerProtocol,
                    identity.capabilities,
                )
            assertEquals(
                readings.last(),
                CycProtocol.withIdentity(CycProtocol.telemetry(hex(sample["payloadHex"].text())), unverified),
            )
            assertEquals(2, checks)
            val forged =
                ControllerIdentity(
                    identity.controllerModel,
                    identity.firmwareLabel,
                    identity.controllerProtocol,
                    ControllerCapabilities(setOf(TelemetryMetric.humanPowerW), false),
                )
            assertIs<ControllerRejectedReply>(decoder.feed(hex(sample["frameHex"].text()), forged).replies.single())
        } finally {
            CycProtocol.identityCheckObserverForTesting = null
        }
    }

    @Test
    fun telemetryFramingFixtures() {
        val f = protocolFixture()
        assertEquals(f["crc"]["expected"].long().toInt(), CycProtocol.crc(f["crc"]["ascii"].text().encodeToByteArray()))
        for (request in CycRequest.entries) assertEquals(f["requests"][request.name].text(), request.frame().hex())
        val single = hex(f["framing"]["single"].text())
        assertContentEquals(byteArrayOf(4), CycFrameDecoder().feed(single).single())
        assertTrue(CycFrameDecoder().feed(hex(f["framing"]["corrupted"].text())).isEmpty())
        val long = hex(f["framing"]["longFrameHex"].text())
        assertContentEquals(hex(f["framing"]["longPayloadHex"].text()), CycFrameDecoder().feed(long).single())
        for (split in 1 until long.size) {
            val decoder = CycFrameDecoder()
            assertTrue(decoder.feed(long.copyOfRange(0, split)).isEmpty())
            assertContentEquals(
                hex(f["framing"]["longPayloadHex"].text()),
                decoder.feed(long.copyOfRange(split, long.size)).single(),
            )
            assertEquals(0, decoder.pendingBytes)
        }
        val decoder = CycFrameDecoder()
        assertEquals(3, decoder.feed(single + long + single).size)
        val noisy = CycFrameDecoder()
        val noise = hex(telemetryFixture("framing")["noiseHex"].text())
        assertEquals(1, noisy.feed(noise + hex(f["framing"]["corrupted"].text()) + single).size)
        assertEquals((noise.size + single.size).toLong(), noisy.discardedBytes)
        noisy.feed(single.copyOfRange(0, 3))
        noisy.reset()
        assertEquals(0, noisy.pendingBytes)
        assertEquals((noise.size + single.size + 3).toLong(), noisy.discardedBytes)
    }

    @Test
    fun maximumLengthFragmentedFrame() {
        val f = telemetryFixture("framing")["maximumFragmentedFrame"]
        val payload = ByteArray(f["payloadSize"].long().toInt()) { f["fillByte"].long().toByte() }
        val crc = CycProtocol.crc(payload)
        val frame = byteArrayOf(3, 4, 0) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
        val split = f["splitAt"].long().toInt()
        val decoder = CycFrameDecoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, split)).isEmpty())
        assertContentEquals(payload, decoder.feed(frame.copyOfRange(split, frame.size)).single())
        assertEquals(0L, decoder.discardedBytes)
    }

    @Test
    fun telemetryMalformedFramesAndPackets() {
        val f = telemetryFixture("framing")
        for (frame in f["malformedFrameHex"].list()) assertTrue(CycFrameDecoder().feed(hex(frame.text())).isEmpty())
        val raw = hex(protocolFixture()["telemetry"].list().last()["payloadHex"].text())
        for (mask in f["unsupportedMasks"].list()) {
            val changed = raw.copyOf()
            hex(mask.text()).copyInto(changed, 1)
            assertEquals(
                CycRejection.telemetryMask,
                assertFailsWith<CycException> { CycProtocol.telemetry(changed) }.reason,
            )
        }
        for (size in f["payloadSizes"].list().map { it.long().toInt() }.filter { it != 54 }) assertFailsWith<
            CycException
        > {
            CycProtocol.telemetry(raw.copyOf(size))
        }
        for (payload in f["rejectedPayloadHex"].list()) assertFailsWith<CycException> {
            CycProtocol.packet(hex(payload.text()))
        }
        assertFailsWith<CycException> { CycProtocol.telemetry(raw.copyOf().also { it[0] = 4 }) }
        val decoder = CycFrameDecoder()
        decoder.feed(ByteArray(10000) { 2 })
        assertTrue(decoder.pendingBytes <= 1028)
        val complete = hex(protocolFixture()["framing"]["single"].text())
        assertEquals(1, decoder.feed(complete).size)
        assertTrue(decoder.discardedBytes >= 10000)
        assertIs<CycTelemetryPacket>(CycProtocol.packet(raw))
    }

    @Test
    fun telemetryReadingFixtures() {
        for (row in protocolFixture()["telemetry"].list()) {
            if (row["mask"].long().toInt() != CycProtocol.selectedMask) {
                assertFailsWith<CycException> { CycProtocol.telemetry(hex(row["payloadHex"].text())) }
                continue
            }
            val decoded = CycProtocol.telemetry(hex(row["payloadHex"].text()))
            for ((key, value) in row["expected"].fields()) assertEquals(
                value.double(),
                decoded.value(TelemetryMetric.valueOf(key)),
            )
            assertNull(decoded.value(TelemetryMetric.controllerSpeedMps))
            assertContentEquals(
                hex(row["payloadHex"].text()),
                CycFrameDecoder().feed(hex(row["frameHex"].text())).single(),
            )
        }
        assertEquals(49, CycProtocol.fields.sumOf { it.width })
        assertEquals(CycProtocol.selectedMask, CycProtocol.fields.fold(0) { mask, field -> mask or (1 shl field.bit) })
        assertEquals(16, CycProtocol.fields.map { it.metric }.distinct().size)
    }

    @Test
    fun telemetrySignedMetricsAndBatteryPower() {
        val f = telemetryFixture("wire-metrics")
        val decoded = CycProtocol.telemetry(hex(f["payloadHex"].text()))
        for ((key, value) in f["expected"].fields()) assertEquals(
            value.double(),
            decoded.value(TelemetryMetric.valueOf(key))!!,
            0.00000001,
            key,
        )
        assertEquals(-15.129, decoded.value(TelemetryMetric.motorInputPowerW))
        assertEquals(32767.0, decoded.value(TelemetryMetric.humanPowerW))
        val payload = hex(f["payloadHex"].text())
        payload[13] = 0
        payload[14] = 0
        payload[15] = 0
        payload[16] = 1
        assertEquals(0.123, CycProtocol.telemetry(payload).value(TelemetryMetric.motorInputPowerW))
    }

    @Test
    fun telemetryIdentityFixtures() {
        val original = protocolFixture()["identity"]
        val decoded = CycProtocol.identity(hex(original["payloadHex"].text()))
        assertEquals(original["controllerModel"].text(), decoded.controllerModel)
        assertEquals(original["firmwareLabel"].text(), decoded.firmwareLabel)
        for (case in telemetryFixture("identity")["cases"].list()) {
            if (!case["valid"].bool()) {
                assertFailsWith<CycException> { CycProtocol.identity(hex(case["payloadHex"].text())) }
                continue
            }
            val identity = CycProtocol.identity(hex(case["payloadHex"].text()))
            assertEquals(case["model"].text(), identity.controllerModel)
            assertEquals(case["firmware"].text(), identity.firmwareLabel)
            assertEquals(case["knownSpeed"].bool(), identity.hasKnownSpeedUnit)
            assertEquals(
                identity,
                CycProtocol.restoreIdentity(
                    identity.controllerModel,
                    identity.firmwareLabel,
                    identity.controllerProtocol,
                ),
            )
            val raw = hex(telemetryFixture("wire-metrics")["payloadHex"].text())
            val sample = CycProtocol.withIdentity(CycProtocol.telemetry(raw), identity)
            assertEquals(case["speedRaw"].double(), sample.value(TelemetryMetric.speedRaw))
            assertEquals(case["speedMps"].optionalDouble(), sample.value(TelemetryMetric.controllerSpeedMps))
        }
        val tailCases =
            telemetryFixture("identity")["cases"].list().filter {
                it["id"].optionalText()?.startsWith("opaque-tail-") == true
            }
        val opaque = tailCases.map { CycProtocol.identity(hex(it["payloadHex"].text())) }
        assertEquals(opaque[0], opaque[1])
        assertNull(
            CycProtocol.withIdentity(
                    telemetry(),
                    CycProtocol.identity(hex(telemetryFixture("identity")["cases"].list()[2]["payloadHex"].text())),
                )
                .value(TelemetryMetric.controllerSpeedMps)
        )
        val zeroCommand = hex(original["payloadHex"].text()).also { it[0] = 0 }
        assertIs<CycIdentityPacket>(CycProtocol.packet(zeroCommand))
        for (bad in
            listOf(
                byteArrayOf(),
                byteArrayOf(111, 5, 3),
                ByteArray(1025),
                byteArrayOf(111, 5, 3) + "X6 20250725".encodeToByteArray(),
                byteArrayOf(111, 5, 3) +
                    "X6 20250725 ".encodeToByteArray() +
                    ByteArray(130) { 255.toByte() } +
                    byteArrayOf(0),
            )) assertFailsWith<CycException> { CycProtocol.identity(bad) }
    }

    @Test
    fun fixedIdentityHeaderNeverExposesItsPrivateTail() {
        for (firmware in listOf("20250604MX", "20250811UL", "20260618  ", "v5.3.1-mx2")) {
            val header = "X12       $firmware".encodeToByteArray()
            assertEquals(20, header.size)
            val expected = CycProtocol.restoreIdentity("X12", firmware.trimEnd(), "5.3")
            for (firstByte in 0..255) {
                val payload =
                    byteArrayOf(111, 5, 3) +
                        header +
                        byteArrayOf(firstByte.toByte()) +
                        "SYNTHETIC-PRIVATE-TAIL".encodeToByteArray() +
                        byteArrayOf(0)
                assertEquals(expected, CycProtocol.identity(payload), "$firmware tail $firstByte")
            }
        }
    }
}
