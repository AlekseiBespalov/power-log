package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideAppendBoundsTest {

    @Test
    fun appendIdentityAndDistanceQueriesUseCompleteOriginalKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "append-bounds-${UUID.randomUUID()}.sqlite"
        RideStore(context, name).use { store ->
            val id =
                store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading("2026-01-01T00:00:00Z", 0),
                )
            val queries = mutableListOf<Pair<String, Array<Any>>>()
            store.queryObserver = { sql, args -> queries.add(sql to args.copyOf()) }
            val distance = RideDistance(store)
            val values = mapOf("controllerSpeedMps" to 10.0)
            val descriptor = "X6|fixture|5.3"
            store.transaction {
                repeat(3) { i ->
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            "2026-01-01T00:00:0${i}Z",
                            "telemetry",
                            true,
                            0,
                            values,
                            descriptor,
                            "fixture",
                            original = OriginalIdentity(UUID.randomUUID().toString(), "controller", i + 1L),
                        )
                    distance.append(id, row, i.toDouble(), values, true, 0, "fixture", descriptor, false)
                }
            }
            assertEquals(
                10.0,
                store.metricObservation(id, "controllerDistanceMeters", 2L)?.values?.get("controllerDistanceMeters"),
            )
            store.queryObserver = null
            fun plans(sql: String, args: Array<Any>): List<String> =
                store.select("EXPLAIN QUERY PLAN $sql", args) { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                }
            val elapsed = store.metricQuery(id, "controllerDistanceMeters", elapsedOrder = true)
            val elapsedPlans =
                plans(
                    "SELECT * FROM ($elapsed) WHERE elapsed_us>=? AND elapsed_us<? ORDER BY elapsed_us,producer,producer_sequence,id LIMIT 16",
                    arrayOf(id, 0L, 3000000L),
                )
            assertTrue(
                "Distance elapsed reads scan the ride: $elapsedPlans",
                elapsedPlans.any { "SEARCH o " in it && "elapsed_us>" in it },
            )
            assertTrue(
                "Distance elapsed reads scan intervals: $elapsedPlans",
                elapsedPlans.any { "SEARCH d " in it && "to_id=?" in it },
            )
            assertFalse("Distance elapsed reads sort the ride: $elapsedPlans", elapsedPlans.any { "TEMP B-TREE" in it })
            val identity = queries.first { "SELECT id,event_id,producer,producer_sequence,evidence,kind" in it.first }
            val identityPlans = plans(identity.first, identity.second)
            for (table in listOf("observations", "lifecycle")) {
                val searches = identityPlans.filter { it.startsWith("SEARCH $table ") }
                assertTrue("$table identity lookups scan the ride: $identityPlans", searches.isNotEmpty())
                assertTrue(
                    "$table identity lookups scan the ride: $identityPlans",
                    searches.all {
                        "(ride=? AND event_id=?)" in it || "(ride=? AND producer=? AND producer_sequence=?)" in it
                    },
                )
            }
            val interval = queries.first { it.first.startsWith("SELECT 1 FROM distance_intervals") }
            val distancePlans = plans(interval.first, interval.second)
            assertTrue(
                "Distance idempotency scans the ride: $distancePlans",
                distancePlans.any { "(ride=? AND source=? AND to_id=?)" in it },
            )
            val metric = queries.first { it.first.startsWith("SELECT * FROM (") && "distance_intervals" in it.first }
            val metricPlans = plans(metric.first, metric.second)
            assertTrue(
                "Distance point reads scan the ride: $metricPlans",
                metricPlans.any { "(ride=? AND source=? AND to_id=?" in it },
            )
        }
        context.deleteDatabase(name)
    }
}
