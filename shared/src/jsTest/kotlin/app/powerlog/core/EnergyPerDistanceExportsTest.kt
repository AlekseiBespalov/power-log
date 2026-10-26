package app.powerlog.core

import app.powerlog.core.protocol.*
import kotlin.test.*

class EnergyPerDistanceExportsTest {
    private fun feed(query: AnalyticsEnergyPerDistance, seconds: IntRange) {
        for (t in seconds) {
            val end = (t * 1_000_000L).toString()
            if (t > 0) {
                val start = ((t - 1) * 1_000_000L).toString()
                query.distance(start, end, 5.0, null, null)
                query.battery(start, end, 0.5, null, null, true, "2026-01-01T00:00:00Z")
            }
            query.telemetry(end, 180.0 + t, 1800.0 - t, 5.0, 0, "fixture", "2026-01-01T00:00:00Z")
        }
    }

    private fun feed(read: AnalyticsEnergyPerDistanceSeriesRead, seconds: IntRange) {
        for (t in seconds) {
            val end = (t * 1_000_000L).toString()
            if (t > 0) {
                val start = ((t - 1) * 1_000_000L).toString()
                read.distance(start, end, 5.0, null, null)
                read.battery(start, end, 0.5, null, null, true, "2026-01-01T00:00:00Z")
            }
            read.telemetry(end, 180.0 + t, 1800.0 - t, 5.0, 0, "fixture", "2026-01-01T00:00:00Z")
        }
    }

    private fun fields(query: AnalyticsEnergyPerDistance) =
        JsonCodec.parse(query.resultJson(), arrayLimit = 1024).obj().fields

    @Test
    fun seriesReadsResumeFromTheCommittedCutAndMatchTheAlignedQuery() {
        val series = AnalyticsEnergyPerDistanceSeries(true, 5, false)
        var through = -1
        for ((end, buckets) in listOf(20 to 64, 45 to 8, 70 to 8)) {
            val endUs = (end * 1_000_000L).toString()
            val slot = inspectionPlotSlotUs("0", endUs, buckets)
            assertEquals(end != 20, slot != null)
            val fresh =
                AnalyticsEnergyPerDistance("0", endUs, buckets, endUs, true, 5, false, null, slot).also {
                    feed(it, 0..end + 10)
                }
            val read = series.begin(endUs, endUs, buckets, slot)
            feed(read, through + 1..end - 3)
            read.cut()
            feed(read, end - 2..end + 10)
            assertEquals(fresh.resultJson(), read.resultJson())
            assertTrue(series.valid)
            assertEquals(((end - 3) * 1_000_000L).toString(), series.throughUs)
            through = end - 3
        }
        assertTrue(series.retainedPoints > 0)
        assertFailsWith<IllegalArgumentException> {
            AnalyticsEnergyPerDistance("0", "70000000", null, "70000000", true, 5, false, null, "16000000")
        }
    }

    @Test
    fun nullBucketsReadTotalsWithoutASeriesAndResumeFromACheckpoint() {
        val series = AnalyticsEnergyPerDistance("0", "45000000", 64, "45000000", true, 5).also { feed(it, 0..55) }
        val totals = AnalyticsEnergyPerDistance("0", "45000000", null, "45000000", true, 5).also { feed(it, 0..55) }
        assertEquals(Obj(emptyMap()), fields(totals)["series"])
        assertEquals(fields(series) - "series", fields(totals) - "series")

        val origin = AnalyticsEnergyPerDistance("0", "60000000", null, "60000000", true, 5).also { feed(it, 0..30) }
        val checkpoint = origin.checkpoint()
        assertEquals("30000000", checkpoint.throughUs)
        assertEquals("2500000", analyticsEnergyCheckpointMarginUs())
        val resumed =
            AnalyticsEnergyPerDistance("0", "45000000", null, "45000000", true, 5, true, checkpoint).also {
                feed(it, 31..55)
            }
        assertEquals(totals.resultJson(), resumed.resultJson())
        assertFailsWith<IllegalArgumentException> {
            AnalyticsEnergyPerDistance("0", "45000000", 64, "45000000", true, 5, true, checkpoint)
        }
    }
}
