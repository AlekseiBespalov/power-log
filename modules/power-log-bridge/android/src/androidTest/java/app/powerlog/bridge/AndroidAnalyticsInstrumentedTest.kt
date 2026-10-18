package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAnalyticsInstrumentedTest {
    @Test
    fun contractBarrierSurvivesRealSqliteCheckpointAndSummaryRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture =
            JSONObject(
                instrumentation.context.assets.open("analytics/iphone-reference.json").bufferedReader().use {
                    it.readText()
                }
            )
        val cases = fixture.getJSONArray("cases")
        val case =
            (0 until cases.length())
                .map { cases.getJSONObject(it) }
                .single { it.getString("name") == "invalid-barrier" }
        val input = case.getJSONObject("input")
        val context = instrumentation.targetContext
        val name = "analytics-${UUID.randomUUID()}.sqlite"
        var id = ""
        try {
            RideStore(context, name).use { store ->
                id =
                    store.create(
                        RideOptions(true, saveToHealth = false, recordGPS = false),
                        RecordingClockReading(
                            input.getJSONObject("create").getString("startedAt"),
                            0,
                        ),
                    )
                store.transaction {
                    val events = input.getJSONArray("events")
                    for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index)
                        val values =
                            event.getJSONObject("payload").let { payload ->
                                payload.keys().asSequence().associateWith { payload.getDouble(it) }
                            }
                        store.insert(
                            id,
                            event.getDouble("elapsedSeconds"),
                            event.getString("timestamp"),
                            "telemetry",
                            true,
                            0,
                            values,
                            original = OriginalIdentity(event.getString("eventId"), "controller", index + 1L),
                        )
                    }
                }
                store.seal(id, RideTiming(1.0, 1.0, input.getString("endedAt")))
            }
            RideStore(context, name).use { store ->
                val stats = store.analytics.stats(id, "humanPowerW", 0.0, 32.0)
                assertEquals(2L, stats["count"])
                assertEquals(0.0, stats.num("coveredSeconds"), 0.0)
                store.writableDatabase.execSQL(
                    "UPDATE analytics SET payload='broken' WHERE ride=?",
                    arrayOf(id),
                )
                assertEquals(stats, store.analytics.stats(id, "humanPowerW", 0.0, 32.0))
                val distance = RideDistance(store)
                val detail = RideDetail(store, distance).detail(id, "auto")["summary"] as Map<*, *>
                assertFalse(detail.containsKey("riderWorkJoules"))
                assertEquals(100.0, detail["maximumRiderPowerW"])
                assertTrue(detail.containsKey("completeness"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
