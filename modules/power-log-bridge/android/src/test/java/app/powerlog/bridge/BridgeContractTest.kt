package app.powerlog.bridge

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import app.powerlog.core.presentation.PlatformCapabilities
import app.powerlog.core.presentation.RideCapabilities
import app.powerlog.core.presentation.RideFailure
import expo.modules.kotlin.exception.CodedException
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private val idle =
        RideSnapshot(
            capabilities = PlatformCapabilities.android(true),
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
            failure = null,
        )

    private val failures by lazy { JSONObject(contractFixture("presentation/failures.json").readText()).map() }

    private fun androidFailureCases(kind: String) =
        (((failures["bridge"] as Map<*, *>)[kind] as Map<*, *>)["cases"] as List<*>)
            .map { it as Map<*, *> }
            .filter { it["platform"] == "android" }
            .also { assertTrue(kind, it.isNotEmpty()) }

    private fun assertPlain(context: String, text: String?) {
        assertFalse(context, text.isNullOrBlank())
        for (fragment in failures["forbidden"] as List<*>) assertFalse(
            "$context: $fragment",
            text!!.contains(fragment as String),
        )
    }

    private fun nativeFailure(detail: String, store: RideStore): () -> Any? =
        when {
            detail.startsWith("android.database.sqlite.SQLiteFullException: ") -> {
                { throw SQLiteFullException(detail.substringAfter(": ")) }
            }
            detail.startsWith("java.lang.IllegalStateException: ") -> {
                { throw IllegalStateException(detail.substringAfter(": ")) }
            }
            detail == "Ride was deleted" -> {
                { store.revision(UUID.randomUUID().toString()) }
            }
            else -> throw AssertionError("No Android failure stands for $detail")
        }

    private fun <T> withStore(body: (RideStore) -> T): T {
        val store = RideStore(RuntimeEnvironment.getApplication(), "bridge-contract-${UUID.randomUUID()}.sqlite")
        try {
            return body(store)
        } finally {
            store.close()
        }
    }

    @Test
    fun everyAndroidSnapshotUsesTheProductionWireConverter() {
        val id = "3f6c1f0e-6f2d-4c43-9b7e-2a4d8f1b9c21"
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
                        capabilities = PlatformCapabilities.android(false),
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
                    capability["gps"] as Boolean,
                    capability["healthProvider"] as String?,
                    capability["foregroundOnly"] as Boolean,
                )
            @Suppress("UNCHECKED_CAST") val input = case["input"] as Payload
            val rate = (case["expected"] as Map<*, *>)["sampleHz"]
            if (input["sampleHz"] == null)
                assertThrows(IllegalArgumentException::class.java) { BridgeInputs.ride(input) }
            val options = BridgeInputs.ride(input + ("sampleHz" to (input["sampleHz"] ?: rate))).effective(typed)
            assertStructure(
                case["name"] as String,
                case["expected"],
                mapOf(
                    "sampleHz" to options.sampleHz,
                    "indoor" to options.indoor,
                    "useWatch" to options.useWatch,
                    "saveToHealth" to options.saveToHealth,
                    "recordGPS" to options.recordGPS,
                    "distanceSource" to options.distanceSource,
                    "batteryConsumptionSource" to options.batteryConsumptionSource,
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
                PlatformCapabilities.android(false),
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
        for (field in listOf("collectionRevision")) {
            assertTrue(roundTrip.containsKey(field))
            assertNull(roundTrip[field])
        }
    }

    @Test
    fun everySharedFailureTextIsPlainAndCarriedByTheTypedFailure() {
        val vocabulary = (failures["vocabulary"] as List<*>).map { it as Map<*, *> }
        assertEquals(vocabulary.map { it["code"] }.toSet(), RideFailure.entries.map { it.name }.toSet())
        for (entry in vocabulary) {
            val failure = RideFailure.valueOf(entry["code"] as String)
            assertEquals("${entry["message"]} ${entry["nextStep"]}".trimEnd(), failure.text)
            assertEquals(
                "${entry["message"]} ${entry["androidNextStep"] ?: entry["nextStep"]}".trimEnd(),
                failure.androidText,
            )
            assertEquals(failure.text, RideFailureException(failure).message)
            assertSame(failure, RideFailureException(failure.name).failure)
            assertPlain(failure.name, failure.text)
        }
    }

    @Test
    fun androidFailureSnapshotCasesUseTheProductionClassifierAndWire() {
        withStore { store ->
            for (case in androidFailureCases("snapshot")) {
                val name = case["name"] as String
                val expected = case["wire"] as Map<*, *>
                val failure =
                    (case["nativeDetail"] as String?)?.let { detail ->
                        RideFailures.classify(
                            assertThrows(Throwable::class.java) { nativeFailure(detail, store)() },
                            case["stage"] as String?,
                        )
                    }
                val wire = idle.copy(failure = failure, healthBusy = expected["healthBusy"] as Boolean).toWireMap()
                for ((key, value) in expected) assertStructure("$name.$key", value, wire[key])
                assertEquals(name, failure?.text, wire["error"])
                if (failure != null) assertPlain(name, wire["error"] as String)
            }
        }
    }

    @Test
    fun androidRejectionCasesReachJavaScriptOnlyAsTheSharedCodeAndText() {
        withStore { store ->
            for (case in androidFailureCases("rejection")) {
                val name = case["function"] as String
                val reply = Reply()
                submit(
                    reply,
                    { it.run() },
                    case["stage"] as String?,
                    nativeFailure(case["nativeDetail"] as String, store),
                )
                val rejection = assertThrows(name, Rejection::class.java) { reply.get() }
                assertEquals(name, case["code"], rejection.code)
                assertEquals(name, case["message"], rejection.message)
                assertPlain(name, rejection.message)
            }
        }
    }

    @Test
    fun onlyTypedFailuresClassifyAndNativeTextNeverDoes() {
        for (input in failures["unknownInputs"] as List<*>) assertEquals(
            "$input",
            RideFailure.unknown,
            RideFailures.classify(IllegalStateException(input as String?)),
        )
        for (failure in RideFailure.entries) {
            assertEquals(RideFailure.unknown, RideFailures.classify(IllegalStateException(failure.name), "finish"))
            assertEquals(failure, RideFailures.classify(CodedException(failure.name, "native detail", null)))
        }
        val enospc = IOException("write failed: ENOSPC", ErrnoException("write", OsConstants.ENOSPC))
        val cases =
            listOf(
                Triple(SQLiteFullException("database or disk is full"), "capture", RideFailure.storageFull),
                Triple(enospc, null, RideFailure.storageFull),
                Triple(SQLiteDatabaseLockedException("database is locked"), null, RideFailure.storageBusy),
                Triple(CodedException("ERR_READ_CONTENTION", "native detail", null), "read", RideFailure.storageBusy),
                Triple(SQLiteException("SQL logic error"), "bike", RideFailure.storageFailed),
                Triple(SecurityException("missing permission"), "location", RideFailure.locationDenied),
                Triple(SecurityException("missing permission"), "health", RideFailure.healthDenied),
                Triple(SecurityException("missing permission"), "start", RideFailure.startInterrupted),
                Triple(IllegalStateException("GATT 133"), "bike", RideFailure.bikeUnavailable),
                Triple(RideFailureException(RideFailure.rideDeleted), "bike", RideFailure.rideDeleted),
                Triple(java.io.IOException("No such file"), null, RideFailure.unknown),
            )
        for ((error, stage, failure) in cases) assertEquals(
            "$error at $stage",
            failure,
            RideFailures.classify(error, stage),
        )
    }
}
