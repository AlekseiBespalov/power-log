package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.connection.ConnectInput
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.RadioState
import app.powerlog.application.settings.SettingsChange
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class ConnectionScenarioTest {
    private val service = CycDecoder.transport.service

    @Test
    fun anAndroidSearchAsksForBluetoothOnceBeforePreparingAndARefusalEndsIt() {
        val scenario = Scenario(Platform.android)
        scenario.permissions.states = FakePermissionGate.undetermined
        scenario.permissions.requests.script = { Answer.Held }
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            val refused = async { scenario.api.connection.startSearch(Empty) }
            runCurrent()
            assertEquals(listOf(setOf(PermissionKind.bluetooth)), scenario.permissions.requests.received)
            assertEquals(emptyList(), scenario.fake.osCalls)
            scenario.permissions.answer(FakePermissionGate.undetermined.copy(bluetooth = PermissionState.denied))
            assertEquals(FailureCode.bluetoothDenied, refused.await().code())
            assertEquals(emptyList(), scenario.fake.osCalls)
            val view = scenario.connection()
            assertEquals(ConnectionState.idle, view.state)
            assertEquals(FailureCode.bluetoothDenied, view.failure?.code)

            val granted = async { scenario.api.connection.startSearch(Empty) }
            runCurrent()
            assertEquals(2, scenario.permissions.requests.received.size)
            assertEquals(emptyList(), scenario.fake.osCalls)
            scenario.permissions.answer(Scenario.granted)
            assertEquals(Outcome.Ok(Unit), granted.await())
            assertEquals(listOf("prepare", "scan $service"), scenario.fake.osCalls)
        }
    }

    @Test
    fun aSearchWindowEndingEmptyAnswersBikeNotFoundAndALaterBikeIsIgnored() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            delay(ControllerPolicy.searchUs / 1_000)
            val ended = scenario.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bikeNotFound, ended.failure?.code)
            assertEquals(listOf("prepare", "scan $service", "stopScan"), scenario.fake.osCalls)

            scenario.events.advertised(
                Advertisement("D9", "CYCMOTOR X6", -60, listOf(service), null, scenario.clock.read())
            )
            runCurrent()
            assertEquals(emptyList(), scenario.connection().found)
        }
    }

    @Test
    fun aStoppedSearchEndsWithoutAFailureAndAFailedOneAnswersBluetoothSearchFailed() {
        val scenario = Scenario(Platform.android)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.stopSearch(Empty))
            val stopped = scenario.connection()
            assertEquals(ConnectionState.idle, stopped.state)
            assertNull(stopped.failure)

            with(scenario) { search() }
            scenario.fake.os.scanFailed("2")
            val failed = scenario.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bluetoothSearchFailed, failed.failure?.code)
        }
    }

    @Test
    fun aCancelledWebChooserEndsTheSearchWithoutAFailure() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.fake.os.chooserCancelled()
            val ended = scenario.connection { it.state == ConnectionState.idle }
            assertNull(ended.failure)
        }
    }

    @Test
    fun aConnectWithBluetoothOffOrNearbyDevicesRevokedFailsAtOnceWithoutAnAttempt() {
        val off = Scenario(Platform.android)
        off.test {
            assertEquals(LaunchResult.Ready, off.launch())
            off.fake.os.radio(RadioState.poweredOff)
            assertEquals(FailureCode.bluetoothOff, off.api.connection.connect(ConnectInput(BikeId("D1"))).code())
            assertEquals(listOf("prepare"), off.fake.osCalls)
        }

        val revoked = Scenario(Platform.android)
        revoked.permissions.states = Scenario.granted.copy(bluetooth = PermissionState.denied)
        revoked.test {
            assertEquals(LaunchResult.Ready, revoked.launch())
            assertEquals(FailureCode.bluetoothDenied, revoked.api.connection.connect(ConnectInput(BikeId("D1"))).code())
            assertEquals(emptyList(), revoked.fake.osCalls)
        }
    }

    @Test
    fun bikesFoundStayListedUntilTheNextSearch() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.discovered("D1")
            scenario.discovered("D2")
            scenario.connection { it.found.size == 2 }
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.stopSearch(Empty))
            val stopped = scenario.connection()
            assertEquals(ConnectionState.idle, stopped.state)
            assertEquals(listOf("D1", "D2"), stopped.found.map { it.bikeId.value })

            with(scenario) { search() }
            assertEquals(emptyList(), scenario.connection().found)
            scenario.discovered("D3")
            delay(ControllerPolicy.searchUs / 1_000)
            val ended = scenario.connection { it.state == ConnectionState.idle }
            assertNull(ended.failure)
            assertEquals(listOf("D3"), ended.found.map { it.bikeId.value })
        }
    }

    @Test
    fun aBikeTheWebChooserReturnedEndsTheSearchListedAndConnects() {
        val scenario = Scenario(Platform.web)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.discovered("D1")
            val ended = scenario.connection { it.state == ConnectionState.idle && it.found.isNotEmpty() }
            assertNull(ended.failure)
            assertEquals(listOf("D1"), ended.found.map { it.bikeId.value })
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.connect(ConnectInput(BikeId("D1"))))
            assertEquals(ConnectionState.connected, scenario.connection().state)
        }
    }

    @Test
    fun aRideRecordingWithABikeRefusesAnotherBikeADisconnectAndASearch() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { riding() }
            assertEquals(FailureCode.differentBike, scenario.api.connection.connect(ConnectInput(BikeId("D2"))).code())
            assertEquals(FailureCode.rideActive, scenario.api.connection.disconnect(Empty).code())
            assertEquals(FailureCode.rideActive, scenario.api.connection.startSearch(Empty).code())
            assertEquals(ConnectionState.connected, scenario.connection().state)
        }
    }

    @Test
    fun disconnectingAConnectedBikeCancelsItsLinkAndForgetsIt() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready() }
            assertEquals(Outcome.Ok(Unit), scenario.api.connection.disconnect(Empty))
            assertTrue("disconnect ${Scenario.BIKE}" in scenario.fake.osCalls, scenario.fake.osCalls.toString())
            assertEquals(WriteBatch(listOf(Mutation.SelectBike(null))), scenario.archive.committed.last())
            val view = scenario.connection()
            assertEquals(ConnectionState.idle, view.state)
            assertEquals(BikeId(Scenario.BIKE), view.bike?.bikeId)
            with(scenario) { search() }
            assertNull(scenario.connection().bike)
        }
    }

    @Test
    fun aPreviewFollowsANewSampleRateFromItsNextRequestAndARideKeepsItsOwn() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            delay(4_000)
            val changedAtUs = scenario.clock.monoUs()
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.SampleRateTo(SampleRate.hz8)))
            delay(4_000)
            val beforeChange = scenario.telemetryGaps(2_000_000, changedAtUs)
            val afterChange = scenario.telemetryGaps(changedAtUs + 500_000, scenario.clock.monoUs())
            assertEquals(setOf(500_000L), beforeChange.toSet())
            assertEquals(setOf(125_000L), afterChange.toSet())

            scenario.indoor()
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            val frozenAtUs = scenario.clock.monoUs()
            assertEquals(Outcome.Ok(Unit), scenario.api.settings.change(SettingsChange.SampleRateTo(SampleRate.hz2)))
            delay(4_000)
            assertEquals(setOf(125_000L), scenario.telemetryGaps(frozenAtUs + 500_000, scenario.clock.monoUs()).toSet())
        }
    }

    @Test
    fun aLostLinkPastItsDisplayHoldShowsBikeReconnectingUntilItVerifiesOrGivesUp() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            delay(2_000)
            scenario.linkUps = false
            scenario.fake.os.disconnected(Scenario.BIKE)
            val held = scenario.connection { it.state == ConnectionState.reconnecting }
            assertNull(held.failure)
            val reconnecting = scenario.connection { it.failure != null }
            assertEquals(ConnectionState.reconnecting, reconnecting.state)
            assertEquals(FailureCode.bikeReconnecting, reconnecting.failure?.code)

            scenario.fake.os.connected(Scenario.BIKE)
            val verified = scenario.connection { it.state == ConnectionState.connected }
            assertNull(verified.failure)

            scenario.fake.os.disconnected(Scenario.BIKE)
            val gaveUp = scenario.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bikeUnavailable, gaveUp.failure?.code)
        }
    }

    @Test
    fun anIPhoneLinkTheOsKeepsReconnectingIsAdoptedAtOnce() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            delay(ControllerPolicy.stableLinkUs / 1_000 + 1_000)
            scenario.fake.os.disconnected(Scenario.BIKE, systemReconnecting = true)
            val reconnecting = scenario.connection { it.state == ConnectionState.reconnecting }
            assertEquals(BikeId(Scenario.BIKE), reconnecting.bike?.bikeId)
            val adopted = scenario.connection { it.state == ConnectionState.connected }
            assertNull(adopted.failure)
            assertEquals(1, scenario.fake.osCalls.count { it == "connect ${Scenario.BIKE}" })
            assertFalse(scenario.fake.osCalls.any { it.startsWith("cancel") }, scenario.fake.osCalls.toString())
        }
    }

    @Test
    fun aLinkOutsideARideEndsWhenTheRadioGoesOffAndNothingReconnectsWhenItReturns() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { connect() }
            val selected = scenario.selections(BikeId(Scenario.BIKE))
            scenario.fake.os.radio(RadioState.poweredOff)
            val off = scenario.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bluetoothOff, off.failure?.code)
            delay(1_000)
            assertEquals(Mutation.SelectBike(null), scenario.mutations().last())

            scenario.fake.os.radio(RadioState.poweredOn)
            delay(40_000)
            assertEquals(ConnectionState.idle, scenario.connection().state)
            assertEquals(1, scenario.fake.osCalls.count { it == "connect ${Scenario.BIKE}" })
            assertEquals(selected, scenario.selections(BikeId(Scenario.BIKE)))
        }
    }

    @Test
    fun aRidesReconnectWaitsWhileTheRadioIsOffAndRunsAtOnceWhenItReturns() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.reconnecting }
            scenario.fake.os.radio(RadioState.poweredOff)
            delay(ControllerPolicy.rideReconnectUs / 1_000 + 10_000)
            val off = scenario.connection()
            assertEquals(ConnectionState.reconnecting, off.state)
            assertEquals(FailureCode.bluetoothOff, off.failure?.code)
            assertEquals(1, scenario.fake.osCalls.count { it == "connect ${Scenario.BIKE}" })
            assertFalse(Mutation.SelectBike(null) in scenario.mutations())

            scenario.fake.os.radio(RadioState.poweredOn)
            val back = scenario.connection { it.state == ConnectionState.connected }
            assertNull(back.failure)
            assertEquals(2, scenario.fake.osCalls.count { it == "connect ${Scenario.BIKE}" })
            assertEquals(RidePhase.running, scenario.recording().phase)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
        }
    }

    @Test
    fun aRideThatEndsWhileItsLinkReconnectsCancelsTheLinkAndForgetsTheBike() {
        val scenario = Scenario()
        scenario.test {
            val rideId = with(scenario) { riding() }
            scenario.linkUps = false
            scenario.fake.os.disconnected(Scenario.BIKE)
            scenario.connection { it.state == ConnectionState.reconnecting }
            delay(2_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            val ended = scenario.connection { it.state == ConnectionState.idle }
            assertNull(ended.failure)
            delay(1_000)
            assertEquals(Mutation.SelectBike(null), scenario.mutations().last { it is Mutation.SelectBike })
            delay(60_000)
            assertEquals(ConnectionState.idle, scenario.connection().state)
        }
    }

    @Test
    fun aRadioThatGoesOffDuringASearchEndsItKeepingWhatItFound() {
        val scenario = Scenario()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            with(scenario) { search() }
            scenario.discovered("D1")
            scenario.connection { it.found.isNotEmpty() }
            scenario.fake.os.radio(RadioState.poweredOff)
            val ended = scenario.connection { it.state == ConnectionState.idle }
            assertEquals(FailureCode.bluetoothOff, ended.failure?.code)
            assertEquals(listOf("D1"), ended.found.map { it.bikeId.value })
        }
    }

    private fun Scenario.selections(bike: BikeId): Int = mutations().count { it == Mutation.SelectBike(bike) }

    private fun Scenario.telemetryGaps(fromUs: Long, toUs: Long): List<Long> =
        sent.indices
            .filter { sent[it] == ControllerRequest.telemetry && sentAtUs[it] in fromUs..toUs }
            .map { sentAtUs[it] }
            .zipWithNext { a, b -> b - a }
}
