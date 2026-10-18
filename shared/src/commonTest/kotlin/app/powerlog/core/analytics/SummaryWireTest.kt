package app.powerlog.core.analytics

import app.powerlog.core.protocol.*
import kotlin.test.*

class SummaryWireTest {
    @Test
    fun summaryWireCarriesDecimalCountsANumericLapCountAndOmitsAbsentReadings() {
        val profile = DistanceProfile("gps", "GPS", false, true, 2_000_000, 500_000, 1_500_000_000)
        val summary =
            Summary(
                "ride",
                "2026-01-01T00:00:00.000Z",
                "2026-01-01T01:00:00.000Z",
                3600.5,
                3500.0,
                DistanceInfo("auto", profile, listOf(profile)),
                3400.0,
                0.0,
                9_007_199_254_740_993,
                7,
                2,
                0,
                3,
                listOf(RoutePoint(1.0, 2.0, 0.0, 1, 1)),
                mapOf("riderPower" to "controller"),
                Completeness("partial", "complete", "missing", "complete"),
                averageRiderPowerW = 180.25,
            )
        val wire = JsonCodec.parse(SummaryWire.encode(summary, 4), wireNumbers = false).obj()
        assertEquals(Str("9007199254740993"), wire["eventCount"])
        assertEquals(Str("4"), wire["lifecycleCount"])
        assertEquals(Num(3.0), wire["lapCount"])
        assertEquals(Num(180.25), wire["averageRiderPowerW"])
        assertTrue(
            Regex("\"maximumRiderPowerW\":-0[,}]")
                .containsMatchIn(SummaryWire.encode(summary.copy(maximumRiderPowerW = -0.0), 4))
        )
        assertNull(wire["averageHeartRateBpm"])
        assertEquals(Num(1500.0), (wire["distance"]!!.obj()["selected"]!!.obj())["distanceMeters"])
        assertEquals(
            Nil,
            JsonCodec.parse(
                    SummaryWire.encode(summary.copy(distance = DistanceInfo("auto", null, emptyList())), 4),
                    wireNumbers = false,
                )
                .obj()["distance"]!!
                .obj()["selected"],
        )
    }
}
