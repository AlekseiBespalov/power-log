package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.powerlog.core.lifecycle.*
import java.io.File
import java.util.UUID
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideFixtureTest {

    @Test
    fun startOptionsFixtureCommitsFrozenAndroidLocalOwners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture =
            JSONObject(instrumentation.context.assets.open("lifecycle/F09.json").bufferedReader().use { it.readText() })
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val input = row.getJSONObject("requested")
            if (input.optBoolean("useWatch")) continue
            RideStore(instrumentation.targetContext, "start-${UUID.randomUUID()}.sqlite").use { store ->
                val before = store.lifecycleState()
                val decision =
                    store.startLifecycle(
                        before,
                        LifecycleStart(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            RecordingClockReading("2026-01-01T00:00:00Z", 0).lifecycle(UUID.randomUUID().toString()),
                            app.powerlog.core.presentation.RequestedRideOptions(
                                input.optBoolean("indoor"),
                                input.opt("useWatch") as? Boolean,
                                input.opt("saveToHealth") as? Boolean,
                                input.opt("recordGPS") as? Boolean,
                                (input.opt("sampleHz") as? Number)?.toDouble(),
                            ),
                            app.powerlog.core.presentation.RideCapabilities(
                                true,
                                false,
                                true,
                                false,
                                true,
                                "healthConnect",
                            ),
                            phoneRecovery = LifecycleOwnerRecovery.local,
                        ),
                    )
                val admitted = store.commitLifecycle(before, decision)
                val owner = admitted.state.ride!!
                val metadata = store.metadata(owner.key.rideId)
                val expected = row.getJSONObject("expected")
                for (key in listOf("indoor", "saveToHealth", "recordGPS", "sampleHz")) assertEquals(
                    row.getString("name") + ":" + key,
                    expected.get(key),
                    metadata[key],
                )
                assertEquals("local", owner.ownerRecovery.name)
                assertEquals("preparing", metadata["phase"])
                assertEquals(1, admitted.effects.size)
            }
        }
    }

    @Test
    fun deletionWaitsForNativeReadPinsAndAnAdmittedExportPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "delete-fixture-${UUID.randomUUID()}.sqlite"
        val store = RideStore(context, name)
        try {
            val id =
                store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading("2026-01-01T00:00:00Z", 0),
                )
            store.seal(id, RideTiming(10.0, 10.0, "2026-01-01T00:00:10Z"))
            val pin = store.pin(id)
            assertThrows(IllegalStateException::class.java) { store.deleteHistorical(id, store.lifecycleState()) }
            store.withPin(pin) { assertEquals("completed", store.metadata(id)["phase"]) }
            val queue = ArrayDeque<Runnable>()
            val source = ExportSource(store, RideDistance(store), java.util.concurrent.Executor { queue.add(it) })
            var opened: Any? = null
            var rejected: String? = null
            fun reply(onResolved: (Any?) -> Unit) =
                object : expo.modules.kotlin.Promise {
                    override fun resolve(value: Any?) {
                        onResolved(value)
                    }

                    override fun reject(code: String?, message: String?, cause: Throwable?) {
                        rejected = code
                    }
                }
            source.open(
                mapOf(
                    "rideId" to id,
                    "kind" to "zip",
                    "context" to mapOf("exportedAt" to "2026-01-01T00:00:30Z", "platform" to "android"),
                ),
                reply { opened = it },
            )
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertNull(rejected)
            val session = (opened as Map<*, *>)["session"] as String
            source.page(
                mapOf("session" to session, "projection" to "telemetry", "after" to null),
                reply { fail("A closed export page completed") },
            )
            source.close(session)
            assertThrows(IllegalStateException::class.java) { store.deleteHistorical(id, store.lifecycleState()) }
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertEquals("cancelled", rejected)
            store.deleteHistorical(id, store.lifecycleState())
            assertFalse(store.exists(id))
            source.closeAll()
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun finalizedSyntheticRideHasConsistentDetailAndChartOriginals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ui-fixture.sqlite"
        context.deleteDatabase(name)
        val store = RideStore(context, name)
        val distance = RideDistance(store)
        val monitor = RideMonitor(store, distance)
        val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        try {
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = false, sampleHz = 8),
                    RecordingClockReading(iso(1767225600000L), 0),
                    example = true,
                )
            store.update(
                id,
                "running",
                RideTiming(0.0, 0.0, iso(1767225600000L)),
            )
            for (batch in 0 until 2400 step 128) store.transaction {
                for (i in batch until minOf(batch + 128, 2400)) {
                    val t = i / 8.0
                    val active = t !in 100.0..<110.0
                    val segment = if (t < 100) 0 else if (t < 110) 1 else 2
                    val values =
                        mapOf(
                            "humanPowerW" to if (active) 160 + 60 * sin(t / 10) else 0.0,
                            "cadenceRpm" to if (active) 80 + 10 * sin(t / 15) else 0.0,
                            "motorInputPowerW" to if (active) 450 + 200 * sin(t / 10) else 0.0,
                            "batteryVoltageV" to 54 - t / 100,
                            "batteryCurrentA" to 9.0,
                            "controllerSpeedMps" to if (active) 8.0 else 0.0,
                        )
                    val row =
                        store.insert(
                            id,
                            t,
                            iso(1767225600000L + i * 125),
                            "telemetry",
                            active,
                            segment,
                            values,
                            "X6|synthetic|5.3",
                            "synthetic",
                        )
                    distance.append(
                        id,
                        row,
                        t,
                        values,
                        active,
                        segment,
                        "synthetic",
                        "X6|synthetic|5.3",
                        false,
                    )
                }
            }
            store.lifecycle(id, RideTiming(100.0, 100.0, iso(1767225700000L)), "pause")
            store.lifecycle(id, RideTiming(110.0, 100.0, iso(1767225710000L)), "resume")
            store.lifecycle(id, RideTiming(180.0, 170.0, iso(1767225780000L)), "lap")
            store.seal(id, RideTiming(300.0, 290.0, iso(1767225900000L)))
            assertEquals(6, store.readableDatabase.version)
            assertEquals(2400L, store.count(id))
            assertEquals("2405", store.metadata(id)["eventCount"])
            assertEquals(8, store.metadata(id)["sampleHz"])
            val actual = RideDetail(store, distance).detail(id, "auto")["summary"] as Payload
            assertEquals("2405", actual["eventCount"])
            assertEquals("5", actual["lifecycleCount"])
            assertEquals(290.0, actual.num("timerSeconds"), 0.0)
            assertEquals(2.0, actual.num("lapCount"), 0.0)
        } finally {
            store.close()
        }
        context.getDatabasePath(name).copyTo(File(output, "ui-fixture.sqlite"), true)
        context.deleteDatabase(name)
    }
}
