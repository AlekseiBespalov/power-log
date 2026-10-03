package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideFixtureTest {
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
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            store.update(
                id,
                "running",
                RideTiming(0.0, 0.0, iso(1767225600000L)),
                mapOf("example" to true, "startedAt" to iso(1767225600000L)),
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
            assertEquals(2400L, store.count(id))
            val actual = RideDetail(store, distance, monitor).detail(id, "auto")["summary"] as Payload
            assertEquals(290.0, actual.num("timerSeconds"), 0.0)
            assertEquals(2.0, actual.num("lapCount"), 0.0)
        } finally {
            store.close()
        }
        context.getDatabasePath(name).copyTo(File(output, "ui-fixture.sqlite"), true)
        context.deleteDatabase(name)
    }
}
