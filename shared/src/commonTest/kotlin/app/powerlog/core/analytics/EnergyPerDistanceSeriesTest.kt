package app.powerlog.core.analytics

import app.powerlog.core.inspection.InspectionPlotSlots
import kotlin.random.Random
import kotlin.test.*

private const val margin = EnergyPerDistancePolicy.checkpointMarginUs
private const val readPastEndUs = 10_000_000L
private val smoothings = listOf(0, 5, 30, 60)

private data class SeriesKey(val controllerSpeed: Boolean, val smoothing: Int, val includeTotals: Boolean)

private fun slot(endUs: Long, buckets: Int) = InspectionPlotSlots.alignedSlotUs(AnalysisRange(0, endUs), buckets)

private fun freshSeries(ride: List<RideInput>, key: SeriesKey, endUs: Long, buckets: Int): String {
    val query =
        EnergyPerDistanceQuery(
            0,
            endUs,
            buckets,
            slot(endUs, buckets),
            endUs,
            key.controllerSpeed,
            key.smoothing,
            key.includeTotals,
        )
    for (input in ride) {
        if (input.time > endUs + readPastEndUs) break
        input.feed(query)
    }
    return query.resultJson()
}

private class SeriesReader(
    val ride: List<RideInput>,
    key: SeriesKey,
    pointBudget: Int = InspectionPlotSlots.maximumPoints,
) {
    val series = EnergyPerDistanceSeries(key.controllerSpeed, key.smoothing, key.includeTotals, pointBudget)
    var cursor = 0
    var through = 0
    var fed = 0L

    fun read(endUs: Long, cutBeforeUs: Long, buckets: Int): String {
        val read = series.begin(endUs, endUs, buckets, slot(endUs, buckets))
        var index = cursor
        while (index < ride.size && ride[index].time < cutBeforeUs) ride[index++].feed(read)
        read.cut()
        val cut = index
        while (index < ride.size && ride[index].time <= endUs + readPastEndUs) ride[index++].feed(read)
        fed += index - cursor
        through = index
        val result = read.resultJson()
        cursor = cut
        return result
    }
}

private fun assertIncrementalReadsMatchFreshQueries(
    random: Random,
    rides: Int,
    seconds: IntRange,
    spacingUs: Long,
    saving: Int,
    compared: Int,
) {
    var freshInputs = 0L
    var incrementalInputs = 0L
    var exact = 0
    var aligned = 0
    repeat(rides) { rideIndex ->
        val ride = EnergyRides.synthetic(random, random.nextInt(seconds.first, seconds.last + 1))
        val rideEndUs = ride.last().time
        for (smoothing in smoothings) {
            val key = SeriesKey(random.nextBoolean(), smoothing, random.nextBoolean())
            val reader = SeriesReader(ride, key)
            var endUs = random.nextLong(margin + 1, 8_000_000)
            var read = 0
            while (endUs <= rideEndUs + 5_000_000) {
                val buckets = if (random.nextInt(16) == 0) 512 else random.nextInt(1, 65)
                if (slot(endUs, buckets) == null) exact++ else aligned++
                val cutBeforeUs = endUs - margin - if (random.nextBoolean()) 0 else random.nextLong(0, 16_000_000)
                val result = reader.read(endUs, cutBeforeUs, buckets)
                freshInputs += reader.through
                if (read++ % compared == 0)
                    assertEquals(
                        freshSeries(ride, key, endUs, buckets),
                        result,
                        "ride $rideIndex, $key, end $endUs, buckets $buckets",
                    )
                assertTrue(reader.series.valid)
                endUs += random.nextLong(1, spacingUs)
            }
            incrementalInputs += reader.fed
        }
    }
    assertTrue(exact > 0 && aligned > 0, "$exact exact-width and $aligned aligned reads")
    assertTrue(
        incrementalInputs * saving < freshInputs,
        "incremental reads fed $incrementalInputs inputs, fresh reads $freshInputs",
    )
}

class EnergyPerDistanceSeriesTest {
    @Test
    fun alignedSeriesFollowAbsoluteSlotsAndMatchTheExactGridOnlyWhereTheGridsCoincide() {
        val ride = EnergyRides.synthetic(Random(20261040), 250)
        fun json(query: EnergyPerDistanceQuery): String {
            for (input in ride) if (input.time <= query.endUs + readPastEndUs) input.feed(query)
            return query.resultJson()
        }
        for (smoothing in listOf(0, 60)) {
            for ((buckets, slotUs) in listOf(1 to 64_000_000L, 37 to 4_000_000L, 120 to 2_000_000L)) {
                val onGrid = buckets * slotUs
                assertTrue(ride.none { it is RideInput.Telemetry && it.time == onGrid })
                assertEquals(
                    json(EnergyPerDistanceQuery(0, onGrid, buckets, onGrid, true, smoothing, false)),
                    json(EnergyPerDistanceQuery(0, onGrid, buckets, slotUs, onGrid, true, smoothing, false)),
                    "smoothing $smoothing, buckets $buckets",
                )
                val offGrid = onGrid - slotUs / 2
                if (buckets > 1)
                    assertNotEquals(
                        json(EnergyPerDistanceQuery(0, offGrid, buckets, offGrid, true, smoothing, false)),
                        json(EnergyPerDistanceQuery(0, offGrid, buckets, slotUs, offGrid, true, smoothing, false)),
                        "smoothing $smoothing, buckets $buckets",
                    )
            }
        }
    }

    @Test
    fun incrementalReadsMatchFreshAlignedAndExactWidthQueries() =
        assertIncrementalReadsMatchFreshQueries(Random(20261041), 1, 560..700, 120_000_000, 3, 3)

    @Test
    fun exactWidthReadsReplayTheCommittedReadings() {
        val ride = EnergyRides.synthetic(Random(20261042), 130)
        for (smoothing in listOf(0, 60)) {
            val key = SeriesKey(smoothing % 2 == 0, smoothing, smoothing == 5)
            val reader = SeriesReader(ride, key)
            var endUs = 4_000_000L
            while (endUs < 128_000_000L) {
                assertNull(slot(endUs, 128))
                assertEquals(freshSeries(ride, key, endUs, 128), reader.read(endUs, endUs - margin, 128), "end $endUs")
                endUs += 9_000_000
            }
            assertTrue(reader.series.retainedPoints >= reader.series.throughUs / 1_000_000)
        }
    }

    @Test
    fun retainedPointsStayBoundedOnceEveryReadIsAligned() {
        val ride = EnergyRides.steady(1800)
        val key = SeriesKey(true, 30, true)
        val reader = SeriesReader(ride, key)
        var endUs = 30_000_000L
        var early = 0
        while (endUs < 1_800_000_000L) {
            reader.read(endUs, endUs - margin, 32)
            if (reader.series.throughUs < 512_000_000) early = reader.series.retainedPoints
            endUs += 90_000_000
        }
        assertEquals(freshSeries(ride, key, endUs, 400), reader.read(endUs, endUs - margin, 400))
        val retained = reader.series.retainedPoints
        assertTrue(retained <= 2 * (4 * (512 + 2) + 64), "$retained points retained after half an hour")
        assertTrue(early > retained, "$early points retained before 512 s, $retained after half an hour")
        assertTrue(reader.fed < 2L * ride.size, "${reader.fed} inputs fed for a ride of ${ride.size}")
    }

    @Test
    fun aColdReadSummarizesItsReadingsAsTheyArrive() {
        val ride = EnergyRides.steady(1800)
        val key = SeriesKey(true, 30, true)
        val endUs = 1_790_000_000L
        val cutUs = endUs - margin
        val series = EnergyPerDistanceSeries(key.controllerSpeed, key.smoothing, key.includeTotals)
        val read = series.begin(endUs, endUs, 400, slot(endUs, 400))
        var index = 0
        while (ride[index].time < cutUs) ride[index++].feed(read)
        read.cut()
        while (index < ride.size && ride[index].time <= endUs + readPastEndUs) ride[index++].feed(read)
        val readings = 2 * ride.count { it is RideInput.Telemetry && it.time <= endUs + readPastEndUs }
        val logged = 2 * ride.count { it is RideInput.Telemetry && it.time < 512_000_000 }
        val retained = series.retainedPoints
        assertTrue(
            retained in logged + 1..2 * (4 * (512 + 2) + 64) + logged,
            "$retained points retained for $readings readings",
        )
        assertEquals(freshSeries(ride, key, endUs, 400), read.resultJson())
        assertTrue(series.valid)
        assertNull(series.current)
        assertTrue(series.retainedPoints <= 2 * (4 * (512 + 2) + 64))
    }

    @Test
    fun anAbandonedOrFailedReadThatFedTheSummariesInvalidatesTheSeries() {
        val ride = EnergyRides.synthetic(Random(20261043), 200, battery = true)
        for (smoothing in smoothings) {
            val key = SeriesKey(smoothing != 30, smoothing, true)
            val abandoning = SeriesReader(ride, key)
            abandoning.read(60_000_000, 60_000_000 - margin, 24)
            val abandoned = abandoning.series.begin(120_000_000, 120_000_000, 24, slot(120_000_000, 24))
            var index = abandoning.cursor
            while (ride[index].time < 120_000_000 - 4 * margin) ride[index++].feed(abandoned)
            assertFalse(abandoning.series.valid)
            assertFailsWith<IllegalStateException> {
                abandoning.series.begin(150_000_000, 150_000_000, 48, slot(150_000_000, 48))
            }
            assertNull(abandoning.series.current)
            assertFailsWith<IllegalStateException> { ride[index].feed(abandoned) }
            assertFailsWith<IllegalStateException> { abandoned.cut() }
            assertFalse(abandoning.series.valid)

            val failing = SeriesReader(ride, key)
            failing.read(60_000_000, 60_000_000 - margin, 24)
            val failed = failing.series.begin(120_000_000, 120_000_000, 24, slot(120_000_000, 24))
            index = failing.cursor
            while (ride[index].time < 100_000_000) ride[index++].feed(failed)
            assertFailsWith<IllegalArgumentException> { ride[failing.cursor].feed(failed) }
            assertNull(failing.series.current)
            assertFalse(failing.series.valid)
            assertFailsWith<IllegalStateException> {
                failing.series.begin(150_000_000, 150_000_000, 48, slot(150_000_000, 48))
            }
        }
    }

    @Test
    fun aReadThatFedNothingToTheSummariesLeavesTheSeriesValid() {
        val ride = EnergyRides.synthetic(Random(20261045), 200, battery = true)
        for (smoothing in smoothings) {
            val key = SeriesKey(smoothing != 30, smoothing, true)
            val reader = SeriesReader(ride, key)
            val early = reader.series.begin(2_000_000, 2_000_000, 16, slot(2_000_000, 16))
            for (input in ride) if (input.time <= 2_000_000 + readPastEndUs) input.feed(early)
            assertEquals(freshSeries(ride, key, 2_000_000, 16), early.resultJson())
            assertNull(reader.series.current)
            assertTrue(reader.series.valid)
            assertEquals(-1, reader.series.throughUs)
            assertEquals(0, reader.series.retainedPoints)

            reader.read(60_000_000, 60_000_000 - margin, 24)
            val through = reader.series.throughUs
            val retained = reader.series.retainedPoints
            val abandoned = reader.series.begin(120_000_000, 120_000_000, 24, slot(120_000_000, 24))
            abandoned.cut()
            var index = reader.cursor
            while (ride[index].time <= 130_000_000) ride[index++].feed(abandoned)
            assertTrue(reader.series.valid)
            val endUs = 150_000_000L
            assertEquals(freshSeries(ride, key, endUs, 48), reader.read(endUs, endUs - 4 * margin, 48))
            assertFailsWith<IllegalStateException> { ride[index].feed(abandoned) }
            assertFailsWith<IllegalStateException> { abandoned.resultJson() }
            assertTrue(reader.series.valid)
            assertTrue(reader.series.throughUs > through && reader.series.retainedPoints > retained)
            assertEquals(freshSeries(ride, key, 190_000_000, 16), reader.read(190_000_000, 180_000_000, 16))
        }
    }

    @Test
    fun aFailedAppendOrAnOverflowedSummaryInvalidatesTheSeries() {
        val ride = EnergyRides.synthetic(Random(20261044), 200)
        val key = SeriesKey(true, 0, false)
        val failed = SeriesReader(ride, key)
        failed.read(40_000_000, 40_000_000 - margin, 100)
        val read = failed.series.begin(60_000_000, 60_000_000, 100, slot(60_000_000, 100))
        val late = ride.first { it is RideInput.Telemetry && it.time > 50_000_000 }
        late.feed(read)
        assertFailsWith<IllegalArgumentException> { ride.first { it.time > 0 && it.time < late.time }.feed(read) }
        assertFalse(failed.series.valid)
        assertNull(failed.series.current)
        assertFailsWith<IllegalStateException> {
            failed.series.begin(70_000_000, 70_000_000, 100, slot(70_000_000, 100))
        }

        val small = SeriesReader(ride, key, pointBudget = 32)
        var endUs = 10_000_000L
        while (small.series.valid) {
            assertEquals(freshSeries(ride, key, endUs, 50), small.read(endUs, endUs - margin, 50), "end $endUs")
            endUs += 10_000_000
            assertTrue(endUs < 200_000_000)
        }
        assertNull(small.series.current)
        assertFailsWith<IllegalStateException> { small.series.begin(endUs, endUs, 50, slot(endUs, 50)) }

        val cold = SeriesReader(ride, key, pointBudget = 32)
        assertEquals(freshSeries(ride, key, 190_000_000, 50), cold.read(190_000_000, 190_000_000 - margin, 50))
        assertFalse(cold.series.valid)
        assertNull(cold.series.current)
    }
}

class EnergyPerDistanceSeriesVolumeTest {
    @Test
    fun incrementalReadsMatchFreshAlignedAndExactWidthQueries() =
        assertIncrementalReadsMatchFreshQueries(Random(20261051), 1, 1100..1500, 180_000_000, 5, 5)
}
