package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeLocationSource
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.AltitudeDatum
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.recording.LocationOriginal
import app.powerlog.domain.recording.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class GpsCaptureScenarioTest {
    @Test
    fun aGpsRideCommitsItsFixesAndTheFirstFixAfterResumeIsABarrier() {
        val scenario = Scenario()
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location = location
        scenario.test {
            val rideId = gpsRide(scenario)
            repeat(5) {
                location.fixes(listOf(fix(scenario, it)))
                delay(1_000)
            }
            assertEquals(SourceState.receiving, scenario.recording().sources.gps)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.pause))
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.resume))
            location.fixes(listOf(fix(scenario, 10)))
            delay(3_000)
            val fixes = scenario.fixes()
            assertEquals(6, fixes.size)
            assertEquals((1L..6L).toList(), fixes.map { it.seq })
            assertEquals(listOf(false, false, false, false, false, true), fixes.map { it.barrier })
            assertEquals(listOf("start", "pause", "resume"), location.commands.take(3))
        }
    }

    @Test
    fun aHiddenWebTabAdmitsNothingAndItsNextFixIsABarrier() {
        val scenario = Scenario(Platform.web)
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location = location
        scenario.test {
            gpsRide(scenario)
            location.fixes(listOf(fix(scenario, 0)))
            delay(2_000)
            scenario.application.host.foregroundChanged(false)
            val hiddenAt = scenario.clock.monoUs()
            delay(5_000)
            val rowsWhileHidden = scenario.telemetryRows()
            delay(2_000)
            assertEquals(rowsWhileHidden, scenario.telemetryRows())
            scenario.application.host.foregroundChanged(true)
            location.fixes(listOf(fix(scenario, 1)))
            delay(3_000)
            assertTrue(scenario.telemetryRows() > rowsWhileHidden)
            assertEquals(listOf(false, true), scenario.fixes().map { it.barrier })
            val gaps = scenario.telemetryElapsed().zipWithNext { a, b -> b - a }
            assertTrue(gaps.max() >= scenario.clock.monoUs() - hiddenAt - 3_500_000, "$gaps")
            assertEquals(listOf("start", "pause", "resume"), location.commands.take(3))
        }
    }

    @Test
    fun aProviderChangeSetsTheGpsSourceAndLogsIt() {
        val scenario = Scenario()
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location = location
        scenario.test {
            gpsRide(scenario)
            location.provider(LocationProviderState.denied)
            assertEquals(SourceState.denied, scenario.recording { it.sources.gps == SourceState.denied }.sources.gps)
            delay(1_000)
            val line = scenario.archive.log.last { it.event == "location" }
            assertEquals(LogValue.Text("denied"), line.fields.single { it.key == "state" }.value)
            assertFalse(scenario.mutations().any { it is Mutation.AppendLocations })
        }
    }

    @Test
    fun theControllerAndGpsSourcesTurnStaleAtTheirStaleInstants() {
        val scenario = Scenario()
        val location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.location = location
        scenario.test {
            gpsRide(scenario)
            location.fixes(listOf(fix(scenario, 0)))
            val fixedAt = scenario.clock.monoUs()
            assertEquals(
                SourceState.receiving,
                scenario.recording { it.sources.gps == SourceState.receiving }.sources.gps,
            )
            scenario.recording { it.sources.gps == SourceState.stale }
            val gpsHold = requireNotNull(MetricCatalog.of(MetricId.speedMps).displayHoldUs)
            assertEquals(fixedAt + gpsHold, scenario.clock.monoUs())

            scenario.telemetryReplies = false
            val lastReading = scenario.clock.monoUs()
            scenario.recording { it.sources.controller == SourceState.stale }
            val controllerHold = requireNotNull(MetricCatalog.of(MetricId.humanPowerW).displayHoldUs)
            assertEquals(controllerHold + Scenario.REPLY_MS * 1_000, scenario.clock.monoUs() - lastReading)
        }
    }

    private fun undeterminedStart(platform: Platform, allowed: Boolean) {
        val scenario = Scenario(platform)
        scenario.location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.permissions.states = FakePermissionGate.undetermined.copy(bluetooth = PermissionState.granted)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            scenario.indoor(SampleRate.hz8)
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.indoor, false)),
            )
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.RecordGpsTo(true)))
            assertTrue(scenario.recording { it.allowed.start.enabled }.allowed.start.enabled, "$platform")
            val answer = if (allowed) PermissionState.granted else PermissionState.denied
            scenario.permissions.requests.script = {
                Answer.Ok(scenario.permissions.states.copy(location = answer).also { scenario.permissions.states = it })
            }
            val started = scenario.api.recording.start(Empty)
            assertEquals(listOf(setOf(PermissionKind.location)), scenario.permissions.requests.received, "$platform")
            if (allowed) {
                assertEquals(Outcome.Ok(Unit), started, "$platform")
                scenario.recording { it.phase == RidePhase.running }
            } else assertEquals(FailureCode.locationDenied, started.code(), "$platform")
        }
    }

    @Test
    fun aGpsStartWithLocationUndecidedAsksOnceAndStartsWhenAllowed() {
        for (platform in listOf(Platform.web, Platform.ios)) undeterminedStart(platform, allowed = true)
    }

    @Test
    fun aGpsStartWithLocationUndecidedRefusesWithLocationDeniedWhenDenied() {
        for (platform in listOf(Platform.web, Platform.ios)) undeterminedStart(platform, allowed = false)
    }

    private suspend fun ScenarioScope.gpsRide(scenario: Scenario): RideId {
        assertEquals(LaunchResult.Ready, scenario.launch())
        with(scenario) { connect() }
        scenario.indoor(SampleRate.hz8)
        assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.Toggle(RideToggle.indoor, false)))
        assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.RecordGpsTo(true)))
        assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
        return requireNotNull(scenario.recording { it.phase == RidePhase.running }.ride).rideId
    }

    private fun fix(scenario: Scenario, step: Int) =
        LocationOriginal(
            utcUs = scenario.clock.utcUs(),
            acquiredMonoUs = scenario.clock.monoUs(),
            latitude = 52.5 + step * 0.0001,
            longitude = 13.4,
            horizontalAccuracyM = 5.0,
            altitudeM = null,
            altitudeDatum = AltitudeDatum.unknown,
            verticalAccuracyM = null,
            mslM = null,
            mslVerticalAccuracyM = null,
            speedMps = 5.0,
            speedAccuracyMps = 0.5,
            courseDeg = null,
            courseAccuracyDeg = null,
        )

    private fun Scenario.fixes(): List<LocationFix> =
        mutations().filterIsInstance<Mutation.AppendLocations>().flatMap { it.fixes }

    private fun Scenario.telemetryElapsed(): List<Long> =
        telemetry().flatMap { batch -> (0 until batch.rows.rows).map { batch.rows.elapsedUs[it].toLong() } }
}
