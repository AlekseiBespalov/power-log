package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.StreamAnswer
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeHealthProvider
import app.powerlog.application.fakes.FakeLocationSource
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.recording.PermissionsView
import app.powerlog.application.settings.RideToggle
import app.powerlog.application.settings.SettingsChange
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.recording.PermissionAction
import app.powerlog.domain.recording.PermissionNeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.async

class PermissionsScenarioTest {
    private val placeholder =
        PermissionStates(
            PermissionState.unavailable,
            PermissionState.unavailable,
            null,
            null,
            PermissionState.unavailable,
        )
    private val granted = Scenario.granted

    @Test
    fun savedSettingsNeedingHealthAndGpsGetTheActionAndNeedOfTheRuleOrderForEachPermissionState() {
        for (platform in listOf(Platform.ios, Platform.android, Platform.web)) {
            val scenario = Scenario(platform)
            scenario.location = FakeLocationSource(scenario.clock, scenario.post)
            scenario.test {
                assertEquals(LaunchResult.Ready, scenario.launch())
                val settings =
                    if (platform == Platform.web) PermissionAction.check else PermissionAction.openAppSettings
                val gpsOnly =
                    listOf(
                        placeholder to (PermissionAction.check to PermissionNeed.location),
                        granted to null,
                        granted.copy(location = PermissionState.notDetermined) to
                            (PermissionAction.request to PermissionNeed.location),
                        granted.copy(location = PermissionState.notDetermined, locationServicesOn = false) to
                            (PermissionAction.request to PermissionNeed.location),
                        granted.copy(locationServicesOn = false) to (settings to PermissionNeed.locationServices),
                        granted.copy(location = PermissionState.restricted) to
                            (settings to PermissionNeed.locationRestricted),
                        granted.copy(location = PermissionState.denied) to (settings to PermissionNeed.location),
                        granted.copy(preciseLocation = false) to
                            (PermissionAction.openAppSettings to PermissionNeed.preciseLocation),
                    )
                scenario.save(health = false, gps = true)
                for ((states, expected) in gpsOnly) assertEquals(
                    expected,
                    scenario.actionFor(states),
                    "$platform GPS: $states",
                )

                scenario.save(health = true, gps = false)
                for (states in listOf(placeholder, granted)) assertNull(scenario.actionFor(states), "$platform Health")
                scenario.save(health = true, gps = true)
                for ((states, expected) in gpsOnly) assertEquals(
                    expected,
                    scenario.actionFor(states),
                    "$platform Health and GPS: $states",
                )

                scenario.save(health = false, gps = false)
                for (states in listOf(placeholder, granted)) assertNull(scenario.actionFor(states), "$platform neither")
            }
        }
    }

    @Test
    fun withoutALocationSourceGpsNeedsNoPermission() {
        for (platform in listOf(Platform.ios, Platform.android, Platform.web)) {
            val scenario = Scenario(platform)
            scenario.test {
                assertEquals(LaunchResult.Ready, scenario.launch())
                scenario.save(health = false, gps = true)
                for (states in
                    listOf(
                        placeholder,
                        granted,
                        granted.copy(location = PermissionState.notDetermined),
                        granted.copy(location = PermissionState.denied),
                    )) assertNull(scenario.actionFor(states), "$platform: $states")
            }
        }
    }

    @Test
    fun aPhonesHealthProviderThatIsNotAvailableGivesUnavailableForHealth() {
        for (platform in listOf(Platform.ios, Platform.android)) {
            val scenario = Scenario(platform)
            val provider =
                if (platform == Platform.ios) FakeHealthProvider.apple(scenario.clock, scenario.post)
                else FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
            provider.access.availability = HealthAvailability.unsupported
            scenario.health = provider
            scenario.test {
                assertEquals(LaunchResult.Ready, scenario.launch())
                scenario.save(health = true, gps = false)
                assertEquals(
                    PermissionAction.unavailable to PermissionNeed.health,
                    scenario.actionFor(granted),
                    platform.name,
                )
                scenario.save(health = false, gps = false)
                assertNull(scenario.actionFor(granted), platform.name)
            }
        }
    }

    @Test
    fun theHealthWriteGrantsGiveTheActionOfTheRuleOrderForEachHealthState() {
        val gpsWrites = listOf(HealthComponent.route, HealthComponent.cyclingSpeed, HealthComponent.cyclingDistance)
        val appleWrites = listOf(HealthComponent.workout, HealthComponent.riderPower, HealthComponent.cyclingCadence)
        val scenario = Scenario(Platform.ios)
        val provider = FakeHealthProvider.apple(scenario.clock, scenario.post)
        scenario.health = provider
        scenario.location = FakeLocationSource(scenario.clock, scenario.post)
        fun writes(vararg changed: Pair<HealthComponent, PermissionState>) =
            (appleWrites + gpsWrites).associateWith { PermissionState.granted } + changed.toMap()
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.save(health = true, gps = true)
            val outdoor =
                listOf(
                    Triple(writes(), granted, null),
                    Triple(
                        writes(HealthComponent.workout to PermissionState.notDetermined),
                        granted,
                        PermissionAction.request to PermissionNeed.health,
                    ),
                    Triple(
                        writes(HealthComponent.route to PermissionState.notDetermined),
                        granted,
                        PermissionAction.request to PermissionNeed.health,
                    ),
                    Triple(
                        writes(HealthComponent.workout to PermissionState.denied),
                        granted,
                        PermissionAction.healthHelp to PermissionNeed.health,
                    ),
                    Triple(
                        writes(HealthComponent.workout to PermissionState.denied),
                        granted.copy(location = PermissionState.notDetermined),
                        PermissionAction.request to PermissionNeed.location,
                    ),
                    Triple(
                        writes(HealthComponent.workout to PermissionState.denied),
                        granted.copy(location = PermissionState.denied),
                        PermissionAction.healthHelp to PermissionNeed.health,
                    ),
                    Triple(
                        writes(HealthComponent.workout to PermissionState.unavailable),
                        granted,
                        PermissionAction.check to PermissionNeed.health,
                    ),
                )
            for ((grantsWrite, states, expected) in outdoor) {
                provider.access.grants = provider.access.grants.copy(write = grantsWrite)
                assertEquals(expected, scenario.actionFor(states), "outdoor $grantsWrite $states")
            }
            scenario.save(health = true, gps = false)
            for (component in gpsWrites) {
                provider.access.grants =
                    provider.access.grants.copy(write = writes(component to PermissionState.notDetermined))
                assertNull(scenario.actionFor(granted.copy(location = PermissionState.denied)), "indoor $component")
            }
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(SettingsChange.Toggle(RideToggle.useWatch, true)),
            )
            provider.access.grants =
                provider.access.grants.copy(write = writes(HealthComponent.workout to PermissionState.denied))
            assertNull(scenario.actionFor(granted), "a Watch ride leaves Health to the Watch")
        }
    }

    @Test
    fun healthConnectAsksAgainForAnyRequiredWriteNotGranted() {
        val scenario = Scenario(Platform.android)
        val provider = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        scenario.health = provider
        scenario.location = FakeLocationSource(scenario.clock, scenario.post)
        val all =
            listOf(
                    HealthComponent.workout,
                    HealthComponent.riderPower,
                    HealthComponent.cyclingDistance,
                    HealthComponent.cyclingSpeed,
                    HealthComponent.route,
                )
                .associateWith { PermissionState.granted }
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.save(health = true, gps = true)
            provider.access.grants = provider.access.grants.copy(write = all)
            assertNull(scenario.actionFor(granted))
            for (component in all.keys) for (state in listOf(PermissionState.notDetermined, PermissionState.denied)) {
                provider.access.grants = provider.access.grants.copy(write = all + (component to state))
                assertEquals(
                    PermissionAction.request to PermissionNeed.health,
                    scenario.actionFor(granted),
                    "$component $state",
                )
            }
            scenario.save(health = true, gps = false)
            provider.access.grants =
                provider.access.grants.copy(write = all + (HealthComponent.route to PermissionState.notDetermined))
            assertNull(scenario.actionFor(granted))
            provider.access.grants =
                provider.access.grants.copy(write = all + (HealthComponent.riderPower to PermissionState.notDetermined))
            assertEquals(PermissionAction.request to PermissionNeed.health, scenario.actionFor(granted))
        }
    }

    @Test
    fun thePermissionStreamAnswersAgainAfterAForegroundChangeARefreshAFailedReadAndARequest() {
        val scenario = Scenario(Platform.android)
        scenario.location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.permissions.states = granted.copy(location = PermissionState.notDetermined)
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.save(health = false, gps = true)
            val api = scenario.api
            val first = assertIs<StreamAnswer.Value<PermissionsView>>(api.recording.permissions.next(Empty, null, "p"))
            assertEquals(PermissionAction.request, first.value.action)

            scenario.permissions.states = granted
            val foreground = async { api.recording.permissions.next(Empty, first.revision, "p") }
            scenario.application.host.foregroundChanged(true)
            val shown = assertIs<StreamAnswer.Value<PermissionsView>>(foreground.await())
            assertEquals(PermissionsView(null, null, null), shown.value)

            scenario.statusFailure = PortError(PortErrorKind.failed)
            assertEquals(Outcome.Ok(Unit), api.recording.refreshPermissions(Empty))
            val failed =
                assertIs<StreamAnswer.Value<PermissionsView>>(
                    api.recording.permissions.next(Empty, shown.revision, "p")
                )
            assertEquals(FailureCode.unknown, failed.value.failure?.code)
            assertNull(failed.value.action)
            scenario.statusFailure = null
            assertEquals(Outcome.Ok(Unit), api.recording.refreshPermissions(Empty))
            val retried =
                assertIs<StreamAnswer.Value<PermissionsView>>(
                    api.recording.permissions.next(Empty, failed.revision, "p")
                )
            assertEquals(PermissionsView(null, null, null), retried.value)

            scenario.permissions.states = granted.copy(location = PermissionState.notDetermined)
            assertEquals(Outcome.Ok(Unit), api.recording.refreshPermissions(Empty))
            val asking =
                assertIs<StreamAnswer.Value<PermissionsView>>(
                    api.recording.permissions.next(Empty, retried.revision, "p")
                )
            assertEquals(
                PermissionAction.request to PermissionNeed.location,
                asking.value.action to asking.value.actionFor,
            )
            scenario.permissions.requests.script = { Answer.Ok(granted) }
            with(scenario) { search() }
            assertEquals(listOf(setOf(PermissionKind.bluetooth)), scenario.permissions.requests.received)
            val answered =
                assertIs<StreamAnswer.Value<PermissionsView>>(
                    api.recording.permissions.next(Empty, asking.revision, "p")
                )
            assertEquals(PermissionsView(null, null, null), answered.value)
        }
    }

    @Test
    fun androidRequestsHealthConnectFirstThenOneOsPromptAndNothingWhenAllIsGranted() {
        val scenario = Scenario(Platform.android)
        val provider = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        scenario.health = provider
        scenario.location = FakeLocationSource(scenario.clock, scenario.post)
        scenario.device.snapshot = scenario.device.snapshot.copy(sdkLevel = 34)
        scenario.permissions.states =
            PermissionStates(
                PermissionState.notDetermined,
                PermissionState.notDetermined,
                null,
                true,
                PermissionState.notDetermined,
            )
        val asked = mutableListOf<String>()
        provider.access.requests.script = { scope ->
            asked += "health"
            provider.access.grants =
                provider.access.grants.copy(
                    write = scope.write.associateWith { PermissionState.granted },
                    read = scope.read.associateWith { PermissionState.granted },
                    route = PermissionState.granted,
                )
            Answer.Ok(Unit)
        }
        scenario.permissions.requests.script = { kinds ->
            asked += "os ${kinds.sortedBy { it.name }}"
            scenario.permissions.states = Scenario.granted
            Answer.Ok(Scenario.granted)
        }
        scenario.test {
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.save(health = true, gps = true)
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.requestPermissions(Empty))
            assertEquals(
                listOf(
                    "health",
                    "os ${listOf(PermissionKind.bluetooth, PermissionKind.location, PermissionKind.notifications)}",
                ),
                asked,
            )
            asked.clear()
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.requestPermissions(Empty))
            assertEquals(emptyList(), asked)
        }
    }

    private suspend fun Scenario.save(health: Boolean, gps: Boolean) {
        for (change in
            listOf(
                SettingsChange.Toggle(RideToggle.useWatch, false),
                SettingsChange.Toggle(RideToggle.saveToHealth, health),
                SettingsChange.Toggle(RideToggle.indoor, !gps),
                SettingsChange.RecordGpsTo(null),
            )) assertEquals(Outcome.Ok(Unit), api.settings.change(change))
    }

    private suspend fun Scenario.actionFor(states: PermissionStates): Pair<PermissionAction, PermissionNeed>? {
        permissions.states = states
        assertEquals(Outcome.Ok(Unit), api.recording.refreshPermissions(Empty))
        val view =
            assertIs<StreamAnswer.Value<PermissionsView>>(api.recording.permissions.next(Empty, null, "now")).value
        assertNull(view.failure)
        assertEquals(view.action == null, view.actionFor == null)
        return view.action?.let { it to requireNotNull(view.actionFor) }
    }
}
