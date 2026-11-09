package app.powerlog.application.contracts

import app.powerlog.domain.core.ride.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleScenariosTest {
    private val document = File("../../tests/fixtures/contract/ports/ble-central.json").readText()

    @Test
    fun everyScenarioPassesOverTheFakeOnEachOfItsPlatforms() {
        val runner = BleScenarioRunner(document)
        val results = Platform.entries.flatMap { platform -> runner.run(platform.name) { FakeBleHarness(platform) } }

        val failures = results.filter { it.failure != null }
        assertEquals(emptyList(), failures.map { "${it.platform}: ${it.scenario}: ${it.failure}" })
        assertEquals(runner.scenarios.sumOf { it.platforms.size }, results.size)
    }

    @Test
    fun everyScenarioNamesKnownPlatformsAndAUniqueName() {
        val runner = BleScenarioRunner(document)
        val known = Platform.entries.map { it.name }.toSet()

        assertTrue(runner.scenarios.isNotEmpty())
        assertEquals(runner.scenarios.size, runner.scenarios.map { it.name }.toSet().size)
        assertEquals(
            emptyList(),
            runner.scenarios.filter { it.platforms.isEmpty() || !known.containsAll(it.platforms) }.map { it.name },
        )
    }

    @Test
    fun theRunnerReportsAWrongEventALeftoverEventAndAnUnknownOccurrence() {
        val runner =
            BleScenarioRunner(
                """
            { "os": { "radio": ["state"] },
              "profiles": {},
              "scenarios": [
                { "name": "wrong", "platforms": ["android"], "steps": [
                  { "call": "prepare" }, { "os": "radio", "args": { "state": "poweredOff" } },
                  { "expect": "radioChanged", "args": { "state": "poweredOn" } } ] },
                { "name": "leftover", "platforms": ["android"], "steps": [
                  { "call": "prepare" }, { "os": "radio", "args": { "state": "poweredOff" } }, { "expectNone": true } ] },
                { "name": "unknown", "platforms": ["android"], "steps": [ { "os": "lightning" } ] },
                { "name": "returns", "platforms": ["android"], "steps": [ { "call": "radio", "returns": "unknown" } ] }
              ] }
            """
            )

        val failures =
            runner.run("android") { FakeBleHarness(Platform.android) }.associate { it.scenario to it.failure }

        assertTrue(
            failures.getValue("wrong")!!.contains("state: expected \"poweredOn\", got poweredOff"),
            failures["wrong"],
        )
        assertTrue(failures.getValue("leftover")!!.contains("unexpected"), failures["leftover"])
        assertTrue(failures.getValue("unknown")!!.contains("unknown OS occurrence"), failures["unknown"])
        assertTrue(failures.getValue("returns")!!.contains("expected \"unknown\", got poweredOn"), failures["returns"])
    }

    @Test
    fun manufacturerBytesAreComparedByContent() {
        val runner =
            BleScenarioRunner(
                """
            { "os": { "radio": ["state"], "discovered": ["bike", "name", "rssi", "services", "manufacturerData", "atMonoUs"] },
              "profiles": { "uart": { "service": "s", "notify": "n", "write": "w", "namePrefixes": [] } },
              "scenarios": [ { "name": "bytes", "platforms": ["android"], "steps": [
                { "call": "startSearch", "args": { "profiles": ["uart"], "inventory": true } },
                { "os": "discovered", "args": { "bike": "D1", "name": null, "rssi": null, "services": [], "manufacturerData": "AQI=", "atMonoUs": 0 } },
                { "expect": "advertised", "args": { "advertisement": { "manufacturerData": "AQM=" } } } ] } ] }
            """
            )

        val failure = runner.run("android") { FakeBleHarness(Platform.android) }.single().failure

        assertTrue(failure!!.contains("manufacturerData: expected bytes \"AQM=\", got bytes AQI="), failure)
    }
}
