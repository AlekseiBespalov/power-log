package app.powerlog.core.analytics

import app.powerlog.core.protocol.*
import kotlin.random.Random
import kotlin.test.*

private const val margin = EnergyPerDistancePolicy.checkpointMarginUs
private const val readPastEndUs = 10_000_000L
private val smoothings = listOf(0, 5, 30, 60)

private data class QueryKey(
    val startUs: Long,
    val controllerSpeed: Boolean,
    val smoothing: Int,
    val includeTotals: Boolean,
)

private fun randomKey(random: Random, smoothing: Int) =
    QueryKey(
        if (random.nextInt(3) == 0) random.nextLong(0, 30_000_000) else 0L,
        random.nextBoolean(),
        smoothing,
        random.nextInt(4) != 0,
    )

private fun query(key: QueryKey, endUs: Long, buckets: Int? = null): EnergyPerDistanceQuery =
    if (buckets == null)
        EnergyPerDistanceQuery(
            key.startUs,
            endUs,
            endUs - key.startUs,
            key.controllerSpeed,
            key.smoothing,
            key.includeTotals,
        )
    else
        EnergyPerDistanceQuery(
            key.startUs,
            endUs,
            buckets,
            endUs - key.startUs,
            key.controllerSpeed,
            key.smoothing,
            key.includeTotals,
        )

private fun resume(checkpoint: EnergyPerDistanceCheckpoint, key: QueryKey, endUs: Long) =
    EnergyPerDistanceQuery(
        checkpoint,
        key.startUs,
        endUs,
        endUs - key.startUs,
        key.controllerSpeed,
        key.smoothing,
        key.includeTotals,
    )

private fun feed(query: EnergyPerDistanceQuery, ride: List<RideInput>, from: Int, endUs: Long): Int {
    var index = from
    while (index < ride.size && ride[index].time <= endUs + readPastEndUs) ride[index++].feed(query)
    return index
}

private fun fresh(ride: List<RideInput>, key: QueryKey, endUs: Long, buckets: Int? = null): String =
    query(key, endUs, buckets).also { feed(it, ride, 0, endUs) }.resultJson()

private fun parsed(result: String) = JsonCodec.parse(result, arrayLimit = 16384).obj()

private fun assertSeriesFreeMatchesFullQuery(random: Random, rides: Int, seconds: IntRange, buckets: Int) {
    repeat(rides) { rideIndex ->
        val ride = EnergyRides.synthetic(random, random.nextInt(seconds.first, seconds.last + 1))
        val rideEndUs = ride.last().time
        for (smoothing in smoothings) {
            val key = randomKey(random, smoothing)
            repeat(2) {
                val endUs = random.nextLong(key.startUs, maxOf(key.startUs, rideEndUs) + 5_000_001)
                val full = parsed(fresh(ride, key, endUs, random.nextInt(1, buckets + 1)))
                val free = parsed(fresh(ride, key, endUs))
                val context = "ride $rideIndex, $key, end $endUs"
                assertEquals(Obj(emptyMap()), free.required("series"), context)
                assertEquals(Obj(full.fields - "series"), Obj(free.fields - "series"), context)
            }
        }
    }
}

private fun assertEveryCutResumes(random: Random, rides: Int, seconds: Int) {
    var cuts = 0
    var pendingCuts = 0
    repeat(rides) { rideIndex ->
        val ride = EnergyRides.synthetic(random, seconds, battery = true)
        for (smoothing in smoothings) {
            val key = randomKey(random, smoothing)
            val origin = query(key, maxOf(key.startUs, ride.last().time + margin + 1))
            val checkpoints = mutableListOf(origin.checkpoint())
            var batteryEnd = -1L
            var distanceEnd = -1L
            for (input in ride) {
                input.feed(origin)
                if (input.channel == 0) distanceEnd = input.time
                if (input.channel == 1) batteryEnd = input.time
                checkpoints += origin.checkpoint()
                if (batteryEnd > distanceEnd && batteryEnd >= input.time - 10_000_000) pendingCuts++
            }
            var cut = 0
            while (cut < checkpoints.size) {
                val throughUs = checkpoints[cut].throughUs
                val endUs =
                    maxOf(
                        key.startUs,
                        throughUs + margin + 1 + if (random.nextInt(8) == 0) 0 else random.nextLong(4_000_000),
                    )
                val expected = fresh(ride, key, endUs)
                while (cut < checkpoints.size && endUs - checkpoints[cut].throughUs > margin) {
                    val resumed = resume(checkpoints[cut], key, endUs).also { feed(it, ride, cut, endUs) }
                    assertEquals(expected, resumed.resultJson(), "ride $rideIndex, $key, cut $cut, end $endUs")
                    cut++
                    cuts++
                }
            }
        }
    }
    assertTrue(pendingCuts * 5 > cuts, "$pendingCuts of $cuts cuts held pending battery edges")
}

private fun assertLiveReadChainsResume(random: Random, rides: Int, seconds: IntRange, saving: Int, compared: Int) {
    var resumedInputs = 0L
    var freshInputs = 0L
    repeat(rides) { rideIndex ->
        val ride = EnergyRides.synthetic(random, random.nextInt(seconds.first, seconds.last + 1))
        val rideEndUs = ride.last().time
        for (smoothing in smoothings) {
            val key = randomKey(random, smoothing).copy(startUs = 0)
            var checkpoint: EnergyPerDistanceCheckpoint? = null
            var cut = 0
            var endUs = random.nextLong(margin + 1, 8_000_000)
            var read = 0
            while (endUs <= rideEndUs + 5_000_000) {
                val query = checkpoint?.let { resume(it, key, endUs) } ?: query(key, endUs)
                val cutBeforeUs = endUs - margin - if (random.nextBoolean()) 0 else random.nextLong(0, 16_000_000)
                var index = cut
                while (index < ride.size && ride[index].time < cutBeforeUs) ride[index++].feed(query)
                val next = query.checkpoint()
                val nextCut = index
                index = feed(query, ride, index, endUs)
                resumedInputs += index - cut
                freshInputs += index
                val result = query.resultJson()
                if (read++ % compared == 0)
                    assertEquals(fresh(ride, key, endUs), result, "ride $rideIndex, $key, end $endUs")
                checkpoint = next
                cut = nextCut
                endUs += random.nextLong(1, 30_000_000)
            }
        }
    }
    assertTrue(
        resumedInputs * saving < freshInputs,
        "resumed reads fed $resumedInputs inputs, fresh reads $freshInputs",
    )
}

class EnergyPerDistanceTotalsTest {
    @Test
    fun seriesFreeQueriesReturnEveryNonSeriesFieldOfTheFullQuery() =
        assertSeriesFreeMatchesFullQuery(Random(20261022), 3, 20..120, 128)

    @Test fun resumingAfterAnyInputMatchesAFreshQuery() = assertEveryCutResumes(Random(20261023), 1, 40)

    @Test
    fun chainedLiveReadsResumeFromTheirOwnCheckpointsAndFeedOnlyNewInputs() =
        assertLiveReadChainsResume(Random(20261024), 3, 120..300, 2, 1)

    @Test
    fun aCheckpointIsUnaffectedByLaterUseOfItsQueryAndServesManyEnds() {
        val ride = EnergyRides.steady(120)
        val cut = ride.indexOfFirst { it.channel == 1 && it.time == 37_000_000L } + 1
        val originEndUs = ride[cut - 1].time + 20_000_000
        for (smoothing in smoothings) {
            val key = QueryKey(0, smoothing % 2 == 0, smoothing, true)
            val origin = query(key, originEndUs)
            for (input in ride.subList(0, cut)) input.feed(origin)
            val checkpoint = origin.checkpoint()
            val ends = listOf(checkpoint.throughUs + margin + 1, checkpoint.throughUs + 30_000_000, 125_000_000L)
            val before = ends.map {
                resume(checkpoint, key, it).also { query -> feed(query, ride, cut, it) }.resultJson()
            }
            feed(origin, ride, cut, originEndUs)
            origin.resultJson()
            for ((index, endUs) in ends.withIndex()) {
                val expected = fresh(ride, key, endUs)
                val after = resume(checkpoint, key, endUs).also { feed(it, ride, cut, endUs) }.resultJson()
                assertEquals(expected, before[index], "$key, end $endUs, resumed before the origin finished")
                assertEquals(expected, after, "$key, end $endUs, resumed after the origin finished")
            }
        }
    }

    @Test
    fun checkpointsRequireTheEndMarginAndTheSameQueryParameters() {
        assertEquals(DistancePolicy.maximumControllerIntervalUs, margin)
        val key = QueryKey(0, true, 0, true)
        fun reading(query: EnergyPerDistanceQuery, timeUs: Long) =
            query.appendTelemetry(timeUs, 180.0, 900.0, 5.0, 0, "fixture", EnergyRides.stamp(timeUs))
        val throughUs = 20_000_000L
        val near = query(key, throughUs + margin).also { reading(it, throughUs) }
        assertFailsWith<IllegalArgumentException> { near.checkpoint() }
        val endPoint = near.resultJson().let(::parsed).required("endpoints").obj().required("end").obj()
        assertEquals(throughUs / 1e6, endPoint.required("batteryWhPerKm").obj().required("elapsedSeconds").number())

        val origin = query(key, throughUs + margin + 1).also { reading(it, throughUs) }
        val checkpoint = origin.checkpoint()
        assertEquals(throughUs, checkpoint.throughUs)
        assertFailsWith<IllegalArgumentException> { resume(checkpoint, key, throughUs + margin) }
        resume(checkpoint, key, throughUs + margin + 1)
        for (other in
            listOf(
                key.copy(startUs = 1_000_000),
                key.copy(controllerSpeed = false),
                key.copy(smoothing = 5),
                key.copy(includeTotals = false),
            )) {
            assertFailsWith<IllegalArgumentException> { resume(checkpoint, other, throughUs + margin + 1) }
        }
        assertFailsWith<IllegalStateException> { query(key, 60_000_000, 64).checkpoint() }
        origin.resultJson()
        assertFailsWith<IllegalStateException> { origin.checkpoint() }
    }
}

class EnergyPerDistanceTotalsVolumeTest {
    @Test
    fun seriesFreeQueriesReturnEveryNonSeriesFieldOfTheFullQuery() =
        assertSeriesFreeMatchesFullQuery(Random(20261032), 4, 20..900, 512)

    @Test fun resumingAfterAnyInputMatchesAFreshQuery() = assertEveryCutResumes(Random(20261033), 2, 90)

    @Test
    fun chainedLiveReadsResumeFromTheirOwnCheckpointsAndFeedOnlyNewInputs() =
        assertLiveReadChainsResume(Random(20261034), 4, 300..1800, 10, 4)
}
