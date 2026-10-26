package app.powerlog.core.presentation

import app.powerlog.core.RideActions
import app.powerlog.core.StreamStatus
import app.powerlog.core.analytics.J
import app.powerlog.core.analytics.fixture
import app.powerlog.core.health.MeasurementSource
import app.powerlog.core.protocol.*
import app.powerlog.core.telemetry.MetricCatalog
import kotlin.test.*

internal fun presentationFixture(name: String): J = fixture("presentation/$name.json")

internal fun J.optionalBool(): Boolean? = if (this == J.Null) null else bool()

internal fun J.json(): Json =
    when (this) {
        is J.Obj -> Obj(fields.mapValues { it.value.json() })
        is J.Arr -> Arr(values.map { it.json() })
        is J.Str -> Str(value)
        is J.Num -> Num(text.toDouble())
        is J.Bool -> Bool(value)
        J.Null -> Nil
    }

internal fun clock(nowUs: Long, epoch: String = "process-a") = PresentationClock(epoch, nowUs, nowUs)

internal fun reading(
    sequence: Long = 1,
    acquisition: Long = 100_000_000,
    original: Long = acquisition,
    epoch: String = "process-a",
    values: Map<String, Double> = mapOf("humanPowerW" to 200.0, "cadenceRpm" to 80.0),
) = LiveReading(LiveAcquisition("observation-$sequence", sequence, original, epoch, acquisition), values)

internal fun liveState(ride: String = presentationFixture("freshness")["rideA"].text()): LivePresentationState {
    var state = LivePresentation.adopt(LivePresentation.empty(), ride)
    state = LivePresentation.observe(state, ride, LiveSource.controller, reading(), clock(100_000_000))
    return LivePresentation.observe(
        state,
        ride,
        LiveSource.heartRate,
        reading(values = mapOf("heartRateBpm" to 150.0)),
        clock(100_000_000),
    )
}

internal val allCapabilities = RideCapabilities(true, true, true, true, true, "appleHealth")

internal fun acquisitionFixtures(section: String) {
    val f = presentationFixture("freshness")
    val cases = f[section].list()
    assertTrue(cases.isNotEmpty())
    for (r in cases) {
        val source = LiveSource.valueOf(r["source"].text())
        val metric =
            when (source) {
                LiveSource.controller -> "humanPowerW"
                LiveSource.heartRate -> "heartRateBpm"
                LiveSource.gps -> "speedMps"
            }
        var state = LivePresentation.adopt(LivePresentation.empty(), f["rideA"].text())
        for (o in r["observations"].list()) {
            val acquired = o["acquiredUs"].long()
            state =
                LivePresentation.observe(
                    state,
                    f["rideA"].text(),
                    source,
                    LiveReading(
                        LiveAcquisition(
                            o["id"].text(),
                            o["sequence"].optionalDouble()?.toLong(),
                            acquired,
                            "process-a",
                            acquired,
                        ),
                        mapOf(metric to o["value"].double()),
                    ),
                    clock(o["deliveredUs"].long()),
                    o["backfilled"].bool(),
                )
        }
        val retained = state.reading(source)!!
        assertEquals(r["expectedId"].text(), retained.acquisition.observationId, r["name"].text())
        assertEquals(r["expectedSequence"].optionalDouble()?.toLong(), retained.acquisition.sequence, r["name"].text())
        assertEquals(r["expectedAcquiredUs"].long(), retained.acquisition.monotonicUs, r["name"].text())
        assertEquals(r["expectedOriginalTimeUs"].long(), retained.acquisition.originalTimeUs, r["name"].text())
        assertEquals(r["expectedValue"].double(), retained.values[metric], r["name"].text())
        val view =
            LivePresentation.view(state, LivePresentationInput("running", gpsAccuracy = 5.0), clock(r["nowUs"].long()))
        val status =
            when (source) {
                LiveSource.controller -> view.controllerStatus
                LiveSource.heartRate -> view.heartStatus
                LiveSource.gps -> view.gpsStatus
            }
        assertEquals(r["status"].text(), status, r["name"].text())
        assertEquals(
            if (status == "stale") null else r["expectedValue"].double(),
            view.values[metric],
            r["name"].text(),
        )
    }
}

class PresentationContractTest {
    @Test fun presentationDuplicateIdentityFixtures() = acquisitionFixtures("duplicates")

    @Test fun presentationUnsequencedAcquisitionFixtures() = acquisitionFixtures("unsequenced")

    @Test
    fun presentationNativeStartContextFixtures() {
        val options = RideOptions.resolve(RequestedRideOptions(useWatch = true), allCapabilities)
        for (r in presentationFixture("start")["cases"].list()) {
            val context =
                RideStartContext(
                    StartSurface.valueOf(r["surface"].text()),
                    r["hasSession"].bool(),
                    r["endingOwner"].bool(),
                    r["recoveredForeignOwner"].bool(),
                    r["unresolvedOwner"].bool(),
                    r["finishing"].bool(),
                    r["busy"].bool(),
                    r["recovering"].bool(),
                    r["deletionProbeInFlight"].bool(),
                )
            val phase = r["phase"].text()
            val reason = RideStarts.rejection(phase, context)
            val action =
                RideActions.admit(
                    ActionPresentationState(),
                    ActionRequest("a", null, RideAction.start),
                    phase,
                    null,
                    startContext = context,
                )
            val readiness = RideOptions.readiness(options, allCapabilities, true, true, phase, context)
            assertEquals(r["admitted"].bool(), reason == null, r["name"].text())
            assertEquals(r["admitted"].bool(), action.admitted, r["name"].text())
            assertEquals(r["admitted"].bool(), readiness.available, r["name"].text())
            assertEquals(reason, action.error, r["name"].text())
            assertEquals(reason, readiness.error, r["name"].text())
        }
    }

    @Test
    fun presentationProviderNameDelegationFixtures() {
        for (r in presentationFixture("formatting")["providers"].list()) {
            val provider = r["provider"].optionalText()
            assertEquals(
                app.powerlog.core.health.ProviderNames.fromWire(provider),
                PresentationFormatting.provider(provider),
            )
            val typed = app.powerlog.core.health.Provider.entries.firstOrNull { it.name == provider } ?: continue
            val source =
                MeasurementSource(app.powerlog.core.health.Source.phone, app.powerlog.core.health.Device.iphone, typed)
            assertEquals(
                app.powerlog.core.health.ProviderNames.displayName(typed),
                app.powerlog.core.health.ProviderNames.label("phone", listOf(source)),
            )
        }
    }

    @Test
    fun presentationSpeedUnitDelegationFixtures() {
        val defaults = Preferences.encode(Preferences.defaults())
        for (r in presentationFixture("formatting")["speedUnits"].list()) {
            val unit = r["unit"].text()
            val text = defaults.replace("\"speedUnit\":\"km/h\"", "\"speedUnit\":\"$unit\"")
            assertEquals(MetricCatalog.acceptsSpeedUnit(unit), runCatching { Preferences.decode(text) }.isSuccess, unit)
            assertEquals(
                MetricCatalog.acceptsSpeedUnit(unit),
                runCatching { MetricCatalog.presentationMetric("speedMps", unit) }.isSuccess,
                unit,
            )
        }
    }

    @Test
    fun presentationDistanceUnitDelegationFixture() {
        val f = presentationFixture("formatting")
        for (r in f["distance"].list()) {
            assertEquals(
                MetricCatalog.metric("distanceMeters").unit,
                PresentationFormatting.distance(1234.56, r["source"].optionalText()).unit,
            )
        }
    }

    @Test
    fun presentationSharedRuleGoldenFixtures() {
        val f = presentationFixture("formatting")
        for (r in f["providers"].list()) assertEquals(
            r["label"].text(),
            PresentationFormatting.provider(r["provider"].optionalText()),
        )
        for (r in f["speedUnits"].list()) assertEquals(
            r["accepted"].bool(),
            MetricCatalog.acceptsSpeedUnit(r["unit"].text()),
        )
        assertEquals(f["distanceUnit"].text(), MetricCatalog.metric("distanceMeters").unit)
    }

    @Test
    fun presentationStartPhaseFixtures() {
        for (r in presentationFixture("actions")["start"].list()) {
            val phase = r["phase"].text()
            for (surface in RideActionSurface.entries) {
                assertEquals(r[surface.name].bool(), RideActions.rejection("start", phase, surface) == null, phase)
            }
            val decision =
                RideActions.admit(ActionPresentationState(), ActionRequest("a", null, RideAction.start), phase, null)
            assertEquals(r["rider"].bool(), decision.admitted, phase)
            if (r["rider"].bool()) {
                assertFalse(
                    RideActions.admit(decision.next, ActionRequest("b", null, RideAction.start), phase, null).admitted
                )
                assertFalse(
                    RideActions.admit(
                            ActionPresentationState(),
                            ActionRequest("a", null, RideAction.start),
                            phase,
                            null,
                            nativePendingAction = "start",
                        )
                        .admitted
                )
            }
        }
    }

    @Test
    fun presentationActivityPhaseFixtures() {
        val f = presentationFixture("activity")
        for (r in f["phases"].list()) {
            val phase =
                ActivityPhases.resolve(
                    ActivityPhaseInput(
                        r["phase"].text(),
                        r["watchOwned"].bool(),
                        r["hasCutoff"].bool(),
                        r["ownerPhase"].optionalText(),
                        r["stopOutcome"].optionalText(),
                        r["verified"].bool(),
                        r["pendingStop"].bool(),
                        r["phoneStopping"].bool(),
                        r["closedWithoutWatch"].bool(),
                    )
                )
            assertEquals(r["expected"].text(), phase, r["name"].text())
            val p =
                ActivityPresentations.project(
                    ActivityInput(f["rideA"].text(), 1, f["token"].text(), phase, null, 10.0),
                    liveState(),
                    clock(105_000_000),
                )
            assertEquals(phase, p.expectedPhase)
            assertEquals(r["status"].text(), p.render(105_000_000, false).status.text, r["name"].text())
        }
    }

    @Test
    fun presentationDistanceOptionFixtures() {
        for (r in presentationFixture("formatting")["distanceOptions"].list()) {
            assertEquals(
                r["label"].text(),
                PresentationFormatting.distanceOptionLabel(r["source"].text(), r["foreground"].bool()),
            )
        }
        assertFailsWith<IllegalArgumentException> { PresentationFormatting.distanceOptionLabel("unknown") }
        assertFailsWith<IllegalArgumentException> { PresentationFormatting.distance(1.0, "auto") }
        assertFailsWith<IllegalArgumentException> { PresentationFormatting.distance(1.0, "unknown") }
    }

    @Test
    fun presentationExactDifferences() {
        for (r in presentationFixture("formatting")["differences"].list()) assertEquals(
            r["text"].text(),
            MetricCatalog.formatDifference(
                r["id"].text(),
                r["a"].optionalDouble(),
                r["b"].optionalDouble(),
                r["aExact"].optionalText(),
                r["bExact"].optionalText(),
                "km/h",
            ),
        )
        assertEquals(
            "9007199254740993",
            MetricCatalog.formatPoint("humanPowerW", 9007199254740992.0, "9007199254740993", "km/h"),
        )
        assertFalse(MetricCatalog.allowsMean("distanceMeters"))
        assertFalse(MetricCatalog.allowsMean("faultCode"))
    }

    @Test
    fun presentationIdentityAndMissingReadingFields() {
        val f = presentationFixture("actions")
        val state = liveState()
        assertEquals(100_000_000L, state.reading(LiveSource.controller)!!.acquisition.monotonicUs)
        assertEquals("observation-1", state.reading(LiveSource.controller)!!.acquisition.observationId)
        assertNull(state.reading(LiveSource.gps))
        assertSame(state, LivePresentation.adopt(state, state.rideId!!.uppercase()))
        val cleared =
            LivePresentation.observe(
                state,
                state.rideId!!,
                LiveSource.controller,
                reading(2, 101_000_000, values = mapOf("cadenceRpm" to 90.0)),
                clock(101_000_000),
            )
        val view = LivePresentation.view(cleared, LivePresentationInput("running"), clock(101_000_000))
        assertNull(view.values["humanPowerW"])
        assertEquals(90.0, view.values["cadenceRpm"])
        val context = ExternalControlContext(f["rideA"].text(), f["token"].text(), "running", null)
        val request =
            ExternalControlRequest(f["rideA"].text().uppercase(), f["token"].text().uppercase(), "running", "pause")
        assertEquals("admitted", RideActions.external(context, request, false).outcome)
        assertFailsWith<IllegalArgumentException> {
            ExternalControlRequest("bad", f["token"].text(), "running", "pause")
        }
    }

    @Test
    fun presentationNativePendingAndResolvedStart() {
        val ride = presentationFixture("actions")["rideA"].text()
        val pause = ActionRequest("a", ride, RideAction.pause)
        assertFalse(
            RideActions.admit(ActionPresentationState(), pause, "running", ride, nativePendingAction = "lap").admitted
        )
        val start = ActionRequest("b", null, RideAction.start)
        assertTrue(RideActions.admit(ActionPresentationState(), start, "completed", ride).admitted)
        assertFalse(
            RideActions.admit(
                    ActionPresentationState(),
                    start,
                    "completed",
                    ride,
                    startContext = RideStartContext(unresolvedOwner = true),
                )
                .admitted
        )
        assertFalse(RideActions.admit(ActionPresentationState(), start, "interrupted", ride).admitted)
    }

    @Test
    fun presentationControllerFreshnessFixtures() {
        for (row in presentationFixture("freshness")["controller"].list()) {
            val view =
                LivePresentation.view(
                    liveState(),
                    LivePresentationInput(row["phase"].text()),
                    clock(100_000_000 + (row["age"].double() * 1_000_000).toLong()),
                )
            assertEquals(row["display"].text(), view.controllerDisplay, row.toString())
            assertEquals(row["status"].text(), view.controllerStatus)
            assertEquals(row["power"].optionalDouble(), view.values["humanPowerW"])
        }
        val empty = LivePresentation.adopt(LivePresentation.empty(), presentationFixture("freshness")["rideA"].text())
        assertEquals(
            "waiting",
            LivePresentation.view(empty, LivePresentationInput("running"), clock(100_000_000)).controllerStatus,
        )
        assertEquals(
            "off",
            LivePresentation.view(LivePresentation.empty(), LivePresentationInput("running"), clock(100_000_000))
                .controllerStatus,
        )
        for (age in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, null)) assertEquals(
            "unavailable",
            StreamStatus.controllerDisplay(age, "running"),
        )
    }

    @Test
    fun presentationGPSFixtures() {
        val fixture = presentationFixture("freshness")
        for (r in fixture["gps"].list()) {
            val age = r["age"].optionalDouble()
            assertEquals(
                r["status"].text(),
                StreamStatus.gps(
                    r["requested"].bool(),
                    r["phase"].text(),
                    age,
                    r["accuracy"].optionalDouble(),
                    r["provider"].optionalText(),
                ),
                r.toString(),
            )
            var state = LivePresentation.adopt(LivePresentation.empty(), fixture["rideA"].text())
            if (age != null)
                state =
                    LivePresentation.observe(
                        state,
                        state.rideId!!,
                        LiveSource.gps,
                        reading(values = mapOf("speedMps" to 150.0)),
                        clock(100_000_000),
                    )
            val view =
                LivePresentation.view(
                    state,
                    LivePresentationInput(
                        r["phase"].text(),
                        gpsRequested = r["requested"].bool(),
                        gpsProviderStatus = r["provider"].optionalText(),
                        gpsAccuracy = r["accuracy"].optionalDouble(),
                    ),
                    clock(100_000_000 + ((age ?: 0.0) * 1_000_000).toLong()),
                )
            assertEquals(r["status"].text(), view.gpsStatus, r.toString())
        }
    }

    @Test
    fun presentationHeartAndPausedFixtures() {
        for (r in presentationFixture("freshness")["heart"].list()) {
            val view =
                LivePresentation.view(
                    liveState(),
                    LivePresentationInput(r["phase"].text()),
                    clock(100_000_000 + (r["age"].double() * 1_000_000).toLong()),
                )
            assertEquals(r["text"].text(), MetricCatalog.format("heartRateBpm", view.values["heartRateBpm"]))
            if (r["phase"].text() == "paused") assertEquals("paused", view.heartStatus)
        }
    }

    @Test
    fun presentationRideResetAndReplay() {
        val f = presentationFixture("freshness")
        val a = f["rideA"].text()
        var state = liveState(a)
        state =
            LivePresentation.observe(
                state,
                a,
                LiveSource.gps,
                reading(values = mapOf("speedMps" to 1.0)),
                clock(100_000_000),
            )
        assertSame(state, LivePresentation.adopt(state, a))
        for (source in LiveSource.entries) assertEquals(
            100_000_000L,
            state.reading(source)!!.acquisition.originalTimeUs,
        )
        assertSame(
            state,
            LivePresentation.observe(
                state,
                a,
                LiveSource.controller,
                reading(acquisition = 110_000_000),
                clock(110_000_000),
            ),
        )
        assertEquals(
            "stale",
            LivePresentation.view(state, LivePresentationInput("running"), clock(110_000_000)).controllerStatus,
        )
        val changed =
            LivePresentation.observe(state, a, LiveSource.controller, reading(2, 101_000_000), clock(101_000_000))
        assertEquals(2L, changed.reading(LiveSource.controller)!!.acquisition.sequence)
        val reset = LivePresentation.adopt(changed, f["rideB"].text())
        LiveSource.entries.forEach { assertNull(reset.reading(it)) }
        assertTrue(LivePresentation.view(reset, LivePresentationInput("running"), clock(120_000_000)).values.isEmpty())
        val next =
            LivePresentation.observe(
                reset,
                f["rideB"].text(),
                LiveSource.controller,
                reading(acquisition = 120_000_000),
                clock(120_000_000),
            )
        assertEquals(1L, next.reading(LiveSource.controller)!!.acquisition.sequence)
        assertSame(next, LivePresentation.observe(next, a, LiveSource.controller, reading(3), clock(120_000_000)))
    }

    @Test
    fun presentationHeldValuesRetainOriginalEvidence() {
        val state = liveState()
        val original = state.reading(LiveSource.controller)!!
        val view = LivePresentation.view(state, LivePresentationInput("running"), clock(105_999_999))
        assertEquals("held", view.controllerDisplay)
        assertEquals("normal", view.controllerConnection)
        assertSame(original, state.reading(LiveSource.controller))
        assertEquals(100_000_000L, original.acquisition.originalTimeUs)
        assertEquals(1L, original.acquisition.sequence)
        assertEquals(
            "reconnectError",
            LivePresentation.view(state, LivePresentationInput("running"), clock(106_000_000)).controllerConnection,
        )
        assertEquals("Connected", StreamStatus.connectionLabel("reconnecting", "held"))
        assertEquals("Reconnecting…", StreamStatus.connectionLabel("reconnecting", "unavailable"))
    }

    @Test
    fun presentationEpochAndBackfill() {
        val state = liveState()
        assertTrue(
            LivePresentation.view(state, LivePresentationInput("running"), clock(101_000_000, "process-b"))
                .values
                .isEmpty()
        )
        val backfill = reading(2, 116_000_000, 100_000_000, values = mapOf("heartRateBpm" to 90.0))
        assertSame(
            state,
            LivePresentation.observe(state, state.rideId!!, LiveSource.heartRate, backfill, clock(116_000_000), true),
        )
        assertEquals(100_000_000L, backfill.acquisition.originalTimeUs)
        assertEquals(90.0, backfill.values["heartRateBpm"])
        assertNull(
            LivePresentation.view(state, LivePresentationInput("running"), clock(116_000_000)).values["heartRateBpm"]
        )
        assertSame(
            state,
            LivePresentation.observe(
                state,
                state.rideId!!,
                LiveSource.controller,
                reading(2, epoch = "foreign"),
                clock(101_000_000),
            ),
        )
    }

    @Test
    fun presentationReadingValidation() {
        assertFailsWith<IllegalArgumentException> { reading(values = mapOf("unknown" to 1.0)) }
        assertFailsWith<IllegalArgumentException> { reading(values = mapOf("humanPowerW" to Double.NaN)) }
        val values = mutableMapOf("humanPowerW" to 200.0)
        val r = reading(values = values)
        values["humanPowerW"] = 0.0
        assertEquals(200.0, r.values["humanPowerW"])
    }

    @Test
    fun presentationExternalControlFixtures() {
        val f = presentationFixture("actions")
        for (r in f["external"].list()) {
            val result =
                RideActions.external(
                    ExternalControlContext(
                        f["rideA"].text(),
                        f["token"].text(),
                        "running",
                        r["pending"].optionalText(),
                    ),
                    ExternalControlRequest(
                        f["rideA"].text(),
                        r["token"].text(),
                        r["expectedPhase"].text(),
                        r["action"].text(),
                    ),
                    r["confirmed"].bool(),
                )
            assertEquals(r["outcome"].text(), result.outcome)
            assertEquals(r["command"].optionalText(), result.command)
        }
        val request = ExternalControlRequest(f["rideA"].text(), f["token"].text(), "running", "finish")
        assertEquals(
            "rejected",
            RideActions.external(
                    ExternalControlContext(f["rideB"].text(), f["token"].text(), "running", null),
                    request,
                    true,
                )
                .outcome,
        )
    }

    @Test
    fun presentationActionSuccessAndErrorFixtures() {
        for (r in presentationFixture("actions")["outcomes"].list()) {
            val action = RideAction.valueOf(r["action"].text())
            val ride = if (action == RideAction.start) null else presentationFixture("actions")["rideA"].text()
            val admitted =
                RideActions.admit(
                    ActionPresentationState(),
                    ActionRequest(
                        "a",
                        ride,
                        action,
                        if (action == RideAction.stopUnconfirmed && r["phase"].text() == "preparing") null
                        else ride?.let(::FinishConfirmation),
                    ),
                    r["phase"].text(),
                    ride,
                )
            assertTrue(admitted.admitted, r.toString())
            assertEquals("none", admitted.haptic)
            val succeeded = RideActions.complete(admitted.next, "a", ride, null)
            assertEquals(r["haptic"].text(), succeeded.haptic)
            assertNull(succeeded.next.pending)
            val failed = RideActions.complete(admitted.next, "a", ride, "native failure")
            assertEquals("warning", failed.haptic)
            assertEquals("unknown", failed.next.error)
        }
        val f = presentationFixture("actions")
        for (r in f["unconfirmedStop"].list()) {
            val result =
                RideActions.admit(
                    ActionPresentationState(),
                    ActionRequest("a", f["rideA"].text(), RideAction.stopUnconfirmed),
                    r["phase"].text(),
                    f["rideA"].text(),
                )
            assertEquals(r["admitted"].bool(), result.admitted, r["phase"].text())
        }
    }

    @Test
    fun presentationPendingActionAndReceiptFence() {
        val ride = presentationFixture("actions")["rideA"].text()
        val first =
            RideActions.admit(ActionPresentationState(), ActionRequest("a", ride, RideAction.pause), "running", ride)
        assertFalse(RideActions.admit(first.next, ActionRequest("b", ride, RideAction.lap), "running", ride).admitted)
        assertSame(first.next, RideActions.complete(first.next, "b", ride, null).next)
        assertSame(first.next, RideActions.complete(first.next, "a", "another-ride", null).next)
        assertFalse(
            RideActions.admit(ActionPresentationState(), ActionRequest("a", ride, RideAction.lap), "paused", ride)
                .admitted
        )
    }

    @Test
    fun presentationTerminalConfirmationIdentity() {
        val f = presentationFixture("actions")
        val ride = f["rideA"].text()
        val request = ActionRequest("a", ride, RideAction.stop, FinishConfirmation(ride))
        assertFalse(RideActions.admit(ActionPresentationState(), request, "running", f["rideB"].text()).admitted)
        assertFalse(
            RideActions.admit(ActionPresentationState(), request.copy(confirmation = null), "running", ride).admitted
        )
        assertTrue(RideActions.admit(ActionPresentationState(), request, "running", ride).admitted)
    }

    @Test
    fun presentationOptionFixtures() {
        for (r in presentationFixture("options")["options"].list()) {
            val o =
                RideOptions.resolve(
                    RequestedRideOptions(
                        r["indoor"].bool(),
                        r["watch"].optionalBool(),
                        r["health"].optionalBool(),
                        r["recordGPS"].optionalBool(),
                        r["sampleHz"].optionalDouble(),
                    ),
                    allCapabilities,
                )
            assertEquals(r["expectedGPS"].bool(), o.recordGPS)
            assertEquals(r["expectedHealth"].bool(), o.saveToHealth)
            assertEquals(r["expectedWatch"].bool(), o.useWatch)
            assertEquals(r["expectedRate"].long().toInt(), o.sampleHz)
        }
    }

    @Test
    fun presentationOptionsWithoutWatchOrHealth() {
        val o =
            RideOptions.resolve(
                RequestedRideOptions(useWatch = true),
                RideCapabilities(true, false, false, false, true, null, true),
            )
        assertFalse(o.useWatch)
        assertFalse(o.saveToHealth)
        assertTrue(o.recordGPS)
    }

    @Test
    fun presentationFrozenRateFixtures() {
        val f = presentationFixture("options")
        for (r in f["invalidRates"].list()) assertFailsWith<IllegalArgumentException> {
            RideOptions.restore(false, false, false, true, r.optionalDouble())
        }
        for (r in f["validRates"].list()) assertEquals(r.long().toInt(), SampleRates.require(r.double()))
        val frozen = RideOptions.resolve(RequestedRideOptions(sampleHz = f["frozenRate"].double()), allCapabilities)
        val next =
            RideOptions.resolve(RequestedRideOptions(sampleHz = f["changedPreferenceRate"].double()), allCapabilities)
        assertEquals(
            8,
            RideOptions.restore(
                    frozen.indoor,
                    frozen.useWatch,
                    frozen.saveToHealth,
                    frozen.recordGPS,
                    frozen.sampleHz.toDouble(),
                )
                .sampleHz,
        )
        assertEquals(2, next.sampleHz)
        assertFailsWith<IllegalArgumentException> {
            RideOptions.resolve(RequestedRideOptions(sampleHz = 3.0), allCapabilities)
        }
    }

    @Test
    fun presentationStartRequiresBikeWithEveryOptionalSourceCombination() {
        for (gps in listOf(false, true)) {
            for (watch in listOf(false, true)) {
                val options =
                    RideOptions.resolve(RequestedRideOptions(recordGPS = gps, useWatch = watch), allCapabilities)
                assertEquals(
                    "bikeRequired",
                    RideOptions.readiness(options, allCapabilities, false, true).error,
                    "GPS=$gps Watch=$watch",
                )
                assertTrue(RideOptions.readiness(options, allCapabilities, true, true).available)
            }
        }
        val o = RideOptions.resolve(RequestedRideOptions(saveToHealth = false), allCapabilities)
        assertTrue(RideOptions.readiness(o, allCapabilities, true, false).available)
        assertFalse(o.saveToHealth)
        assertEquals("phone", o.recordingOwner)
        assertEquals(
            "ownerUnresolved",
            RideOptions.readiness(
                    o,
                    allCapabilities,
                    false,
                    false,
                    startContext = RideStartContext(unresolvedOwner = true),
                )
                .error,
        )
        assertEquals(
            "actual native error",
            RideOptions.readiness(o, allCapabilities, false, true, error = "actual native error").error,
        )
        val indoor = RideOptions.resolve(RequestedRideOptions(indoor = true), allCapabilities)
        assertEquals("bikeRequired", RideOptions.readiness(indoor, allCapabilities, false, false).error)
        val watch = RideOptions.resolve(RequestedRideOptions(useWatch = true), allCapabilities)
        assertEquals("watchNotInstalled", RideOptions.readiness(watch, allCapabilities, true, false).error)
        assertTrue(RideOptions.readiness(watch, allCapabilities, true, true).available)
    }

    @Test
    fun presentationPermissionFixtures() {
        for (r in presentationFixture("permissions")["cases"].list()) {
            val raw = r["status"]
            val s =
                if (raw == J.Null) null
                else
                    PermissionStatus(
                        raw["available"].bool(),
                        raw["provider"].optionalText(),
                        if (raw["writes"].fields().isEmpty()) null else listOf("workout"),
                        raw["writes"].fields().mapValues { it.value.text() },
                        raw["location"].text(),
                        raw["services"].bool(),
                        raw["accuracy"].text(),
                    )
            val result =
                SetupPresentation.permission(
                    s,
                    RequestedRideOptions(
                        useWatch = r["watch"].bool(),
                        saveToHealth = r["health"].bool(),
                        recordGPS = r["gps"].bool(),
                    ),
                )
            assertEquals(r["action"].text(), result.action, r["name"].text())
            assertEquals(r["label"].text(), result.label, r["name"].text())
        }
        assertContains(
            SetupPresentation.health(RequestedRideOptions(), RideCapabilities(true, false, false, false, true, null))
                .detail,
            "This ride will stay in Power Log.",
        )
    }

    @Test
    fun presentationTimeoutFixtures() {
        for (r in presentationFixture("timeouts")["cases"].list()) {
            val result = PresentationTimeouts.evaluate(PresentationWait.valueOf(r["kind"].text()), 0, r["nowUs"].long())
            assertEquals(r["outcome"].text(), result.outcome)
            assertEquals(r["pending"].bool(), result.pending)
            assertNotEquals("completed", result.outcome)
            assertNotEquals("running", result.outcome)
        }
        assertEquals(0L, PresentationTimeouts.beginHandoff(null, 0, "ready", false, true, false))
        assertNull(PresentationTimeouts.beginHandoff(null, 0, "ready", true, true, false))
        assertNull(PresentationTimeouts.beginHandoff(null, 0, "ready", false, true, true))
    }

    @Test
    fun presentationFormattingFixtures() {
        for (r in presentationFixture("formatting")["metrics"].list()) assertEquals(
            r["text"].text(),
            MetricCatalog.format(r["id"].text(), r["value"].optionalDouble(), r["unit"].text()),
            r.toString(),
        )
        for (r in presentationFixture("formatting")["duration"].list()) assertEquals(
            r["text"].text(),
            PresentationFormatting.duration(r["seconds"].optionalDouble()),
        )
        assertEquals("km", MetricCatalog.presentationMetric("distanceMeters", "mph").unit)
        assertEquals("mph", MetricCatalog.presentationMetric("controllerSpeedMps", "mph").unit)
        assertFailsWith<IllegalArgumentException> { MetricCatalog.presentationMetric("humanPowerW", "km") }
    }

    @Test
    fun presentationExactIntegerFixtures() {
        for (r in presentationFixture("formatting")["exact"].list()) assertEquals(
            r["text"].text(),
            MetricCatalog.formatExact(r["id"].text(), r["original"].text()),
        )
        for (v in listOf("01", "1.5", "abc", "18446744073709551616", "-9223372036854775809")) assertNull(
            MetricCatalog.formatExact("humanPowerW", v)
        )
    }

    @Test
    fun presentationDistanceProvenanceFixtures() {
        for (r in presentationFixture("formatting")["distance"].list()) {
            val result =
                PresentationFormatting.distance(
                    1234.56,
                    r["source"].text(),
                    r["partial"].bool(),
                    r["estimated"].bool(),
                    r["descriptors"].list().map {
                        MeasurementSource(it["source"].text(), it["device"].text(), it["provider"].optionalText())
                    },
                )
            assertEquals("1.23", result.text)
            assertEquals("km", result.unit)
            assertEquals(r["caption"].text(), result.caption)
            assertEquals(r["partial"].bool(), result.partial)
        }
        assertEquals("0.00", PresentationFormatting.distance(0.0, "gps:phone").text)
        assertEquals("—", PresentationFormatting.distance(-1.0, "gps:phone").text)
        assertEquals("Heart rate (backfilled)", PresentationFormatting.physiology("Heart rate", "healthConnect"))
        assertEquals("Apple Health", PresentationFormatting.provider("appleHealth"))
        assertEquals("Health Connect", PresentationFormatting.provider("healthConnect"))
        assertEquals("Health", PresentationFormatting.provider(null))
    }

    @Test
    fun presentationActivityFixtures() {
        val f = presentationFixture("activity")
        for (r in f["cases"].list()) {
            val now = r["nowUs"].long()
            val input =
                ActivityInput(
                    f["rideA"].text(),
                    1,
                    f["token"].text(),
                    r["phase"].text(),
                    r["pending"].optionalText(),
                    10.0,
                )
            val p = ActivityPresentations.project(input, liveState(), clock(now))
            assertEquals(f["bikeExpiresAtUs"].long(), p.bikeExpiresAtUs)
            assertEquals(f["heartRateExpiresAtUs"].long(), p.heartRateExpiresAtUs)
            assertEquals(r["staleDateUs"].optionalText()?.toLong(), p.staleDateUs)
            val v = p.render(now, r["systemStale"].bool())
            assertEquals("—", p.render(f["bikeExpiresAtUs"].long(), false).bike.text)
            assertEquals("—", p.render(f["heartRateExpiresAtUs"].long(), false).heartRate.text)
            assertEquals(r["bike"].text(), v.bike.text)
            assertEquals(r["heart"].text(), v.heartRate.text)
            assertEquals(r["status"].text(), v.status.text)
            assertEquals(r["controls"].list().map { it.text() }, v.controls.map { it.action })
            assertEquals(r["timer"].text(), v.timer.mode)
            assertEquals(!r["systemStale"].bool(), v.showReadings)
            if (v.timer.mode == "running") assertEquals(now - 10_000_000, v.timer.originUs)
            assertTrue(p.controls.filter { it.action == "finish" }.all { it.requiresConfirmation })
            p.controls.forEach {
                assertEquals(input.rideId, it.rideId)
                assertEquals(input.controlToken, it.controlToken)
                assertEquals(input.phase, it.expectedPhase)
            }
        }
    }

    @Test
    fun presentationActivityMissingAndForeignEvidence() {
        val f = presentationFixture("activity")
        val input = ActivityInput(f["rideA"].text(), 7, f["token"].text(), "running", null, 10.0)
        val heart =
            LivePresentation.observe(
                LivePresentation.adopt(LivePresentation.empty(), input.rideId),
                input.rideId,
                LiveSource.heartRate,
                reading(values = mapOf("heartRateBpm" to 150.0)),
                clock(100_000_000),
            )
        val p = ActivityPresentations.project(input, heart, clock(105_000_000))
        assertNull(p.bikeExpiresAtUs)
        assertEquals(130_000_000L, p.staleDateUs)
        assertEquals(7L, p.publicationSequence)
        assertEquals("Bike unavailable", p.render(105_000_000, false).status.text)
        assertEquals("—", p.render(105_000_000, false).bike.text)
        val foreign = ActivityPresentations.project(input, liveState(), clock(105_000_000, "foreign"))
        assertNull(foreign.staleDateUs)
        assertEquals("—", foreign.render(105_000_000, false).heartRate.text)
        assertNull(
            ActivityPresentations.project(input.copy(rideId = f["rideB"].text()), liveState(), clock(105_000_000))
                .staleDateUs
        )
    }

    @Test
    fun presentationActivityRenderUsesSuppliedVariants() {
        val f = presentationFixture("activity")
        val p =
            ActivityPresentations.project(
                ActivityInput(f["rideA"].text(), 1, f["token"].text(), "running", null, 10.0),
                liveState(),
                clock(105_000_000),
            )
        assertEquals("200", p.render(120_999_999, false).bike.text)
        assertEquals("—", p.render(121_000_000, false).bike.text)
        assertEquals("150", p.render(121_000_000, false).heartRate.text)
        assertEquals("—", p.render(121_000_000, true).heartRate.text)
        assertEquals("—", p.render(121_000_000, true).timer.text)
        assertTrue(p.render(121_000_000, true).controls.isEmpty())
        assertEquals("antenna.radiowaves.left.and.right.slash", p.render(121_000_000, false).status.symbol)
    }

    @Test
    fun presentationPreferenceDefaultFixtures() {
        val f = presentationFixture("preferences")
        val expected = f["defaults"].json()
        assertEquals(expected, JsonCodec.parse(Preferences.encode(Preferences.defaults())))
        assertEquals(expected, JsonCodec.parse(Preferences.encode(Preferences.decode(JsonCodec.encode(expected)))))
        val watch = Preferences.watchDefaults()
        assertEquals(f["watch"]["indoor"].bool(), watch.indoor)
        assertEquals(f["watch"]["saveToHealth"].bool(), watch.saveToHealth)
        assertEquals(f["watch"]["recordGPS"].optionalBool(), watch.recordGPS)
        assertEquals(f["watch"]["sampleHz"].double(), watch.sampleHz)
    }

    @Test
    fun presentationIndependentPresetSelections() {
        val defaults = Preferences.defaults()
        val ride = defaults.views.getValue(MonitorPresetId.ride)
        val battery = defaults.views.getValue(MonitorPresetId.battery)
        val changed =
            defaults
                .withPreset(MonitorPresetId.ride, MonitorPreset(ride.numbers, ride.charts, MonitorRange.seconds30, 2))
                .withPreset(
                    MonitorPresetId.battery,
                    MonitorPreset(listOf("batteryVoltageV"), battery.charts, battery.range, 2),
                )
                .withSelection(MonitorPresetId.temperature, MonitorPresetId.battery, MonitorRange.all)
        val reopened = Preferences.decode(Preferences.encode(changed))
        assertEquals(MonitorRange.seconds30, reopened.views.getValue(MonitorPresetId.ride).range)
        assertEquals(listOf("batteryVoltageV"), reopened.views.getValue(MonitorPresetId.battery).numbers)
        assertEquals(battery.charts, reopened.views.getValue(MonitorPresetId.battery).charts)
        assertEquals(MonitorRange.seconds600, reopened.views.getValue(MonitorPresetId.temperature).range)
        assertEquals(MonitorPresetId.temperature, reopened.activeView)
        assertEquals(MonitorPresetId.battery, reopened.historyView)
        assertEquals(MonitorRange.all, reopened.historyRange)
        assertEquals(MonitorRange.seconds120, defaults.views.getValue(MonitorPresetId.ride).range)
    }

    @Test
    fun presentationStrictStoredPreferenceFixtures() {
        val f = presentationFixture("preferences")
        val fields = f["defaults"].json().obj().fields
        for (r in f["invalid"].list()) assertFailsWith<IllegalArgumentException> {
            Preferences.decode(JsonCodec.encode(Obj(fields + (r["field"].text() to r["value"].json()))))
        }
        val options = fields.getValue("workoutOptions").obj()
        for (bad in
            listOf(options.fields + ("sampleHz" to Num(2.0)), options.fields + ("recordGPS" to Nil))) assertFailsWith<
            IllegalArgumentException
        > {
            Preferences.decode(JsonCodec.encode(Obj(fields + ("workoutOptions" to Obj(bad)))))
        }
        assertFailsWith<IllegalArgumentException> {
            MonitorPreset(listOf("speedRaw"), emptyList(), MonitorRange.all, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            MonitorPreset(emptyList(), listOf("healthSpeedMps"), MonitorRange.all, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            MonitorPreset(listOf("humanPowerW", "humanPowerW"), emptyList(), MonitorRange.all, 2)
        }
        assertFailsWith<IllegalArgumentException> { Preferences.decode("{\"version\":1,\"version\":1}") }
        assertFailsWith<IllegalArgumentException> {
            Preferences.decode(JsonCodec.encode(Obj(fields + ("unknown" to Bool(true)))))
        }
    }

    @Test
    fun presentationStoredPreferencesSkipUnavailableChoices() {
        val f = presentationFixture("preferences")
        val fields = f["defaults"].json().obj().fields
        for (source in f["unavailable"]["distanceSources"].list()) assertEquals(
            "auto",
            Preferences.decode(JsonCodec.encode(Obj(fields + ("distanceSource" to source.json())))).distanceSource,
        )
        val removed = f["unavailable"]["metrics"].list().map { Str(it.text()) }
        val views = fields.getValue("views").obj().fields
        val ride = views.getValue("ride").obj().fields
        val mixed = { key: String -> key to Arr(removed.take(1) + ride.getValue(key).array() + removed.drop(1)) }
        val stored = Obj(fields + ("views" to Obj(views + ("ride" to Obj(ride + mixed("numbers") + mixed("charts"))))))
        val reopened = Preferences.decode(JsonCodec.encode(stored)).views.getValue(MonitorPresetId.ride)
        assertEquals(ride.getValue("numbers").array().map { it.string() }, reopened.numbers)
        assertEquals(ride.getValue("charts").array().map { it.string() }, reopened.charts)
    }

    @Test
    fun presentationUnreadablePreferencesNeverSaveDefaults() {
        val f = presentationFixture("preferences")["loadFailure"]
        val previous =
            Preferences.defaults()
                .withSelection(MonitorPresetId.temperature, MonitorPresetId.battery, MonitorRange.seconds120)
        val edited =
            Preferences.defaults()
                .withSelection(
                    MonitorPresetId.valueOf(f["edit"]["activeView"].text()),
                    MonitorPresetId.valueOf(f["edit"]["historyView"].text()),
                    MonitorRange.valueOf("seconds${f["edit"]["historyRange"].long()}"),
                )
        for (result in
            listOf(Preferences.load(previous, "bad JSON"), Preferences.load(previous, null, f["error"].text()))) {
            assertSame(previous, result.preferences)
            assertNotNull(result.error)
            val changed = Preferences.change(result, edited)
            assertSame(edited, changed.preferences)
            assertEquals(f["shouldSave"].bool(), changed.shouldSave)
            assertEquals(f["writable"].bool(), changed.writable)
            assertEquals(result.error, changed.error)
            assertTrue(changed.error!!.contains(f["notice"].text()))
            val reloaded = Preferences.load(changed.preferences, Preferences.encode(Preferences.defaults()))
            assertEquals(Preferences.encode(Preferences.defaults()), Preferences.encode(reloaded.preferences))
            assertNull(reloaded.error)
            assertTrue(reloaded.writable)
            assertFalse(reloaded.shouldSave)
            assertTrue(Preferences.change(reloaded, previous).shouldSave)
        }
        val empty = Preferences.load(previous, null)
        assertTrue(empty.writable)
        assertFalse(empty.shouldSave)
        assertTrue(Preferences.change(empty, previous).shouldSave)
    }

    @Test
    fun presentationAutomaticGPSPreference() {
        val preferences = Preferences.defaults().withOptions(RequestedRideOptions(true, false, true))
        val reopened = Preferences.decode(Preferences.encode(preferences))
        assertNull(reopened.workoutOptions.recordGPS)
        assertFalse(RideOptions.resolve(reopened.startOptions(), allCapabilities).recordGPS)
        assertTrue(RideOptions.resolve(reopened.workoutOptions.copy(indoor = false), allCapabilities).recordGPS)
        val explicit = preferences.withOptions(preferences.workoutOptions.copy(recordGPS = true))
        assertEquals(true, Preferences.decode(Preferences.encode(explicit)).workoutOptions.recordGPS)
        assertTrue(RideOptions.resolve(explicit.startOptions(), allCapabilities).recordGPS)
    }

    @Test
    fun presentationSourceAndProviderWording() {
        val options = RideOptions.resolve(RequestedRideOptions(), allCapabilities)
        val idle =
            SourcePresentations.project(
                SourcePresentationInput("idle", options, false, "off", "off", "off", "phone", null, "appleHealth")
            )
        assertEquals("Not connected", idle.bike.label)
        assertEquals("Starts with workout", idle.gps.label)
        val active =
            SourcePresentations.project(
                SourcePresentationInput(
                    "running",
                    options,
                    true,
                    "receiving",
                    "backfilled",
                    "receiving",
                    "phone",
                    5.5,
                    "healthConnect",
                )
            )
        assertEquals("Backfilled heart rate", active.heartRate.label)
        assertEquals("neutral", active.heartRate.tone)
        assertEquals("Phone · ±6 m", active.gps.detail)
        assertEquals("Receiving samples", active.bike.label)
        assertNotNull(SourcePresentations.ownership("anotherBrowserTab"))
    }

    @Test
    fun presentationSourceFactsDoNotRequireARecordingRate() {
        for (rate in SampleRates.admitted) {
            val selected = FrozenRideOptions(false, true, false, true, rate)
            val input =
                SourcePresentationInput(
                    "running",
                    selected,
                    true,
                    "receiving",
                    "receiving",
                    "receiving",
                    "watch",
                    5.0,
                    "appleHealth",
                )
            assertEquals(
                SourcePresentations.project(input),
                SourcePresentations.project(
                    "running",
                    false,
                    true,
                    false,
                    true,
                    true,
                    "receiving",
                    "receiving",
                    "receiving",
                    "watch",
                    5.0,
                    "appleHealth",
                ),
            )
        }
    }

    @Test
    fun presentationSchedulerDeadlinesMatchTimeoutFixtures() {
        for (row in presentationFixture("timeouts")["cases"].list()) {
            val kind = PresentationWait.valueOf(row["kind"].text())
            val deadline = PresentationTimeouts.deadline(kind, 10L)
            assertTrue(PresentationTimeouts.evaluate(kind, 10L, deadline - 1).pending)
            assertFalse(PresentationTimeouts.evaluate(kind, 10L, deadline).pending)
        }
    }

    @Test
    fun presentationActiveFinishConfirmationFixtures() {
        val fixture = presentationFixture("actions")
        val ride = fixture["rideA"].text()
        for (row in fixture["activeFinish"].list()) {
            for (action in listOf(RideAction.stopUnconfirmed, RideAction.stop)) {
                val surface = if (action == RideAction.stop) RideActionSurface.rider else RideActionSurface.recovery
                for (confirmation in listOf(null, fixture["rideB"].text(), ride)) {
                    val result =
                        RideActions.admit(
                            ActionPresentationState(),
                            ActionRequest("finish", ride, action, confirmation?.let(::FinishConfirmation)),
                            row["phase"].text(),
                            ride,
                            nativePendingAction = row["pending"].text(),
                            surface = surface,
                        )
                    assertEquals(
                        row[if (confirmation == ride) "confirmedStop" else "unconfirmedStop"].bool(),
                        result.admitted,
                        "${row["phase"].text()} ${row["pending"].text()} $action $confirmation",
                    )
                    assertEquals(
                        if (confirmation == ride) null else "finishConfirmationRequired",
                        result.error,
                    )
                }
            }
            assertEquals(
                row["retry"].bool(),
                RideActions.admit(
                        ActionPresentationState(),
                        ActionRequest("retry", ride, RideAction.recover),
                        row["phase"].text(),
                        ride,
                        nativePendingAction = row["pending"].text(),
                        surface = RideActionSurface.recovery,
                    )
                    .admitted,
            )
        }
    }

    @Test
    fun presentationRecoverySurfaceFixtures() {
        val fixture = presentationFixture("actions")
        val ride = fixture["rideA"].text()
        for (row in fixture["recovery"].list()) {
            assertEquals(
                row["stopOffered"].bool(),
                RideActions.recoveryStopOffered(row["phase"].text(), row["pending"].optionalText()),
                row["name"].text(),
            )
            val result =
                RideActions.admit(
                    ActionPresentationState(),
                    ActionRequest("retry", ride, RideAction.valueOf(row["action"].text())),
                    row["phase"].text(),
                    ride,
                    foreignOwner = row["foreign"].bool(),
                    nativePendingAction = row["pending"].optionalText(),
                    surface = RideActionSurface.recovery,
                    adapterBusy = row["busy"].bool(),
                )
            assertEquals(row["admitted"].bool(), result.admitted, row["name"].text())
        }
        val pending = ActionPresentationState(ActionFlight("old", ride, RideAction.pause))
        assertFalse(
            RideActions.admit(
                    pending,
                    ActionRequest("retry", ride, RideAction.recover),
                    "finishing",
                    ride,
                    nativePendingAction = "stop",
                    surface = RideActionSurface.recovery,
                )
                .admitted
        )
    }

    @Test
    fun presentationConnectionPreviewOrderingAndClockFixtures() {
        val preview = ControllerPreview()
        assertTrue(preview.observe("connection", 2, 101.0, 9.0, null, 10.0))
        assertFalse(preview.observe("connection", 1, 100.0, 7.0, null, 10.0))
        val retained = preview.observation
        assertNull(preview.view("connected", null, 10.0).faultCode)
        assertEquals(9.0, preview.view("connected", 10.0, 10.0).faultCode)
        assertSame(retained, preview.observation)
        assertNull(preview.view("connected", null, 10.0).faultCode)
        assertSame(retained, preview.observation)
        assertTrue(preview.observe("connection", 2, 99.0, 7.0, null, 10.0))
        assertEquals(9.0, preview.observation?.faultCode)
        assertEquals(99.0, preview.observation?.acquiredAt)
        for (row in presentationFixture("freshness")["connection"].list()) {
            val mapped = row["acquired"].optionalDouble()
            val view = preview.view(row["connection"].text(), mapped, row["now"].double())
            assertEquals(row["display"].text(), view.display)
            assertEquals(row["deadline"].optionalDouble(), view.nextDeadline)
        }
    }
}
