package app.powerlog.domain.core.controller

import app.powerlog.domain.analytics.fixture
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.telemetry.cyc.CycProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CycDecoderTest {
    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun frame(payload: ByteArray): ByteArray {
        val crc = CycProtocol.crc(payload)
        return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
    }

    private val x6 = ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3")

    private val telemetry = fixture("telemetry/cyc/wire-metrics.json")

    @Test
    fun onlyIdentityAndSelectiveTelemetryCanBeFramed() {
        assertEquals("02016f9d4903", hex(CycDecoder.request(ControllerRequest.identity).bytes))
        assertEquals("02053203c0fb8f5a1a03", hex(CycDecoder.request(ControllerRequest.telemetry).bytes))
        assertEquals("02016f9d4903", CycDecoder.traceContract.identityRequestHex)
        assertEquals("02053203c0fb8f5a1a03", CycDecoder.traceContract.telemetryRequestHex)
        assertEquals("03c0fb8f", CycDecoder.traceContract.telemetryMaskHex)
        assertEquals(CycProtocol.fields.size, CycDecoder.traceContract.fields.size)
    }

    @Test
    fun identitiesDecodeAsTodaysAndOnlyX6AndX12On53DeriveControllerSpeed() {
        for (case in fixture("telemetry/cyc/identity.json")["cases"].list()) {
            val reply = CycDecoder.newFramer().feed(frame(bytes(case["payloadHex"].text())), null).replies.single()
            if (!case["valid"].bool()) {
                assertEquals(ControllerReply.Refused(ControllerRequest.identity), reply, case.toString())
                continue
            }
            val identity = assertIs<ControllerReply.Identity>(reply, case.toString()).identity
            assertEquals(DecoderId("cyc"), identity.decoder)
            assertEquals(case["model"].text(), identity.model, case.toString())
            assertEquals(case["firmware"].text(), identity.firmware, case.toString())
            val capabilities = CycDecoder.capabilities(identity)
            assertEquals(case["knownSpeed"].bool(), capabilities.controllerSpeed, case.toString())
            assertEquals(
                case["knownSpeed"].bool(),
                MetricId.controllerSpeedMps in capabilities.metrics,
                case.toString(),
            )
            assertTrue(MetricId.speedRaw in capabilities.metrics && MetricId.motorInputPowerW in capabilities.metrics)
        }
    }

    @Test
    fun aReadingCarriesTheMeasuredAndDerivedValues() {
        val payload = frame(bytes(telemetry["payloadHex"].text()))
        val reading = assertIs<ControllerReply.Reading>(CycDecoder.newFramer().feed(payload, x6).replies.single())
        for ((name, expected) in telemetry["expected"].fields()) assertEquals(
            expected.double(),
            reading.values.getValue(MetricId.valueOf(name)),
            name,
        )
        assertEquals(3.6000000000000001 / 3.6, reading.values[MetricId.controllerSpeedMps])
        val unverified = assertIs<ControllerReply.Reading>(CycDecoder.newFramer().feed(payload, null).replies.single())
        assertNull(unverified.values[MetricId.controllerSpeedMps])
        val other = x6.copy(protocol = "9.9")
        assertNull(
            assertIs<ControllerReply.Reading>(CycDecoder.newFramer().feed(payload, other).replies.single())
                .values[MetricId.controllerSpeedMps]
        )
    }

    @Test
    fun deriveAddsBatteryInputPowerAndControllerSpeed() {
        val derived =
            CycDecoder.derive(
                x6,
                mapOf(
                    MetricId.batteryVoltageV to 52.3,
                    MetricId.batteryCurrentA to 4.07,
                    MetricId.speedRaw to 25.2,
                ),
            )
        assertEquals(212.861, derived[MetricId.motorInputPowerW])
        assertEquals(25.2 / 3.6, derived[MetricId.controllerSpeedMps])
        assertNull(
            CycDecoder.derive(x6.copy(model = "X6_suffix"), mapOf(MetricId.speedRaw to 25.2))[
                    MetricId.controllerSpeedMps]
        )
    }

    @Test
    fun aRefusedPacketAnswersItsRequest() {
        val framer = CycDecoder.newFramer()
        assertEquals(
            listOf(ControllerReply.Refused(ControllerRequest.telemetry)),
            framer.feed(frame(bytes("320040510800000000")), x6).replies,
        )
        assertEquals(emptyList(), framer.feed(frame(bytes("04")), x6).replies)
    }

    @Test
    fun aFrameSplitAcrossNotificationsDecodesOnceAndResetDiscardsTheRest() {
        val whole = frame(bytes(telemetry["payloadHex"].text()))
        val framer = CycDecoder.newFramer()
        val first = framer.feed(whole.copyOfRange(0, 20), x6)
        assertEquals(emptyList(), first.replies)
        assertEquals(20, first.pendingBytes)
        assertIs<ControllerReply.Reading>(framer.feed(whole.copyOfRange(20, whole.size), x6).replies.single())
        framer.feed(whole.copyOfRange(0, 7), x6)
        val reset = framer.reset()
        assertEquals(0, reset.pendingBytes)
        assertEquals(7L, reset.discardedBytes)
    }

    @Test
    fun aFeedReportsOnlyTheBytesItDiscarded() {
        val whole = frame(bytes(telemetry["payloadHex"].text()))
        val framer = CycDecoder.newFramer()
        assertEquals(3L, framer.feed(bytes("aabbcc") + whole, x6).discardedBytes)
        val clean = framer.feed(whole, x6)
        assertIs<ControllerReply.Reading>(clean.replies.single())
        assertEquals(0L, clean.discardedBytes)
    }

    @Test
    fun anUnrecognisedCommandIsCountedWithItsCode() {
        val decoded = CycDecoder.newFramer().feed(frame(bytes("07")), x6)
        assertEquals(emptyList(), decoded.replies)
        assertEquals(1, decoded.unknownFrames)
        assertEquals(7, decoded.unknownCommand)
    }

    @Test
    fun aStalePartialFrameDoesNotHideTheNextReply() {
        val whole = frame(bytes(telemetry["payloadHex"].text()))
        val framer = CycDecoder.newFramer()
        framer.feed(whole.copyOfRange(0, 20), x6)
        val next = framer.feed(whole, x6)
        assertIs<ControllerReply.Reading>(next.replies.single())
        assertEquals(20L, next.discardedBytes)
        assertEquals(0, next.pendingBytes)
    }

    @Test
    fun theRegistryHoldsCycByIdAndService() {
        assertEquals(listOf<ControllerDecoder>(CycDecoder), ControllerDecoders.all)
        assertSame(CycDecoder, ControllerDecoders.of(DecoderId("cyc")))
        assertNull(ControllerDecoders.of(DecoderId("other")))
        assertSame(CycDecoder, ControllerDecoders.forTransport("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
        assertNull(ControllerDecoders.forTransport("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals(listOf("CYCMOTOR"), CycDecoder.transport.namePrefixes)
    }
}
