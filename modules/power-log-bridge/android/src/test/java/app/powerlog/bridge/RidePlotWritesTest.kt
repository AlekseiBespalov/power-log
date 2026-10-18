package app.powerlog.bridge

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RidePlotWritesTest {
    @Test
    fun chartCapacityKeepsOriginalsAndReturnsSmallerRangeFailure() {
        RideStore(RuntimeEnvironment.getApplication(), "capacity-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                repeat(32_770) { index ->
                    store.insert(
                        id,
                        index / 4000.0,
                        iso(),
                        "telemetry",
                        true,
                        index,
                        mapOf("controllerSpeedMps" to if (index % 2 == 0) 10.0 else -1.0),
                        epoch = "epoch",
                    )
                }
            }
            assertEquals(32_770, store.count(id))
            val error =
                assertThrows(expo.modules.kotlin.exception.CodedException::class.java) {
                    store.analytics.plot(id, "controllerSpeedMps", 0.0, 9.0, 1)
                }
            assertEquals("ERR_CHART_CAPACITY", error.code)
            assertEquals("Choose a smaller range.", error.message)
            assertTrue(store.analytics.plot(id, "controllerSpeedMps", 0.0, 0.01, 1).isNotEmpty())
        }
    }

    @Test
    fun liveCommitsComposeOnlyCompletedParentsAndLeaveTheFinalPartialParentToLeaves() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-live-writes-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            var compositions = 0
            store.queryObserver = { sql, _ ->
                if (sql.startsWith("SELECT bucket,CASE WHEN json_valid(payload)") && "bucket>=? AND bucket<=?" in sql)
                    compositions++
            }
            for (second in 0 until 520) {
                val before = compositions
                store.transaction {
                    for (sample in 0 until 8) {
                        store.insert(
                            id,
                            second + sample / 8.0,
                            iso(),
                            "telemetry",
                            true,
                            0,
                            mapOf("humanPowerW" to 150.0, "cadenceRpm" to 80.0),
                            epoch = "fixture",
                        )
                    }
                    store.update(id, "running", RideTiming(second + 1.0, second + 1.0, iso()))
                }
                val expected = if ((second + 1) % 256 == 0) 2 else 0
                assertEquals("Parent compositions in commit ending at ${second + 1}s", expected, compositions - before)
            }
            assertEquals(4, compositions)
            val beforeStop = compositions
            store.seal(id, RideTiming(520.0, 520.0, iso()))
            assertEquals(beforeStop, compositions)
            store.queryObserver = null
            assertEquals(
                4,
                store.select("SELECT count(*) FROM analytics_plot_blocks WHERE ride=?", arrayOf(id)) {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }
    }

    @Test
    fun captureClosesThePreviousParentWhenTheBoundaryFallsBetweenCommits() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-crossing-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            var compositions = 0
            store.queryObserver = { sql, _ ->
                if (sql.startsWith("SELECT bucket,CASE WHEN json_valid(payload)") && "bucket>=? AND bucket<=?" in sql)
                    compositions++
            }
            store.transaction {
                for (second in 0 until 256) {
                    store.insert(id, second.toDouble(), iso(), "telemetry", true, 0, mapOf("humanPowerW" to 150.0))
                }
                store.insert(id, 255.875, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 150.0))
            }
            assertEquals(0, compositions)
            store.insert(id, 300.0, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 150.0))
            assertEquals(1, compositions)
            store.insert(id, 300.125, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 150.0))
            assertEquals(1, compositions)
        }
    }

    @Test
    fun lateDataRecomposesCompletedParentsAndPreservesPinnedPlots() {
        RideStore(RuntimeEnvironment.getApplication(), "plot-late-writes-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
            store.transaction {
                for (second in 0..300) {
                    store.insert(
                        id,
                        second.toDouble(),
                        iso(),
                        "telemetry",
                        true,
                        0,
                        mapOf("humanPowerW" to 150.0),
                        epoch = "fixture",
                    )
                }
            }
            assertEquals(
                1,
                store.select("SELECT count(*) FROM analytics_plot_blocks WHERE ride=?", arrayOf(id)) {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            val original = store.analytics.plot(id, "humanPowerW", 0.0, 300.0, 1)
            val pin = store.pin(id)
            var compositions = 0
            store.queryObserver = { sql, _ ->
                if (sql.startsWith("SELECT bucket,CASE WHEN json_valid(payload)") && "bucket>=? AND bucket<=?" in sql)
                    compositions++
            }
            store.insert(id, 100.5, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 999.0), epoch = "fixture")
            assertEquals(1, compositions)
            store.insert(id, 299.5, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 888.0), epoch = "fixture")
            assertEquals(1, compositions)
            store.queryObserver = null
            store.withPin(pin) { assertEquals(original, store.analytics.plot(id, "humanPowerW", 0.0, 300.0, 1)) }
            val updated = store.analytics.plot(id, "humanPowerW", 0.0, 300.0, 1)
            assertTrue(updated.any { it.num("value") == 999.0 })
            store.writableDatabase.execSQL("DELETE FROM analytics_plot_blocks WHERE ride=?", arrayOf(id))
            assertEquals(updated, store.analytics.plot(id, "humanPowerW", 0.0, 300.0, 1))
        }
    }
}
