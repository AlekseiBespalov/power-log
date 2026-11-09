package app.powerlog.application.slice

import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.port.HoldKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.BikeId
import app.powerlog.domain.core.ride.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class RememberedBikeScenarioTest {
    @Test
    fun theIphoneConnectsItsRememberedBikeAtLaunchAsReconnectAttemptOne() {
        val scenario = remembering(Platform.ios)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.radio(RadioState.poweredOn)
            val reconnecting = scenario.connection { it.state == ConnectionState.reconnecting }
            assertEquals(BikeId(Scenario.BIKE), reconnecting.bike?.bikeId)
            scenario.connection { it.state == ConnectionState.connected }
            assertEquals(listOf("prepare", "connect ${Scenario.BIKE}"), scenario.fake.osCalls.take(2))
            val attempt = scenario.archive.log.first { it.event == "connectAttempt" }
            assertEquals(LogValue.Integer(1), attempt.field("attempt"))
            assertEquals(LogValue.Flag(true), attempt.field("adopt"))
            assertEquals(LogValue.Flag(true), attempt.field("known"))
        }
    }

    @Test
    fun aRadioThatIsOffAtLaunchShowsItsFailureAndKeepsTheBikeUntilItTurnsOn() {
        val scenario = remembering(Platform.ios)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.radio(RadioState.poweredOff)
            val off = scenario.connection { it.failure?.code == FailureCode.bluetoothOff }
            assertEquals(ConnectionState.idle, off.state)
            assertEquals(BikeId(Scenario.BIKE), off.bike?.bikeId)
            delay(30_000)
            assertTrue(scenario.fake.osCalls.none { it.startsWith("connect") })

            scenario.fake.os.radio(RadioState.poweredOn)
            scenario.connection { it.state == ConnectionState.connected }
            assertEquals(1, scenario.fake.osCalls.count { it.startsWith("connect") })
            assertTrue(scenario.mutations().none { it == Mutation.SelectBike(null) })
            assertEquals(
                listOf(LogValue.Text("unknown"), LogValue.Text("poweredOff"), LogValue.Text("poweredOn")),
                scenario.archive.log.filter { it.event == "radio" }.map { it.field("state") },
            )
        }
    }

    @Test
    fun anUnreachableRememberedBikeGetsFiveTriesThenIsForgotten() {
        val scenario = remembering(Platform.ios)
        scenario.linkUps = false
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.radio(RadioState.poweredOn)
            val gaveUp = scenario.connection { it.failure?.code == FailureCode.bikeUnavailable }
            assertEquals(ConnectionState.idle, gaveUp.state)
            delay(1_000)
            assertEquals(5, scenario.fake.osCalls.count { it.startsWith("connect") })
            assertEquals(Mutation.SelectBike(null), scenario.mutations().last())
            assertEquals(
                LogValue.Text("gaveUp"),
                scenario.archive.log.single { it.event == "bikeForgotten" }.field("reason"),
            )
        }
    }

    @Test
    fun aBikeTheIphoneNoLongerKnowsStopsTheLaunchConnectWithTheBikeKept() {
        val scenario = remembering(Platform.ios, known = false)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.radio(RadioState.poweredOn)
            scenario.connection { it.state == ConnectionState.reconnecting }
            val stopped = scenario.connection { it.state == ConnectionState.idle }
            assertNull(stopped.failure)
            delay(60_000)
            assertEquals(ConnectionState.idle, scenario.connection().state)
            assertTrue(scenario.mutations().none { it == Mutation.SelectBike(null) })
        }
    }

    @Test
    fun androidConnectsItsRememberedBikeAtTheProcesssFirstForegroundOnly() {
        val scenario = remembering(Platform.android)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            delay(5_000)
            assertTrue(scenario.fake.osCalls.none { it.startsWith("connect") })
            scenario.application.host.foregroundChanged(true)
            scenario.connection { it.state == ConnectionState.connected }
            scenario.application.host.foregroundChanged(false)
            scenario.application.host.foregroundChanged(true)
            delay(5_000)
            assertEquals(1, scenario.fake.osCalls.count { it.startsWith("connect") })
        }
    }

    @Test
    fun aBluetoothRelaunchHoldsTheReconnectUntilVerifiedThenPollsAtTheBackgroundRate() {
        val scenario = remembering(Platform.ios)
        scenario.test {
            assertEquals(
                LaunchResult.Ready,
                scenario.application.host.launch(LaunchContext(LaunchReason.bluetoothRelaunch)),
            )
            scenario.radio(RadioState.poweredOn)
            scenario.connection { it.state == ConnectionState.reconnecting }
            runCurrent()
            assertEquals(setOf(HoldKind.reconnect), scenario.keepAlive.held)
            scenario.connection { it.state == ConnectionState.connected }
            assertEquals(emptySet(), scenario.keepAlive.held)
            val from = scenario.sentAtUs.size
            delay(10_000)
            val gaps = scenario.sentAtUs.drop(from).zipWithNext { a, b -> b - a }
            assertTrue(gaps.isNotEmpty() && gaps.all { it >= 1_000_000 }, "$gaps")
        }
    }

    private suspend fun Scenario.radio(state: RadioState) {
        while ("prepare" !in fake.osCalls) delay(1)
        fake.os.radio(state)
    }

    private fun remembering(platform: Platform, known: Boolean = true): Scenario {
        val scenario = Scenario(platform)
        if (known) scenario.fake.os.remembered(Scenario.BIKE)
        scenario.test {
            scenario.archive.seed(
                WriteBatch(
                    listOf(
                        Mutation.RememberController(
                            BikeId(Scenario.BIKE),
                            ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3"),
                        ),
                        Mutation.SelectBike(BikeId(Scenario.BIKE)),
                    )
                )
            )
        }
        return scenario
    }

    private fun app.powerlog.application.store.LogLine.field(key: String): LogValue? =
        fields.firstOrNull { it.key == key }?.value
}
