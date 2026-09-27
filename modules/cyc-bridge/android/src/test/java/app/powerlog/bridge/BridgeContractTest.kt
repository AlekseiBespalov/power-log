package app.powerlog.bridge

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BridgeContractTest {
    private fun assertStructure(path: String, expected: Any?, actual: Any?) {
        when (expected) {
            is Map<*, *> -> {
                assertTrue(path, actual is Map<*, *>)
                val value = actual as Map<*, *>
                assertEquals(path, expected.keys, value.keys)
                for ((key, member) in expected) assertStructure("$path.$key", member, value[key])
            }
            is List<*> -> {
                assertTrue(path, actual is List<*>)
                val value = actual as List<*>
                assertEquals(path, expected.size, value.size)
                expected.forEachIndexed { index, member -> assertStructure("$path[$index]", member, value[index]) }
            }
            is Number -> {
                assertTrue(path, actual is Number)
                assertEquals(path, expected.toDouble(), (actual as Number).toDouble(), 0.0)
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    @Test
    fun everyAndroidSnapshotUsesTheProductionWireConverter() {
        val id = "3f6c1f0e-6f2d-4c43-9b7e-2a4d8f1b9c21"
        val idle =
            RideSnapshot(
                capabilities = RideCapabilities.android(true),
                id = null,
                phase = "idle",
                timerSeconds = 0.0,
                historyRevision = "0",
                lastDeletedWorkoutId = null,
                indoor = false,
                useWatch = false,
                saveToHealth = false,
                recordGPS = false,
                healthKitState = "notRequested",
                streams = RideStreams(RideStream("off"), RideStream("off"), RideGPSStream("off")),
                error = null,
            )
        val cases =
            mapOf(
                "idle" to idle,
                "ride running with Health Connect" to
                    idle.copy(
                        id = id,
                        phase = "running",
                        timerSeconds = 3600.25,
                        historyRevision = "4",
                        saveToHealth = true,
                        recordGPS = true,
                        healthKitState = "pending",
                        streams =
                            RideStreams(
                                RideStream("receiving"),
                                RideStream("off"),
                                RideGPSStream("receiving", accuracyMeters = 4.2),
                            ),
                    ),
                "paused indoor ride without Health Connect" to
                    idle.copy(
                        capabilities = RideCapabilities.android(false),
                        id = id,
                        phase = "paused",
                        timerSeconds = 12.0,
                        historyRevision = "4",
                        indoor = true,
                        streams = RideStreams(RideStream("paused"), RideStream("off"), RideGPSStream("off")),
                    ),
            )
        val fixture = JSONObject(File("../../../tests/fixtures/contract/ride-snapshots.json").readText()).map()
        val android = (fixture["cases"] as List<*>).map { it as Map<*, *> }.filter { it["platform"] == "android" }
        assertEquals(cases.keys, android.map { it["name"] }.toSet())
        for (case in android) {
            val name = case["name"] as String
            val wire = cases.getValue(name).toWireMap()
            assertStructure(name, case["wire"], wire)
            assertStructure(name, case["wire"], JSONObject(json(wire).toString()).map())
        }
    }

    @Test
    fun everyAndroidOptionsCaseUsesParsingAndEffectiveCapabilities() {
        val fixture = rideOptionsFixture()
        val capabilities = fixture["capabilities"] as Map<*, *>
        val cases =
            (fixture["cases"] as List<*>)
                .map { it as Map<*, *> }
                .filter { (it["capabilities"] as String).startsWith("android") }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val capability = capabilities[case["capabilities"]] as Map<*, *>
            val typed =
                RideCapabilities(
                    capability["phoneWorkout"] as Boolean,
                    capability["watchWorkout"] as Boolean,
                    capability["phoneHealth"] as Boolean,
                    capability["watchHealth"] as Boolean,
                    capability["healthProvider"] as String?,
                    capability["gps"] as Boolean,
                    capability["foregroundOnly"] as Boolean,
                )
            @Suppress("UNCHECKED_CAST") val options = BridgeInputs.ride(case["input"] as Payload).effective(typed)
            assertStructure(
                case["name"] as String,
                case["expected"],
                mapOf(
                    "sampleHz" to options.sampleHz,
                    "indoor" to options.indoor,
                    "useWatch" to options.useWatch,
                    "saveToHealth" to options.saveToHealth,
                    "recordGPS" to options.recordGPS,
                ),
            )
        }
    }

    @Test
    fun snapshotRequiresFiniteNonnegativeValuesAndKeepsLargestRevisionExact() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertThrows(
            IllegalArgumentException::class.java
        ) {
            RideGPSStream("receiving", accuracyMeters = value)
        }
        val snapshot =
            RideSnapshot(
                RideCapabilities.android(false),
                null,
                "idle",
                0.0,
                RideStore.MAX_SAFE_REVISION.toString(),
                null,
                false,
                false,
                false,
                false,
                "notRequested",
                RideStreams(RideStream("off"), RideStream("off"), RideGPSStream("off")),
                null,
            )
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertThrows(
            IllegalArgumentException::class.java
        ) {
            snapshot.copy(timerSeconds = value)
        }
        val roundTrip = JSONObject(json(snapshot.toWireMap()).toString()).map()
        assertEquals("9007199254740991", roundTrip["historyRevision"])
        for (field in
            listOf(
                "collectionRevision",
                "sealRevision",
                "verifiedSealRevision",
                "finalizationState",
                "recoveryMessage",
            )) {
            assertTrue(roundTrip.containsKey(field))
            assertNull(roundTrip[field])
        }
    }
}
