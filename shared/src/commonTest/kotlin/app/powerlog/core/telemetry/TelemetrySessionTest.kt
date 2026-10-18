package app.powerlog.core.telemetry

import app.powerlog.core.analytics.JsonParser
import app.powerlog.core.telemetry.cyc.CycControllerDecoder
import app.powerlog.core.telemetry.cyc.CycProtocol
import kotlin.test.*

internal class JsonLineDecoder(private val supplied: Set<TelemetryMetric> = setOf(TelemetryMetric.controllerSpeedMps)) :
    ControllerProtocol, ControllerRestorer {
    private var buffer = ""
    private var discarded = 0L

    override fun supportsProtocol(controllerProtocol: String): Boolean = controllerProtocol == "test-v2"

    override fun restoreIdentity(
        controllerModel: String,
        firmwareLabel: String,
        controllerProtocol: String,
    ): ControllerIdentity {
        require(
            controllerModel == telemetryFixture("session")["model"].text() &&
                firmwareLabel == "release-1" &&
                supportsProtocol(controllerProtocol)
        )
        return ControllerIdentity(
            controllerModel,
            firmwareLabel,
            controllerProtocol,
            ControllerCapabilities(supplied, true),
        )
    }

    override fun restoreReading(metrics: Map<String, Double>, identity: ControllerIdentity): TelemetryReading {
        require(metrics.keys == supplied.map { it.name }.toSet())
        return TelemetryReading(metrics.mapKeys { TelemetryMetric.valueOf(it.key) }, identity)
    }

    override fun request(kind: ControllerRequest): ByteArray = "{\"request\":\"${kind.name}\"}\n".encodeToByteArray()

    override fun feed(notification: ByteArray, identity: ControllerIdentity?): ControllerDecodeResult {
        buffer += notification.decodeToString()
        val replies = mutableListOf<ControllerReply>()
        while ('\n' in buffer) {
            val end = buffer.indexOf('\n')
            val line = buffer.substring(0, end)
            buffer = buffer.substring(end + 1)
            val row = runCatching { JsonParser(line).parse() }.getOrNull()
            if (row == null) {
                discarded += line.encodeToByteArray().size + 1
                continue
            }
            when (row["kind"].text()) {
                "identity" ->
                    replies.add(
                        ControllerIdentityReply(
                            ControllerIdentity(
                                row["model"].text(),
                                row["firmware"].text(),
                                row["protocol"].text(),
                                ControllerCapabilities(supplied, true),
                            )
                        )
                    )
                "telemetry" ->
                    replies.add(
                        ControllerReadingReply(
                            TelemetryReading(
                                mapOf(TelemetryMetric.controllerSpeedMps to row["controllerSpeedMps"].double()),
                                identity,
                            )
                        )
                    )
            }
        }
        return ControllerDecodeResult(replies, discarded, buffer.encodeToByteArray().size)
    }

    override fun reset(): ControllerDecodeResult {
        discarded += buffer.encodeToByteArray().size
        buffer = ""
        return ControllerDecodeResult(emptyList(), discarded, 0)
    }
}

internal fun controllerFrame(payload: ByteArray): ByteArray {
    val crc = CycProtocol.crc(payload)
    return byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
}

private data class LinkCase(
    val decoder: ControllerProtocol,
    val identityBytes: ByteArray,
    val readingBytes: ByteArray,
    val identityRequest: ByteArray,
    val readingRequest: ByteArray,
    val model: String,
    val speed: Double,
)

private fun linkCases(): List<LinkCase> {
    val wire = protocolFixture()
    val json = telemetryFixture("session")
    return listOf(
        LinkCase(
            CycControllerDecoder(),
            controllerFrame(hex(wire["identity"]["payloadHex"].text())),
            hex(wire["telemetry"].list().last()["frameHex"].text()),
            hex(wire["requests"]["identity"].text()),
            hex(wire["requests"]["selective"].text()),
            "X6",
            3.77 / 3.6,
        ),
        LinkCase(
            JsonLineDecoder(),
            json["identityNotification"].text().encodeToByteArray(),
            json["telemetryNotification"].text().encodeToByteArray(),
            json["identityRequest"].text().encodeToByteArray(),
            json["telemetryRequest"].text().encodeToByteArray(),
            json["model"].text(),
            json["speedMps"].double(),
        ),
    )
}

private fun identityStage(session: TelemetrySession, start: Double = 0.0) {
    assertTrue(session.begin(start, false))
    for ((from, to) in
        listOf(
            TelemetryStage.connecting to TelemetryStage.services,
            TelemetryStage.services to TelemetryStage.characteristics,
            TelemetryStage.characteristics to TelemetryStage.notifications,
            TelemetryStage.notifications to TelemetryStage.identity,
        )) assertEquals(TelemetryTransportAction.advanced, session.advance(session.state.generation, from, to, start))
}

private fun readySession(case: LinkCase): TelemetrySession {
    val session = TelemetrySession(case.decoder)
    identityStage(session)
    assertEquals(
        TelemetryTransportAction.replyAccepted,
        session
            .notifications(session.state.generation, ControllerRequest.identity, case.identityBytes, 0.0, 0.1)
            .action,
    )
    assertEquals(
        TelemetryTransportAction.advanced,
        session.advance(session.state.generation, TelemetryStage.identity, TelemetryStage.ready, 0.1),
    )
    return session
}

internal fun exerciseControllerSessions() {
    for (case in linkCases()) {
        val session = TelemetrySession(case.decoder)
        assertContentEquals(case.identityRequest, session.request(ControllerRequest.identity))
        assertContentEquals(case.readingRequest, session.request(ControllerRequest.telemetry))
        identityStage(session)
        val generation = session.state.generation
        val split = case.identityBytes.size / 2
        assertTrue(
            session
                .notifications(
                    generation,
                    ControllerRequest.identity,
                    case.identityBytes.copyOfRange(0, split),
                    0.0,
                    0.1,
                )
                .replies
                .isEmpty()
        )
        assertNull(session.identity)
        val identified =
            session.notifications(
                generation,
                ControllerRequest.identity,
                case.identityBytes.copyOfRange(split, case.identityBytes.size),
                0.0,
                0.2,
            )
        assertEquals(TelemetryTransportAction.replyAccepted, identified.action)
        assertEquals(
            case.model,
            assertIs<ControllerIdentityReply>(identified.replies.single()).identity.controllerModel,
        )
        assertEquals(case.model, session.identity!!.controllerModel)
        assertTrue(session.identity!!.capabilities.supplies(TelemetryMetric.controllerSpeedMps))
        assertEquals(
            TelemetryTransportAction.advanced,
            session.advance(generation, TelemetryStage.identity, TelemetryStage.ready, 0.3),
        )
        assertTrue(
            session
                .notifications(
                    generation,
                    ControllerRequest.telemetry,
                    case.readingBytes.copyOfRange(0, 3),
                    0.3,
                    0.4,
                )
                .replies
                .isEmpty()
        )
        val measured =
            session.notifications(
                generation,
                ControllerRequest.telemetry,
                case.readingBytes.copyOfRange(3, case.readingBytes.size),
                0.3,
                0.5,
            )
        assertEquals(TelemetryTransportAction.replyAccepted, measured.action)
        val reading = assertIs<ControllerReadingReply>(measured.replies.single()).reading
        assertEquals(session.identity, reading.identity)
        assertEquals(case.speed, reading.value(TelemetryMetric.controllerSpeedMps))
        assertEquals(
            TelemetryTransportAction.none,
            session.notifications(generation, ControllerRequest.telemetry, case.identityBytes, 0.3, 0.6).action,
        )
        assertEquals(case.model, session.identity!!.controllerModel)
        val coalesced =
            session.notifications(
                generation,
                ControllerRequest.telemetry,
                case.readingBytes + case.readingBytes,
                0.6,
                0.7,
            )
        assertEquals(1, coalesced.replies.size)
    }
}

class TelemetrySessionTest {
    @Test
    fun telemetryMalformedMatchingReplies() {
        val wire = protocolFixture()
        val badMask = hex(wire["telemetry"].list().last()["payloadHex"].text())
        badMask[1] = 0
        val unsupported =
            hex(telemetryFixture("cyc/identity")["cases"].list().first { !it["valid"].bool() }["payloadHex"].text())
        val validIdentity = controllerFrame(hex(wire["identity"]["payloadHex"].text()))
        val validReading = hex(wire["telemetry"].list().last()["frameHex"].text())
        for ((request, malformed, valid) in
            listOf(
                Triple(ControllerRequest.identity, controllerFrame(unsupported), validIdentity),
                Triple(ControllerRequest.telemetry, controllerFrame(badMask), validReading),
            )) {
            val session =
                if (request == ControllerRequest.telemetry) readySession(linkCases().first())
                else TelemetrySession(CycControllerDecoder()).also { identityStage(it) }
            val generation = session.state.generation
            val rejected = session.notifications(generation, request, malformed + valid, 1.0, 1.1)
            assertEquals(TelemetryTransportAction.replyRejected, rejected.action)
            val reason = assertIs<ControllerRejectedReply>(rejected.replies.single())
            assertEquals(request, reason.kind)
            assertEquals(if (request == ControllerRequest.identity) "identity" else "telemetryMask", reason.reason)
            assertEquals(TelemetryStage.idle, session.state.stage)
            assertTrue(session.state.recoveryRequired)
            assertNull(session.identity)
            assertFalse(session.begin(1.2, false))
            assertTrue(session.notifications(generation, request, valid, 1.0, 1.2).replies.isEmpty())
        }
    }

    @Test
    fun telemetrySingleOutstandingReply() {
        for (case in linkCases()) {
            val handshake = TelemetrySession(case.decoder)
            identityStage(handshake)
            val identified =
                handshake.notifications(
                    handshake.state.generation,
                    ControllerRequest.identity,
                    case.identityBytes + case.readingBytes,
                    0.0,
                    0.1,
                )
            assertIs<ControllerIdentityReply>(identified.replies.single())
            assertEquals(
                TelemetryTransportAction.advanced,
                handshake.advance(handshake.state.generation, TelemetryStage.identity, TelemetryStage.ready, 0.1),
            )
            assertEquals(
                1,
                handshake
                    .notifications(handshake.state.generation, ControllerRequest.telemetry, case.readingBytes, 0.2, 0.3)
                    .replies
                    .size,
            )
            val session = readySession(case)
            val generation = session.state.generation
            val first =
                session.notifications(
                    generation,
                    ControllerRequest.telemetry,
                    case.readingBytes + case.readingBytes,
                    1.0,
                    1.1,
                )
            assertEquals(1, first.replies.size)
            assertTrue(
                session
                    .notifications(generation, ControllerRequest.telemetry, case.readingBytes, 1.0, 1.2)
                    .replies
                    .isEmpty()
            )
            assertEquals(
                1,
                session
                    .notifications(generation, ControllerRequest.telemetry, case.readingBytes, 1.3, 1.4)
                    .replies
                    .size,
            )
            assertTrue(
                session
                    .notifications(generation, ControllerRequest.telemetry, case.readingBytes, 1.0, 1.5)
                    .replies
                    .isEmpty()
            )
        }
    }

    @Test
    fun telemetryDecoderDiagnostics() {
        val data = controllerFrame(hex(protocolFixture()["identity"]["payloadHex"].text()))
        val decoder = CycControllerDecoder()
        val partial = decoder.feed(byteArrayOf(9, 8, 7) + data.copyOfRange(0, 3), null)
        assertEquals(3L, partial.discardedBytes)
        assertEquals(3, partial.pendingBytes)
        assertTrue(partial.replies.isEmpty())
        val completed = decoder.feed(data.copyOfRange(3, data.size), null)
        assertEquals(3L, completed.discardedBytes)
        assertEquals(0, completed.pendingBytes)
        assertIs<ControllerIdentityReply>(completed.replies.single())
        decoder.feed(data.copyOfRange(0, 3), null)
        val reset = decoder.reset()
        assertEquals(6L, reset.discardedBytes)
        assertEquals(0, reset.pendingBytes)
        assertTrue(reset.replies.isEmpty())
        for (case in linkCases()) {
            val session = readySession(case)
            val generation = session.state.generation
            val pending =
                session.notifications(
                    generation,
                    ControllerRequest.telemetry,
                    case.readingBytes.copyOfRange(0, 3),
                    1.0,
                    1.0,
                )
            assertEquals(0L, pending.discardedBytes)
            assertEquals(3, pending.pendingBytes)
            val drained =
                session.notifications(
                    generation,
                    ControllerRequest.telemetry,
                    case.readingBytes.copyOfRange(3, case.readingBytes.size) + case.readingBytes,
                    1.0,
                    1.1,
                )
            assertEquals(1, drained.replies.size)
            assertEquals(0L, drained.discardedBytes)
            assertEquals(0, drained.pendingBytes)
            session.notifications(
                generation,
                ControllerRequest.telemetry,
                case.readingBytes.copyOfRange(0, 3),
                2.0,
                2.0,
            )
            session.manualDisconnect(false)
            assertEquals(3L, session.discardedBytes)
            assertEquals(0, session.pendingBytes)
            val fenced = session.notifications(generation, ControllerRequest.telemetry, case.readingBytes, 2.0, 2.1)
            assertEquals(3L, fenced.discardedBytes)
            assertEquals(0, fenced.pendingBytes)
        }
        assertFailsWith<IllegalArgumentException> { ControllerDecodeResult(emptyList(), -1, 0) }
        assertFailsWith<IllegalArgumentException> { ControllerDecodeResult(emptyList(), 0, -1) }
        assertFailsWith<IllegalArgumentException> { ControllerRejectedReply(ControllerRequest.identity, " ") }
    }

    @Test
    fun telemetryRejectedReplyFences() {
        val identity =
            hex(telemetryFixture("identity")["cases"].list().first { !it["valid"].bool() }["payloadHex"].text())
        val telemetry = hex(protocolFixture()["telemetry"].list().last()["payloadHex"].text())
        telemetry[1] = 0
        val valid = hex(protocolFixture()["telemetry"].list().last()["frameHex"].text())
        val session = readySession(linkCases().first())
        val generation = session.state.generation
        assertEquals(
            TelemetryTransportAction.none,
            session.notifications(generation, ControllerRequest.telemetry, controllerFrame(identity), 1.0, 1.1).action,
        )
        assertEquals(TelemetryStage.ready, session.state.stage)
        val accepted =
            session.notifications(generation, ControllerRequest.telemetry, valid + controllerFrame(telemetry), 1.0, 1.2)
        assertEquals(TelemetryTransportAction.replyAccepted, accepted.action)
        assertIs<ControllerReadingReply>(accepted.replies.single())
        assertEquals(TelemetryStage.ready, session.state.stage)
        val late =
            session.notifications(generation, ControllerRequest.telemetry, controllerFrame(telemetry), 2.0, 4.500001)
        assertEquals(TelemetryTransportAction.replyExpired, late.action)
        assertTrue(late.replies.isEmpty())
        val handshake = TelemetrySession(CycControllerDecoder()).also { identityStage(it) }
        val expired =
            handshake.notifications(
                handshake.state.generation,
                ControllerRequest.identity,
                controllerFrame(identity),
                19.0,
                20.0,
            )
        assertEquals(TelemetryTransportAction.attemptTimeout, expired.action)
        assertTrue(expired.replies.isEmpty())
        val rejected = CycControllerDecoder().feed(controllerFrame(telemetry) + controllerFrame(identity), null).replies
        assertEquals(listOf(ControllerRequest.telemetry, ControllerRequest.identity), rejected.map { it.kind })
        assertEquals(listOf("telemetryMask", "identity"), rejected.map { assertIs<ControllerRejectedReply>(it).reason })
        assertTrue(CycControllerDecoder().feed(controllerFrame(byteArrayOf(4)), null).replies.isEmpty())
        assertTrue(
            CycControllerDecoder().feed(hex(protocolFixture()["framing"]["corrupted"].text()), null).replies.isEmpty()
        )
    }

    @Test
    fun telemetryNotificationFragmentLifetime() {
        for (expired in listOf(false, true)) for (case in linkCases()) {
            val session = readySession(case)
            val generation = session.state.generation
            val first = case.readingBytes.copyOfRange(0, 3)
            val rest = case.readingBytes.copyOfRange(3, case.readingBytes.size)
            assertTrue(
                session.notifications(generation, ControllerRequest.telemetry, first, 1.0, 1.0).replies.isEmpty()
            )
            val now = if (expired) 3.500001 else 3.5
            val completed = session.notifications(generation, ControllerRequest.telemetry, rest, 3.0, now)
            assertEquals(if (expired) 0 else 1, completed.replies.size)
        }
    }

    @Test
    fun telemetryNotificationGenerationAndReset() {
        for (case in linkCases()) {
            val session = readySession(case)
            val old = session.state.generation
            assertTrue(
                session
                    .notifications(
                        old,
                        ControllerRequest.telemetry,
                        case.readingBytes.copyOfRange(0, 3),
                        1.0,
                        1.0,
                    )
                    .replies
                    .isEmpty()
            )
            session.manualDisconnect(false)
            assertNull(session.identity)
            identityStage(session, 2.0)
            val generation = session.state.generation
            assertTrue(
                session.notifications(old, ControllerRequest.identity, case.identityBytes, 2.0, 2.1).replies.isEmpty()
            )
            val split = case.identityBytes.size / 2
            assertTrue(
                session
                    .notifications(
                        generation,
                        ControllerRequest.identity,
                        case.identityBytes.copyOfRange(0, split),
                        2.0,
                        2.2,
                    )
                    .replies
                    .isEmpty()
            )
            assertTrue(
                session.notifications(old, ControllerRequest.identity, case.identityBytes, 2.0, 2.3).replies.isEmpty()
            )
            assertEquals(
                TelemetryTransportAction.replyAccepted,
                session
                    .notifications(
                        generation,
                        ControllerRequest.identity,
                        case.identityBytes.copyOfRange(split, case.identityBytes.size),
                        2.0,
                        2.4,
                    )
                    .action,
            )
            assertEquals(case.model, session.identity!!.controllerModel)
        }
    }

    @Test
    fun telemetryNotificationReplyFences() {
        for (case in linkCases()) {
            val session = readySession(case)
            val generation = session.state.generation
            assertEquals(
                TelemetryTransportAction.replyAccepted,
                session.notifications(generation, ControllerRequest.telemetry, case.readingBytes, 1.0, 3.5).action,
            )
            assertEquals(
                TelemetryTransportAction.replyExpired,
                session.notifications(generation, ControllerRequest.telemetry, case.readingBytes, 4.0, 6.500001).action,
            )
            assertNull(session.identity)
            session.disconnected(generation, 7.0)
            identityStage(session, 10.0)
            val next = session.state.generation
            assertEquals(
                TelemetryTransportAction.attemptTimeout,
                session.notifications(next, ControllerRequest.identity, case.identityBytes, 29.0, 30.0).action,
            )
            assertNull(session.identity)
            session.disconnected(next, 30.1)
            identityStage(session, 31.0)
            session.scheduleRetry(TelemetryRetryPlan(1.0, false, true), 31.1)
            assertTrue(
                session
                    .notifications(
                        session.state.generation,
                        ControllerRequest.identity,
                        case.identityBytes,
                        31.0,
                        31.2,
                    )
                    .replies
                    .isEmpty()
            )
            assertNull(session.identity)
        }
    }

    @Test
    fun telemetryCatalogRejections() {
        assertFailsWith<IllegalArgumentException> { MetricCatalog.metric("unknown") }
        for (value in listOf(1.0, null, Double.NaN)) assertFailsWith<IllegalArgumentException> {
            MetricCatalog.format("unknown", value)
        }
    }
}
