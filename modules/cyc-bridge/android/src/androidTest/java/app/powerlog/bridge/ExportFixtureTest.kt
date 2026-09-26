package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportFixtureTest {
    @Test
    fun finalizedSyntheticRideHasConsistentExportsAndChartOriginals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ui-fixture.sqlite"
        context.deleteDatabase(name)
        val store = RideStore(context, name)
        val distance = RideDistance(store)
        val monitor = RideMonitor(store, distance)
        val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        try {
            val id = store.create(mapOf("recordGPS" to false, "saveToHealth" to false))
            store.update(
                id,
                "running",
                0.0,
                0.0,
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
            store.lifecycle(id, 100.0, "pause")
            store.lifecycle(id, 110.0, "resume")
            store.lifecycle(id, 180.0, "lap")
            store.seal(id, 300.0, 290.0, iso(1767225900000L))
            val exporter = RideExport(context, store, distance, monitor)
            File(URI(exporter.fit(id, "auto"))).copyTo(File(output, "synthetic.fit"), true)
            File(URI(exporter.archive(id))).copyTo(File(output, "synthetic.zip"), true)
            assertEquals(0, FitWriter.crc(File(output, "synthetic.fit").readBytes()))
            assertEquals(2400L, store.count(id))
            val actual = exporter.detail(id, "auto")["summary"] as Payload
            assertEquals(290.0, actual.num("timerSeconds"), 0.0)
            assertEquals(2.0, actual.num("lapCount"), 0.0)
        } finally {
            store.close()
        }
        context.getDatabasePath(name).copyTo(File(output, "ui-fixture.sqlite"), true)
        context.deleteDatabase(name)
    }
}
