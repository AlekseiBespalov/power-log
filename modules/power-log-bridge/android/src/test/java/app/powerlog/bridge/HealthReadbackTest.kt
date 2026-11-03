package app.powerlog.bridge

import android.content.Context
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import app.powerlog.core.health.ExecutionMode
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthReadbackTest {
    private val start = Instant.parse("2026-09-09T00:00:00Z")
    private val parent = "99999999-8888-4777-8666-555555555555"

    private fun record(values: List<Pair<Instant, Long>>, version: Long = 1) =
        HeartRateRecord(
            start.minusSeconds(1),
            ZoneOffset.UTC,
            start.plusSeconds(20),
            ZoneOffset.UTC,
            values.map { HeartRateRecord.Sample(it.first, it.second) },
            HealthMetadata.received(
                parent,
                "generated:heart",
                version,
                Device(type = Device.TYPE_PHONE),
                "app.generated.lab",
                start.plusSeconds(version),
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
            ),
        )

    private fun ride(originalLifecycle: Boolean = false, body: (RideStore, String, HealthReadback) -> Unit) {
        val store =
            RideStore(
                RuntimeEnvironment.getApplication(),
                "readback-${java.util.UUID.randomUUID()}.sqlite",
            )
        try {
            val id =
                store.create(
                    RideOptions(false, saveToHealth = true),
                    RecordingClockReading(start.toString(), 0),
                )
            val timing = RideTiming(10.0, 10.0, start.plusSeconds(10).toString())
            if (originalLifecycle) store.fixtureOriginalEnd(id, timing) else store.seal(id, timing)
            body(store, id, HealthReadback(store, FakeHealthConnectAccess()))
        } finally {
            store.close()
        }
    }

    @Test
    fun retainsAllSupportedMetricsAndOriginalMeasurementTimes() = ride { store, id, readback ->
        assertEquals(10, HealthReadback.types.size)
        val original = record(listOf(start.plusMillis(125) to 110L, start.plusMillis(375) to 120L))
        readback.import(id, original)
        readback.import(id, original)
        val rows = store.page(id).filter { it.kind == "health" }
        assertEquals(2, rows.size)
        assertEquals(
            listOf(start.plusMillis(125).toString(), start.plusMillis(375).toString()),
            rows.map { it.timestamp },
        )
        assertEquals(listOf(110.0, 120.0), rows.map { it.values["heartRateBpm"] })
    }

    @Test
    fun refreshAddsLaterRecordsWithoutProviderEditOrDeletionTracking() = ride { store, id, readback ->
        readback.import(id, record(listOf(start.plusSeconds(1) to 110L)))
        readback.import(id, record(listOf(start.plusSeconds(1) to 140L), version = 2))
        assertEquals(110.0, store.page(id).single { it.kind == "health" }.values["heartRateBpm"])
        assertEquals(
            0,
            store.select("SELECT count(*) FROM health_changes", emptyArray()) {
                it.moveToFirst()
                it.getInt(0)
            },
        )
    }

    @Test
    fun readWindowStartsThirtyDaysBeforeTheFirstObservedGrantEvenWhileHealthConnectIsUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("health-access", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val access =
            object : AndroidHealthConnectAccess(context) {
                override val available = false
            }
        assertNull(access.readableFrom)
        preferences.edit().putLong("observedGrantMillis", start.toEpochMilli()).commit()
        assertEquals(start.minusSeconds(30L * 86400), access.readableFrom)
        assertTrue(runBlocking { access.granted() }.isEmpty())
        assertEquals(start.minusSeconds(30L * 86400), access.readableFrom)
    }

    @Test
    fun readbackIsBoundedToCompletionFifteenAndSixtyMinutes() = ride { store, id, _ ->
        val access = FakeHealthConnectAccess()
        var now = timeUs(start.plusSeconds(10).toString())
        val readback = HealthReadback(store, access) { now }
        fun drain() =
            repeat(4) { runBlocking { readback.wake(id, HealthWake(store, { ExecutionMode.foreground }, { now })) } }
        drain()
        assertEquals(now + 900_000_000L, readback.nextRefreshUs(id))
        now += 900_000_000L
        drain()
        assertEquals(timeUs(start.plusSeconds(10).toString()) + 3_600_000_000L, readback.nextRefreshUs(id))
        now = timeUs(start.plusSeconds(3610).toString())
        drain()
        assertNull(readback.nextRefreshUs(id))
    }

    @Test
    fun nativeHealthExportPagesMatchTheSharedExporterFixture() =
        ride(originalLifecycle = true) { store, id, readback ->
            readback.import(
                id,
                record(listOf(start.plusSeconds(1) to 100, start.plusSeconds(3) to 120, start.plusSeconds(5) to 140)),
            )
            readback.import(
                id,
                ActiveCaloriesBurnedRecord(
                    start.minusSeconds(5),
                    ZoneOffset.UTC,
                    start.plusSeconds(15),
                    ZoneOffset.UTC,
                    androidx.health.connect.client.units.Energy.kilocalories(20.0),
                    HealthMetadata.received(
                        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                        "generated:energy",
                        1,
                        null,
                        "app.generated.lab",
                        start.plusSeconds(1),
                        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
            store.savedHealthEvidence(id)
            val source = ExportSource(store, RideDistance(store), CountingExecutor())
            val actual = org.json.JSONObject()
            try {
                for (kind in listOf("zip", "fit")) {
                    val reply = Reply()
                    source.open(
                        mapOf(
                            "rideId" to id,
                            "kind" to kind,
                            "context" to
                                mapOf("exportedAt" to start.plusSeconds(30).toString(), "platform" to "android"),
                        ),
                        reply,
                    )
                    val opened = reply.get() as Map<*, *>
                    val session = opened["session"] as String
                    val pages = org.json.JSONObject()
                    for (projection in
                        if (kind == "zip") listOf("telemetry", "gps", "healthZip", "lifecycle")
                        else listOf("telemetry", "gps", "gpsDiscovery", "healthFit", "lifecycle", "distance")) {
                        val result = Reply()
                        source.page(mapOf("session" to session, "projection" to projection), result)
                        val page = result.get() as Map<*, *>
                        if (kind == "fit" && projection == "lifecycle") {
                            val bytes = (page["columns"] as Map<*, *>)["controllerSequence"] as ByteArray
                            val values = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            assertTrue(values.double.isNaN())
                            assertTrue(values.double.isNaN())
                        }
                        val columns =
                            (page["columns"] as Map<*, *>).mapValues { (_, value) ->
                                if (value is ByteArray)
                                    mapOf("base64" to java.util.Base64.getEncoder().encodeToString(value))
                                else value
                            }
                        pages.put(projection, org.json.JSONObject(page + mapOf("columns" to columns)))
                    }
                    actual.put(
                        kind,
                        org.json.JSONObject(
                            mapOf("open" to opened + mapOf("session" to "captured-native"), "pages" to pages)
                        ),
                    )
                    source.close(session)
                }
                val root =
                    generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
                        .first { java.io.File(it, "tests/fixtures/contract/health").isDirectory }
                val fixture = java.io.File(root, "tests/fixtures/export/android-health-native.json")
                if (System.getenv("POWER_LOG_UPDATE_ANDROID_HEALTH_FIXTURES") == "1")
                    fixture.writeText(actual.toString(2) + "\n")
                assertEquals(org.json.JSONObject(fixture.readText()).toString(), actual.toString())
            } finally {
                source.closeAll()
            }
        }
}
