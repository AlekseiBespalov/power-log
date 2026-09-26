package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.system.measureTimeMillis
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongRideTest {
    @Suppress("UNCHECKED_CAST")
    @Test
    fun eightHoursRetainsOriginalsAndBoundsInteractiveReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("long-ride-test.sqlite")
        val store = RideStore(context, "long-ride-test.sqlite")
        val distance = RideDistance(store)
        val monitor = RideMonitor(store, distance)
        try {
            val id = store.create(mapOf("recordGPS" to false))
            val samples = 8 * 60 * 60 * 8
            val write = measureTimeMillis {
                for (batch in 0 until samples step 512) store.transaction {
                    for (i in batch until minOf(samples, batch + 512)) {
                        val values =
                            mapOf(
                                "humanPowerW" to if (i == 99999) 999.0 else 150.0 + (i % 100),
                                "cadenceRpm" to 80.0,
                                "motorInputPowerW" to 450.0,
                                "batteryVoltageV" to 50.0,
                                "batteryCurrentA" to 9.0,
                                "controllerSpeedMps" to 10.0,
                            )
                        val row =
                            store.insert(
                                id,
                                i / 8.0,
                                iso(1767225600000 + i * 125L),
                                "telemetry",
                                true,
                                0,
                                values,
                                "X6|fixture|5.3",
                                "fixture",
                            )
                        distance.append(
                            id,
                            row,
                            i / 8.0,
                            values,
                            true,
                            0,
                            "fixture",
                            "X6|fixture|5.3",
                            false,
                        )
                    }
                }
                store.seal(id, 28800.0, 28800.0)
            }
            assertEquals(samples.toLong(), store.count(id))
            var points = emptyList<Payload>()
            val request =
                mapOf(
                    "generation" to 1,
                    "expectedRevision" to store.revision(id).toString(),
                    "metrics" to listOf("humanPowerW"),
                    "buckets" to 128,
                    "startSeconds" to 0.0,
                    "endSeconds" to 28800.0,
                )
            val plot = measureTimeMillis {
                points =
                    (monitor.query("plot", id, request)["series"] as Map<String, List<Payload>>)
                        .getValue("humanPowerW")
            }
            assertTrue(points.size <= 520)
            assertTrue(points.any { it.num("value") == 999.0 })
            val cursor = measureTimeMillis {
                repeat(100) {
                    monitor.query("inspect", id, request + mapOf("seconds" to 10000.0 + it))
                }
            }
            val catalog = measureTimeMillis {
                repeat(20) { assertEquals(1, store.list(emptyMap()).size) }
            }
            android.util.Log.i(
                "PowerLogBenchmark",
                "samples=$samples importMs=$write plotMs=$plot cursor100Ms=$cursor catalog20Ms=$catalog points=${points.size}",
            )
            assertTrue(
                "Indexed cursor reads must not scan the whole ride: $cursor ms",
                cursor < 4000,
            )
            assertTrue("History reads metadata only: $catalog ms", catalog < 2000)
            assertTrue("Full-ride plot must stay responsive: $plot ms", plot < 2000)
            val exporter = RideExport(context, store, distance, monitor)
            val fit = java.io.File(java.net.URI(exporter.fit(id, "auto")))
            assertEquals(0, FitWriter.crc(fit.readBytes()))
        } finally {
            store.close()
            context.deleteDatabase("long-ride-test.sqlite")
        }
    }
}
