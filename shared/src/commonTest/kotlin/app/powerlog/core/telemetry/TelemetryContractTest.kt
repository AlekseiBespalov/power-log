package app.powerlog.core.telemetry

import app.powerlog.core.analytics.J
import app.powerlog.core.analytics.fixture
import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.cyc.*
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

internal fun evidence(
    id: Int,
    sequence: Long,
    acquired: Double,
    ride: String = telemetryFixture("admission")["rideA"].text(),
): TelemetryCaptureEvidence {
    val f = telemetryFixture("admission")
    return TelemetryCaptureEvidence(
        ride,
        "50000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
        f["session"].text(),
        f["epoch"].text(),
        f["connection"].text(),
        sequence,
        Timestamps.parse("2026-01-01T00:00:00.123456789Z"),
        acquired,
        acquired - 90,
        telemetry(),
    )
}

internal fun retainedEvidence(row: J): TelemetryCaptureEvidence {
    val profile = row["identity"]
    val identity =
        CycProtocol.restoreIdentity(
            profile["controllerModel"].text(),
            profile["firmwareLabel"].text(),
            profile["controllerProtocol"].text(),
        )
    return TelemetryCaptureEvidence(
        row["rideId"].text(),
        row["observationId"].text(),
        row["captureSessionId"].text(),
        row["clockEpoch"].text(),
        row["connectionEpoch"].text(),
        row["sequence"].long(),
        Timestamps.parse(row["timestamp"].text()),
        row["acquisitionMonotonic"].double(),
        row["sourceElapsedSeconds"].double(),
        CycProtocol.restoreReading(row["metrics"].fields().mapValues { it.value.double() }, identity),
        row["clockDiscontinuitySeconds"].optionalDouble(),
    )
}

class TelemetryContractTest {
    @Test
    fun telemetryProtocolSeam() {
        exerciseControllerSessions()
    }

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
            checks = 0
            val restored =
                TelemetryRestoration.restore(
                    identity.controllerModel,
                    identity.firmwareLabel,
                    identity.controllerProtocol,
                    readings.last().metrics.mapKeys { it.key.name },
                )
            assertEquals(readings.last(), restored.reading)
            assertEquals(1, checks)
        } finally {
            CycProtocol.identityCheckObserverForTesting = null
        }
    }

    @Test
    fun telemetryNeutralReadingRehydration() {
        fun reopen(): TelemetryCaptureEvidence {
            val row = telemetryFixture("reading")["original"]
            val profile = row["identity"]
            val capabilities = profile["capabilities"]
            val decoder =
                JsonLineDecoder(
                    capabilities["suppliedMetrics"].list().map { TelemetryMetric.valueOf(it.text()) }.toSet()
                )
            val reading =
                TelemetryRestoration.restore(
                        profile["controllerModel"].text(),
                        profile["firmwareLabel"].text(),
                        profile["controllerProtocol"].text(),
                        row["metrics"].fields().mapValues { it.value.double() },
                        listOf(decoder),
                    )
                    .reading!!
            return TelemetryCaptureEvidence(
                row["rideId"].text(),
                row["observationId"].text(),
                row["captureSessionId"].text(),
                row["clockEpoch"].text(),
                row["connectionEpoch"].text(),
                row["sequence"].long(),
                Timestamps.parse(row["timestamp"].text()),
                row["acquisitionMonotonic"].double(),
                row["sourceElapsedSeconds"].double(),
                reading,
            )
        }
        val original = reopen()
        val incoming = reopen()
        assertNotSame(original, incoming)
        assertNotSame(original.telemetry, incoming.telemetry)
        assertNotSame(original.telemetry.identity, incoming.telemetry.identity)
        assertEquals(2.6, incoming.telemetry.value(TelemetryMetric.controllerSpeedMps))
        val live = TelemetryCaptureAdmission.admit(TelemetryCaptureState(original.rideId), original, null).next
        val replay = TelemetryCaptureAdmission.admit(live, incoming, reopen())
        assertEquals(TelemetryCaptureOutcome.replay, replay.outcome)
        assertEquals(10.0, replay.next.age(110.0))
        assertNull(
            TelemetryCaptureAdmission.admit(TelemetryCaptureState(original.rideId), reopen(), reopen()).next.latest
        )
        val conflict =
            reopen().let {
                it.copy(
                    telemetry =
                        TelemetryReading(
                            it.telemetry.metrics +
                                (TelemetryMetric.humanPowerW to telemetryFixture("reading")["changedPower"].double()),
                            it.telemetry.identity,
                        )
                )
            }
        assertEquals(
            TelemetryCaptureOutcome.conflict,
            TelemetryCaptureAdmission.admit(live, conflict, reopen()).outcome,
        )
    }

    @Test
    fun persistedTelemetryDispatchesToRegisteredDecoder() {
        val f = telemetryFixture("session")
        val decoder = JsonLineDecoder()
        val identity =
            assertIs<ControllerIdentityReply>(
                    decoder.feed(f["identityNotification"].text().encodeToByteArray(), null).replies.single()
                )
                .identity
        val reading =
            assertIs<ControllerReadingReply>(
                    decoder.feed(f["telemetryNotification"].text().encodeToByteArray(), identity).replies.single()
                )
                .reading
        val metrics = reading.metrics.mapKeys { it.key.name }
        val restored =
            TelemetryRestoration.restore(
                identity.controllerModel,
                identity.firmwareLabel,
                identity.controllerProtocol,
                metrics,
                listOf(decoder),
            )
        assertEquals(identity, restored.identity)
        assertEquals(reading, restored.reading)
        assertNotSame(identity, restored.identity)
        assertEquals(
            identity,
            TelemetryRestoration.restore(
                    identity.controllerModel,
                    identity.firmwareLabel,
                    identity.controllerProtocol,
                    null,
                    listOf(decoder),
                )
                .identity,
        )
        for (protocol in listOf(identity.controllerProtocol, "unknown-protocol")) {
            assertEquals(
                TelemetryRejection.identity,
                assertFailsWith<TelemetryException> {
                        TelemetryRestoration.restore(
                            identity.controllerModel,
                            identity.firmwareLabel,
                            protocol,
                            metrics,
                        )
                    }
                    .reason,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryRestoration.restore(
                identity.controllerModel,
                identity.firmwareLabel,
                identity.controllerProtocol,
                emptyMap(),
                listOf(decoder),
            )
        }
        val original = telemetry()
        val profile = original.identity!!
        assertEquals(
            original,
            TelemetryRestoration.restore(
                    profile.controllerModel,
                    profile.firmwareLabel,
                    profile.controllerProtocol,
                    original.metrics.mapKeys { it.key.name },
                )
                .reading,
        )
        assertEquals(
            profile,
            TelemetryRestoration.restore(
                    profile.controllerModel,
                    profile.firmwareLabel,
                    profile.controllerProtocol,
                    null,
                )
                .identity,
        )
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
            val raw = hex(telemetryFixture("wire-metrics")["payloadHex"].text())
            val sample = CycProtocol.withIdentity(CycProtocol.telemetry(raw), identity)
            assertEquals(case["speedRaw"].double(), sample.value(TelemetryMetric.speedRaw))
            assertEquals(case["speedMps"].optionalDouble(), sample.value(TelemetryMetric.controllerSpeedMps))
        }
        val tailCases =
            telemetryFixture("identity")["cases"].list().filter { it["valid"].bool() && it["model"].text() == "X6" }
        val opaque = tailCases.takeLast(2).map { CycProtocol.identity(hex(it["payloadHex"].text())) }
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
    fun telemetryCaptureAdmissionFixtures() {
        val f = telemetryFixture("admission")
        var state = TelemetryCaptureState(f["rideA"].text())
        val originals = mutableMapOf<String, TelemetryCaptureEvidence>()
        for (case in f["cases"].list()) {
            case["adopt"].optionalText()?.let {
                state = state.adopt(f[it].text())
                assertNull(state.latest)
            }
            val original =
                evidence(case["id"].long().toInt(), case["sequence"].long(), case["acquiredAt"].double(), state.rideId)
            val decision = TelemetryCaptureAdmission.admit(state, original, originals[original.observationId])
            assertEquals(case["expected"].text(), decision.outcome.name)
            if (decision.commitOriginal) originals[original.observationId] = original
            state = decision.next
            assertEquals(case["age"].double(), state.age(case["deliveredAt"].double()))
            assertEquals(state, state.adopt(state.rideId))
        }
        assertEquals(3, originals.size)
        assertNull(TelemetryCaptureState(state.rideId).latest)
    }

    @Test
    fun telemetryCaptureImmutableEvidence() {
        val a = evidence(1, 5, 100.0)
        val state = TelemetryCaptureState(a.rideId, a)
        assertEquals(TelemetryCaptureOutcome.replay, TelemetryCaptureAdmission.admit(state, a, a).outcome)
        val changed =
            listOf(
                a.copy(acquisitionMonotonic = 110.0),
                a.copy(sequence = 6),
                a.copy(timestamp = Timestamps.parse("2026-01-01T00:00:01Z")),
                a.copy(
                    telemetry =
                        CycProtocol.withIdentity(
                            CycProtocol.telemetry(hex(telemetryFixture("wire-metrics")["payloadHex"].text())),
                            a.telemetry.identity!!,
                        )
                ),
                a.copy(connectionEpoch = "40000000-0000-4000-8000-000000000002"),
            )
        for (value in changed) assertEquals(
            TelemetryCaptureOutcome.conflict,
            TelemetryCaptureAdmission.admit(state, value, a).outcome,
        )
        val reconnected = evidence(2, 6, 101.0).copy(connectionEpoch = "40000000-0000-4000-8000-000000000002")
        assertEquals(
            TelemetryCaptureOutcome.admitted,
            TelemetryCaptureAdmission.admit(state, reconnected, null).outcome,
        )
        assertEquals(
            TelemetryCaptureOutcome.older,
            TelemetryCaptureAdmission.admit(state, reconnected.copy(sequence = 1), null).outcome,
        )
        assertEquals(
            TelemetryCaptureOutcome.wrongEpoch,
            TelemetryCaptureAdmission.admit(
                    state,
                    reconnected.copy(clockEpoch = "30000000-0000-4000-8000-000000000002"),
                    null,
                )
                .outcome,
        )
        assertEquals(
            TelemetryCaptureOutcome.wrongRide,
            TelemetryCaptureAdmission.admit(
                    state,
                    reconnected.copy(rideId = telemetryFixture("admission")["rideB"].text()),
                    null,
                )
                .outcome,
        )
        assertNull(state.age(Double.NaN))
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            if (bad != 0.0) assertFailsWith<TelemetryException> { a.copy(acquisitionMonotonic = bad) }
            assertFailsWith<TelemetryException> { a.copy(sequence = bad.toLong().coerceAtMost(0)) }
        }
        assertFailsWith<TelemetryException> { a.copy(observationId = "physical-device-id") }
        assertFailsWith<TelemetryException> { a.copy(timestamp = a.timestamp.copy(comparisonUs = 0)) }
        assertFailsWith<TelemetryException> { a.copy(timestamp = a.timestamp.copy(original = "invalid")) }
        assertFailsWith<TelemetryException> {
            a.copy(telemetry = CycProtocol.telemetry(hex(telemetryFixture("wire-metrics")["payloadHex"].text())))
        }
    }

    @Test
    fun telemetryCaptureClockDiscontinuity() {
        assertNull(TelemetryCaptureAdmission.discontinuity(0.0, 1.25, 100.0, 101.0))
        assertNull(TelemetryCaptureAdmission.discontinuity(0.0, 0.75, 100.0, 101.0))
        assertEquals(0.250001, TelemetryCaptureAdmission.discontinuity(0.0, 1.250001, 100.0, 101.0)!!, 1e-10)
        assertEquals(-0.250001, TelemetryCaptureAdmission.discontinuity(0.0, 0.749999, 100.0, 101.0)!!, 1e-10)
        assertFailsWith<IllegalArgumentException> { TelemetryCaptureAdmission.discontinuity(Double.NaN, 0.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { TelemetryCaptureAdmission.discontinuity(0.0, 0.0, 1.0, 0.0) }
    }

    @Test
    fun telemetryRetainedCaptureFixture() {
        val original = retainedEvidence(telemetryFixture("admission")["retainedOriginal"])
        val reopened = retainedEvidence(telemetryFixture("admission")["retainedOriginal"])
        assertNotSame(original, reopened)
        assertNotSame(original.telemetry, reopened.telemetry)
        assertNotSame(original.telemetry.identity, reopened.telemetry.identity)
        assertEquals(original, reopened)
        assertEquals(9007199254740993L, reopened.sequence)
        val state = TelemetryCaptureAdmission.admit(TelemetryCaptureState(original.rideId), original, null).next
        assertEquals(
            TelemetryCaptureOutcome.replay,
            TelemetryCaptureAdmission.admit(
                    state,
                    reopened,
                    retainedEvidence(telemetryFixture("admission")["retainedOriginal"]),
                )
                .outcome,
        )
        assertEquals(10.0, state.age(110.0))
        val restarted = TelemetryCaptureState(original.rideId)
        val replay =
            TelemetryCaptureAdmission.admit(
                restarted,
                reopened,
                retainedEvidence(telemetryFixture("admission")["retainedOriginal"]),
            )
        assertEquals(TelemetryCaptureOutcome.replay, replay.outcome)
        assertNull(replay.next.latest)
        val values =
            telemetryFixture("admission")["retainedOriginal"]["metrics"].fields().mapValues { it.value.double() }
        val identity = reopened.telemetry.identity!!
        val changed = reopened.copy(telemetry = CycProtocol.restoreReading(values + ("humanPowerW" to 2.0), identity))
        assertEquals(
            TelemetryCaptureOutcome.conflict,
            TelemetryCaptureAdmission.admit(
                    state,
                    changed,
                    retainedEvidence(telemetryFixture("admission")["retainedOriginal"]),
                )
                .outcome,
        )
        for (bad in
            listOf(
                values - "cadenceRpm",
                values + ("serial" to 1.0),
                values + ("humanPowerW" to 1.5),
                values + ("faultCode" to 256.0),
                values + ("motorTempC" to 3276.8),
                values + ("batteryVoltageV" to 54.20001),
                values + ("motorInputPowerW" to 1.0),
                values + ("controllerSpeedMps" to 0.0),
                values + ("humanPowerW" to Double.NaN),
                values + ("batteryCurrentA" to Double.POSITIVE_INFINITY),
            )) assertFailsWith<CycException> { CycProtocol.restoreReading(bad, identity) }
        for (protocol in listOf("05.3", "5", "-1.3", "256.3", "5.256", "5.3 serial")) assertFailsWith<CycException> {
            CycProtocol.restoreIdentity("X6", "20250725", protocol)
        }
        for (model in listOf("X60", "X6 serial", "X6\\u0000")) assertFailsWith<CycException> {
            CycProtocol.restoreIdentity(model, "20250725", "5.3")
        }
        assertFailsWith<CycException> { CycProtocol.restoreIdentity("X6", "20250725 private", "5.3") }
        val unknown = CycProtocol.restoreIdentity("X6_suffix", "20250725", "5.3")
        assertFalse(unknown.hasKnownSpeedUnit)
        assertNull(
            CycProtocol.restoreReading(values - "controllerSpeedMps", unknown).value(TelemetryMetric.controllerSpeedMps)
        )
        assertFailsWith<CycException> { CycProtocol.restoreReading(values, unknown) }
        val outliers =
            CycProtocol.withIdentity(
                CycProtocol.telemetry(hex(telemetryFixture("wire-metrics")["payloadHex"].text())),
                identity,
            )
        val restored =
            CycProtocol.restoreReading(
                identity.capabilities.suppliedMetrics.associate { it.name to outliers.value(it)!! },
                identity,
            )
        assertEquals(outliers, restored)
    }

    @Test
    fun telemetryMetricDeclarationFixtures() {
        val f = telemetryFixture("metrics")
        assertEquals(f["metrics"].list().size, MetricCatalog.all.size)
        for (row in f["metrics"].list()) {
            val m = MetricCatalog.metric(row["id"].text())
            assertEquals(row["description"].optionalText(), m.description)
            assertEquals(row["label"].text(), m.label)
            assertEquals(row["shortLabel"].text(), m.shortLabel)
            assertEquals(row["group"].text(), m.group.name)
            assertEquals(row["kind"].optionalText(), m.kind?.name)
            assertEquals(if (row["zero"] == J.Null) null else row["zero"].bool(), m.zero)
            assertEquals(row["semantics"].optionalText(), m.semantics?.name)
            assertEquals(row["unit"].text(), m.unit)
            assertEquals(row["color"].text(), m.color)
            assertEquals(row["decimals"].long().toInt(), m.decimals)
            assertEquals(row["gapSeconds"].double(), m.gapSeconds)
            assertEquals(row["scale"].double(), m.scale)
            assertEquals(row["text"].text(), MetricCatalog.format(m.id, f["value"].double()))
            for (missing in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertEquals(
                f["unavailableText"].text(),
                MetricCatalog.format(m.id, missing),
            )
        }
        for (case in f["formatCases"].list()) assertEquals(
            case["expected"].text(),
            MetricCatalog.format(case["id"].text(), case["value"].double()),
        )
        for (metric in TelemetryMetric.entries) assertEquals(metric.name, MetricCatalog.metric(metric.name).id)
    }

    @Test
    fun telemetryTransportTimeoutFixtures() {
        val f = telemetryFixture("transport")
        for (case in f["timeouts"].list()) {
            assertEquals(case["expired"].bool(), TelemetryTiming.replyExpired(0.0, case["now"].double()))
            assertEquals(case["expired"].bool(), TelemetryTiming.writeExpired(0.0, case["now"].double()))
        }
        for (case in f["fragmentCases"].list()) assertEquals(
            case["expired"].bool(),
            TelemetryTiming.fragmentExpired(0.0, case["now"].double()),
        )
        assertEquals(f["displayHoldSeconds"].double(), TelemetryTiming.displayHoldSeconds)
        assertTrue(TelemetryTiming.replyAdmissible(0.0, 2.5))
        assertFalse(TelemetryTiming.replyAdmissible(0.0, 2.500001))
        assertFalse(TelemetryTiming.replyAdmissible(1.0, 0.0))
        val ready = TelemetryTransportState(1, TelemetryStage.ready)
        assertEquals(TelemetryTransportAction.responseTimeout, TelemetryTransport.check(ready, 2.5, 0.0).action)
        assertEquals(
            TelemetryTransportAction.writeTimeout,
            TelemetryTransport.check(ready, 2.5, writeBlockedAt = 0.0).action,
        )
        assertEquals(TelemetryTransportAction.none, TelemetryTransport.check(ready, 2.499999, 0.0, 0.0).action)
    }

    @Test
    fun telemetryRetryFixtures() {
        for (case in telemetryFixture("transport")["retries"].list()) {
            val plan =
                TelemetryReconnectPolicy.plan(
                    case["attempt"].long().toInt(),
                    case["active"].bool(),
                    case["completed"].bool(),
                    case["peer"].bool(),
                    case["stable"].optionalDouble(),
                    case["systemReconnect"].bool(),
                    false,
                    true,
                )
            assertEquals(case["delay"].optionalDouble(), plan?.delaySeconds)
            if (plan != null)
                assertEquals(case["systemReconnect"].bool() && plan.delaySeconds == 0.0, plan.adoptSystemReconnect)
        }
        for (complete in listOf(false, true)) for (system in listOf(false, true)) for (pending in
            listOf(false, true)) for (disconnected in listOf(false, true)) {
            val plan = TelemetryReconnectPolicy.plan(1, false, complete, false, null, system, pending, disconnected)!!
            assertEquals(system || pending || (!complete && !disconnected), plan.cancelBeforeRetry)
        }
        for (stable in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertEquals(
            1.0,
            TelemetryReconnectPolicy.plan(1, false, true, true, stable, false, false, true)!!.delaySeconds,
        )
    }

    @Test
    fun telemetryReplyAdmissionFences() {
        val ready = TelemetryTransportState(1, TelemetryStage.ready)
        assertEquals(
            TelemetryTransportAction.replyAccepted,
            TelemetryTransport.reply(
                    ready,
                    1,
                    ControllerRequest.telemetry,
                    ControllerReadingReply(telemetry()),
                    0.0,
                    2.5,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.replyExpired,
            TelemetryTransport.reply(
                    ready,
                    1,
                    ControllerRequest.telemetry,
                    ControllerReadingReply(telemetry()),
                    0.0,
                    2.500001,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.reply(
                    ready,
                    0,
                    ControllerRequest.telemetry,
                    ControllerReadingReply(telemetry()),
                    0.0,
                    2.0,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.reply(
                    ready,
                    1,
                    ControllerRequest.telemetry,
                    ControllerIdentityReply(telemetry().identity!!),
                    0.0,
                    2.0,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.reply(
                    ready,
                    1,
                    ControllerRequest.identity,
                    ControllerIdentityReply(telemetry().identity!!),
                    0.0,
                    2.0,
                )
                .action,
        )
        val identity = TelemetryTransportState(1, TelemetryStage.identity, 20.0)
        assertEquals(
            TelemetryTransportAction.replyAccepted,
            TelemetryTransport.reply(
                    identity,
                    1,
                    ControllerRequest.identity,
                    ControllerIdentityReply(telemetry().identity!!),
                    19.0,
                    19.999999,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.attemptTimeout,
            TelemetryTransport.reply(
                    identity,
                    1,
                    ControllerRequest.identity,
                    ControllerIdentityReply(telemetry().identity!!),
                    19.0,
                    20.0,
                )
                .action,
        )
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.reply(
                    identity,
                    1,
                    ControllerRequest.telemetry,
                    ControllerReadingReply(telemetry()),
                    19.0,
                    19.5,
                )
                .action,
        )
        val cancel = identity.copy(cancellationGeneration = 1)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.reply(
                    cancel,
                    1,
                    ControllerRequest.identity,
                    ControllerIdentityReply(telemetry().identity!!),
                    19.0,
                    19.5,
                )
                .action,
        )
    }

    @Test
    fun telemetryDiscoveryDeadlineFixtures() {
        val f = telemetryFixture("deadlines")
        val window = TelemetryDiscovery.begin(1, f["attemptStart"].double())
        for (case in f["scanTimes"].list()) {
            assertEquals(case["accept"].bool(), window.accepts(1, case["at"].double()))
            assertEquals(!case["accept"].bool(), window.expired(case["at"].double()))
            assertFalse(window.accepts(2, case["at"].double()))
        }
    }

    @Test
    fun telemetryAttemptDeadlineFixtures() {
        val f = telemetryFixture("deadlines")
        var state = TelemetryTransport.begin(TelemetryTransportState(), f["attemptStart"].double())!!
        val g = state.generation
        for (stage in f["stages"].list()) {
            val decision =
                TelemetryTransport.advance(
                    state,
                    g,
                    TelemetryStage.valueOf(stage["from"].text()),
                    TelemetryStage.valueOf(stage["to"].text()),
                    stage["at"].double(),
                )
            assertEquals(TelemetryTransportAction.advanced, decision.action)
            state = decision.next
            assertEquals(f["attemptDeadline"].double(), state.attemptDeadline)
        }
        for (late in f["lateCompletions"].list()) {
            val decision =
                TelemetryTransport.advance(
                    state,
                    g,
                    TelemetryStage.characteristics,
                    TelemetryStage.notifications,
                    late.double(),
                )
            assertEquals(TelemetryTransportAction.attemptTimeout, decision.action)
            assertEquals(TelemetryStage.idle, decision.next.stage)
            assertEquals(
                TelemetryTransportAction.none,
                TelemetryTransport.advance(
                        decision.next,
                        g,
                        TelemetryStage.identity,
                        TelemetryStage.ready,
                        f["lateCallbackAt"].double(),
                    )
                    .action,
            )
        }
        state =
            TelemetryTransport.advance(state, g, TelemetryStage.characteristics, TelemetryStage.notifications, 19.1)
                .next
        state = TelemetryTransport.advance(state, g, TelemetryStage.notifications, TelemetryStage.identity, 19.2).next
        assertEquals(
            TelemetryTransportAction.attemptTimeout,
            TelemetryTransport.advance(state, g, TelemetryStage.identity, TelemetryStage.ready, 20.0).action,
        )
        val ready =
            TelemetryTransport.advance(
                state,
                g,
                TelemetryStage.identity,
                TelemetryStage.ready,
                f["beforeDeadline"].double(),
            )
        assertEquals(TelemetryTransportAction.advanced, ready.action)
        assertNull(ready.next.attemptDeadline)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.advance(state, g - 1, TelemetryStage.identity, TelemetryStage.ready, 19.3).action,
        )
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.advance(state, g, TelemetryStage.identity, TelemetryStage.services, 19.3).action,
        )
    }

    @Test
    fun telemetryCancellationFenceFixtures() {
        val f = telemetryFixture("deadlines")
        val begun = TelemetryTransport.begin(TelemetryTransportState(), 0.0)!!
        val timedOut = TelemetryTransport.check(begun, f["retryErrorAt"].double())
        assertEquals(TelemetryTransportAction.attemptTimeout, timedOut.action)
        assertNull(TelemetryTransport.begin(timedOut.next, 20.0))
        val plan = TelemetryReconnectPolicy.plan(1, false, false, false, null, false, false, false)!!
        val retry = TelemetryTransport.scheduleRetry(timedOut.next, plan, f["retryErrorAt"].double())
        assertEquals(f["reconnectDue"].double(), retry.reconnectDue)
        assertEquals(f["cancellationDeadline"].double(), retry.cancellationDeadline)
        assertEquals(retry, TelemetryTransport.scheduleRetry(retry, plan, 21.0))
        assertNull(TelemetryTransport.begin(retry, 21.0))
        assertEquals(TelemetryTransportAction.none, TelemetryTransport.check(retry, 21.0).action)
        assertEquals(retry, TelemetryTransport.disconnected(retry, begun.generation + 100, 22.0))
        val completed = TelemetryTransport.disconnected(retry, begun.generation, f["cancellationCompletedAt"].double())
        assertEquals(TelemetryTransportAction.beginRetry, TelemetryTransport.check(completed, 22.0).action)
        val next = TelemetryTransport.begin(completed, 22.0)!!
        assertEquals(42.0, next.attemptDeadline)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.advance(
                    next,
                    begun.generation,
                    TelemetryStage.connecting,
                    TelemetryStage.services,
                    f["lateCallbackAt"].double(),
                )
                .action,
        )
        val failed = TelemetryTransport.check(retry, f["missingCancellationAt"].double())
        assertEquals(TelemetryTransportAction.cancellationTimeout, failed.action)
        assertNull(TelemetryTransport.begin(failed.next, f["lateCallbackAt"].double()))
        assertNull(
            TelemetryTransport.disconnected(failed.next, begun.generation, f["lateCallbackAt"].double()).reconnectDue
        )
        assertTrue(TelemetryTransport.disconnected(retry, begun.generation, 26.0).cancellationFailed)
        val manual = TelemetryTransport.manualDisconnect(retry, true)
        assertNull(manual.reconnectDue)
        assertNull(TelemetryTransport.begin(manual, f["lateCallbackAt"].double()))
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.advance(
                    manual,
                    begun.generation,
                    TelemetryStage.identity,
                    TelemetryStage.ready,
                    f["lateCallbackAt"].double(),
                )
                .action,
        )
        val stopped = TelemetryTransport.disconnected(manual, begun.generation, f["lateCallbackAt"].double())
        assertFalse(stopped.cancellationPending)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.check(stopped, f["lateCallbackAt"].double()).action,
        )
    }

    @Test
    fun telemetryBackoffCancellationFixture() {
        val f = telemetryFixture("deadlines")
        val begun = TelemetryTransport.begin(TelemetryTransportState(), f["attemptStart"].double())!!
        val timedOut = TelemetryTransport.check(begun, f["retryErrorAt"].double()).next
        val plan = TelemetryReconnectPolicy.plan(1, false, false, false, null, false, false, false)!!
        val retry = TelemetryTransport.scheduleRetry(timedOut, plan, f["retryErrorAt"].double())
        val completed = TelemetryTransport.disconnected(retry, begun.generation, f["unexpectedReconnectAt"].double())
        val unexpected = TelemetryReconnectPolicy.plan(2, false, true, true, null, true, true, false)!!
        val fenced = TelemetryTransport.scheduleRetry(completed, unexpected, f["unexpectedReconnectAt"].double())
        assertTrue(fenced.cancellationPending)
        assertEquals(f["reconnectDue"].double(), fenced.reconnectDue)
        assertEquals(f["cancellationDeadline"].double(), fenced.cancellationDeadline)
        assertEquals(TelemetryTransportAction.none, TelemetryTransport.check(fenced, f["reconnectDue"].double()).action)
        assertNull(TelemetryTransport.begin(fenced, f["reconnectDue"].double()))
        assertEquals(fenced, TelemetryTransport.disconnected(fenced, begun.generation, f["reconnectDue"].double()))
        val closed =
            TelemetryTransport.disconnected(fenced, completed.generation, f["cancellationCompletedAt"].double())
        assertEquals(
            TelemetryTransportAction.beginRetry,
            TelemetryTransport.check(closed, f["cancellationCompletedAt"].double()).action,
        )
        assertNotNull(TelemetryTransport.begin(closed, f["cancellationCompletedAt"].double()))
    }

    @Test
    fun telemetryRawWallClockFixture() {
        for (row in telemetryFixture("admission")["clockCases"].list()) {
            assertEquals(
                row["expected"].optionalDouble(),
                TelemetryCaptureAdmission.discontinuity(
                    row["firstWall"].double(),
                    row["lastWall"].double(),
                    row["previousMonotonic"].double(),
                    row["monotonic"].double(),
                ),
            )
        }
    }

    @Test
    fun attemptExpiryRetainsSystemConnectionUntilCancellation() {
        val begun = TelemetryTransport.begin(TelemetryTransportState(), 0.0, systemInitiated = true)!!
        val expired = TelemetryTransport.check(begun, 20.0)
        assertEquals(TelemetryTransportAction.attemptTimeout, expired.action)
        assertTrue(expired.next.systemInitiated, "attempt expiry retains pending system ownership")
        val plan =
            TelemetryReconnectPolicy.plan(1, true, false, false, null, false, expired.next.systemInitiated, true)!!
        assertTrue(plan.cancelBeforeRetry)
        val retry = TelemetryTransport.scheduleRetry(expired.next, plan, 20.0)
        assertEquals(21.0, retry.reconnectDue)
        assertEquals(26.0, retry.cancellationDeadline)
        assertTrue(retry.cancellationPending)
        assertFalse(retry.systemInitiated)
        assertEquals(TelemetryTransportAction.none, TelemetryTransport.check(retry, 21.0).action)
        val closed = TelemetryTransport.disconnected(retry, begun.generation, 22.0)
        assertEquals(TelemetryTransportAction.beginRetry, TelemetryTransport.check(closed, 22.0).action)
    }

    @Test
    fun radioTeardownClearsOverdueCancellationAndRetry() {
        val begun = TelemetryTransport.begin(TelemetryTransportState(), 0.0)!!
        val retry = TelemetryTransport.scheduleRetry(begun, TelemetryRetryPlan(1.0, false, true), 20.0)
        assertEquals(21.0, retry.reconnectDue)
        assertEquals(26.0, retry.cancellationDeadline)
        for (state in listOf(retry, TelemetryTransport.check(retry, 27.0).next)) {
            val cleared = TelemetryTransport.radioUnavailable(state)
            assertTrue(cleared.generation > state.generation)
            assertFalse(cleared.cancellationPending)
            assertFalse(cleared.cancellationFailed)
            assertNull(cleared.reconnectDue)
            assertNull(cleared.cancellationDeadline)
            assertEquals(TelemetryStage.idle, cleared.stage)
            assertEquals(cleared, TelemetryTransport.disconnected(cleared, begun.generation, 28.0))
            assertNotNull(TelemetryTransport.begin(cleared, 27.0))
        }
    }

    @Test
    fun lateCancellationFailureDoesNotDependOnCallbackOrder() {
        val begun = TelemetryTransport.begin(TelemetryTransportState(), 0.0)!!
        val retry = TelemetryTransport.scheduleRetry(begun, TelemetryRetryPlan(1.0, false, true), 20.0)
        val timerFirst =
            TelemetryTransport.disconnected(TelemetryTransport.check(retry, 27.0).next, begun.generation, 27.0)
        val callbackFirst =
            TelemetryTransport.check(TelemetryTransport.disconnected(retry, begun.generation, 27.0), 27.0).next
        assertEquals(timerFirst, callbackFirst)
        assertTrue(callbackFirst.cancellationFailed)
        assertFalse(callbackFirst.cancellationPending)
        assertNull(callbackFirst.reconnectDue)
        assertNull(TelemetryTransport.begin(callbackFirst, 29.0))
    }

    @Test
    fun nativeDisconnectionFencesRetryThroughCancellationDeadline() {
        val begun = TelemetryTransport.begin(TelemetryTransportState(), 0.0)!!
        val retry = TelemetryTransport.scheduleRetry(begun, TelemetryRetryPlan(1.0, false, false), 20.0)
        assertFalse(retry.cancellationPending)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.check(retry, 21.0, nativeDisconnecting = true).action,
        )
        assertEquals(
            TelemetryTransportAction.beginRetry,
            TelemetryTransport.check(retry, 22.0, nativeDisconnecting = false).action,
        )
        val expired = TelemetryTransport.check(retry, 26.0, nativeDisconnecting = true)
        assertEquals(TelemetryTransportAction.cancellationTimeout, expired.action)
        assertTrue(expired.next.cancellationFailed)
        assertNull(expired.next.reconnectDue)
        assertEquals(
            TelemetryTransportAction.none,
            TelemetryTransport.check(expired.next, 27.0, nativeDisconnecting = false).action,
        )
    }

    @Test
    fun telemetryLateCancellationCallbackFixture() {
        val f = telemetryFixture("deadlines")
        val begun = TelemetryTransport.begin(TelemetryTransportState(), f["attemptStart"].double())!!
        val plan = TelemetryReconnectPolicy.plan(1, false, false, false, null, false, false, false)!!
        val retry = TelemetryTransport.scheduleRetry(begun, plan, f["retryErrorAt"].double())
        val callbackAt = f["lateCallbackAt"].double()
        val closed = TelemetryTransport.disconnected(retry, begun.generation, callbackAt)
        val late = f["lateCallbackAt"].double() >= f["cancellationDeadline"].double()
        assertEquals(late, closed.cancellationFailed)
        assertEquals(if (late) null else f["reconnectDue"].double(), closed.reconnectDue)
    }

    @Test
    fun telemetryPollingScheduleFixtures() {
        for (case in telemetryFixture("transport")["polling"].list()) assertEquals(
            case["expected"].double(),
            TelemetryTiming.pollingDelay(
                case["now"].double(),
                case["poweredOn"].bool(),
                case["verified"].bool(),
                case["scanning"].bool(),
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
            ),
        )
        fun delay(cancellation: Boolean, sample: Double?, next: Double? = null) =
            TelemetryTiming.pollingDelay(
                21.0,
                true,
                false,
                false,
                null,
                21.0,
                26.0,
                cancellation,
                null,
                null,
                next,
                sample,
            )
        assertEquals(0.005, delay(false, null))
        assertEquals(5.0, delay(true, 20.0))
        assertEquals(0.1, delay(true, 21.1), 0.000001)
        assertEquals(0.005, delay(true, null, 20.0))
    }
}
