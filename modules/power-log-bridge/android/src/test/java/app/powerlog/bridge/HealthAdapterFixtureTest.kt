package app.powerlog.bridge

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device as NativeDevice
import androidx.health.connect.client.units.kilocalories
import app.powerlog.core.health.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthAdapterFixtureTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private fun withStore(body: (RideStore, String) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "adapter-fixture-${java.util.UUID.randomUUID()}.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(false, saveToHealth = true, recordGPS = true),
                    RecordingClockReading(start.toString(), 0),
                )
            body(store, id)
        } finally {
            store.close()
        }
    }

    @Test
    fun readbackActiveEnergyFixturesImportAllOriginsThenUseCoreSelection() {
        val cases = healthFixture("readback").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getJSONObject("input")
            if (
                !input.has("windowUs") ||
                    !input.has("records") ||
                    input.has("parents") ||
                    case.getString("name") == "latest-correction-before-selection"
            )
                continue
            withStore { store, id ->
                val window = input.getJSONArray("windowUs")
                val end = window.getString(1).toLong()
                store.seal(
                    id,
                    RideTiming(
                        end / 1000000.0,
                        end / 1000000.0,
                        start.plusNanos(end * 1000).toString(),
                    ),
                )
                val importer = HealthReadback(store, FakeHealthConnectAccess())
                fun records(key: String) {
                    val records = input.optJSONArray(key) ?: return
                    for (j in 0 until records.length()) {
                        val r = records.getJSONObject(j)
                        val a = start.plusNanos(r.getString("startUs").toLong() * 1000)
                        val b = start.plusNanos(r.getString("endUs").toLong() * 1000)
                        val metadata =
                            HealthMetadata.received(
                                stableEventId(r.getString("providerId")),
                                null,
                                1,
                                NativeDevice(type = NativeDevice.TYPE_WATCH),
                                r.getString("dataOrigin"),
                                start.plusSeconds(20),
                                2,
                            )
                        val record =
                            ActiveCaloriesBurnedRecord(
                                a,
                                null,
                                b,
                                null,
                                r.getDouble("valueKcal").kilocalories,
                                metadata,
                            )
                        importer.import(id, record)
                    }
                }
                records("records")
                val summary = HealthReadings(store).summary(id)
                val e = case.getJSONObject("expected")
                if (e.has("valueKcal")) assertEquals(e.getDouble("valueKcal"), summary.num("activeEnergyKcal"), 0.0)
                assertFalse(summary.containsKey("basalEnergyKcal"))
            }
        }
    }

    @Test
    fun identityGoldenIsMappedToNativeHealthMetadataWithoutRenaming() {
        val fixture = healthFixture("identity").getJSONArray("cases").getJSONObject(0)
        val id = fixture.getJSONObject("input").getString("rideId").lowercase()
        val endpoint = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val fields =
            ProviderContent.Quantity(
                QuantityContent(
                    HealthKind.riderPower,
                    timeUs(start.toString()),
                    timeUs(start.toString()) + 1000000,
                    150.0,
                    "W",
                    MeasurementSource(Source.controller, Device.controller),
                    PublicationMetadata(
                        id,
                        EndpointRole.phone,
                        endpoint,
                        EndpointRole.phone,
                        endpoint,
                        false,
                        false,
                    ),
                )
            )
        val normalized =
            NormalizedContent.Present(
                fields,
                AdapterProjection.HealthConnect(
                    fields,
                    0,
                    0,
                    RouteIntent.NotRequested,
                    512,
                    RouteEncoding(
                        null,
                        "app.generated.lab",
                        "Power Log",
                        fixture.getJSONObject("expected").getString("second"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                    ),
                ),
            )
        val record =
            PublicationRecord(
                fixture.getJSONObject("expected").getString("second"),
                Component.riderPower,
                RecordOperation.upsert,
                1,
                normalized,
                HealthCodec.contentDigest(normalized),
                1,
                emptyList(),
                RecordState.notSubmitted,
                null,
                null,
                0,
            )
        assertEquals(
            fixture.getJSONObject("expected").getString("second"),
            HealthRecords.native(record).metadata.clientRecordId,
        )
    }
}
