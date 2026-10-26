package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.random.Random
import kotlin.test.*

class InspectionPlotSummaryTest {
    private enum class Form {
        item,
        compact,
        distance,
        compactDistance,
    }

    private class Input(
        val item: AnalysisItem,
        val reference: Long,
        val selected: Boolean = true,
        val boundary: Boolean = false,
        val plotSegment: Int = 0,
    )

    private class CallIdentity {
        var open = true

        val function: (Long) -> String = {
            check(open) { "An ended call's identity was used" }
            name(it)
        }
    }

    private companion object {
        fun name(reference: Long): String = (reference * 2654435761L and 0xffffffffL).toString(16).padStart(8, '0')

        fun accuracyMetric(metric: String): String? =
            when (metric) {
                "speedMps" -> "speedAccuracyMps"
                "courseDegrees" -> "courseAccuracyDegrees"
                else -> null
            }
    }

    private fun item(
        metric: String,
        reference: Long,
        timeUs: Long,
        value: Scalar?,
        continuity: Continuity = Continuity(),
        accuracy: Scalar? = null,
        reading: Boolean = true,
        producer: String = "controller",
        id: String = name(reference),
    ): AnalysisItem {
        val values = mutableMapOf<String, Scalar>()
        value?.let { values[metric] = it }
        accuracy?.let { values[requireNotNull(accuracyMetric(metric))] = it }
        return AnalysisItem(
            timeUs,
            FixedPoint.divide(timeUs, 1000000),
            id,
            id,
            producer,
            reference,
            if (reading) InspectionMetrics.channel(metric).name else "lifecycle",
            "controller",
            "2026-01-01T00:00:00Z",
            values,
            continuity = continuity,
            declaredMetrics = listOf(metric),
        )
    }

    private fun compact(metric: String, input: Input) =
        InspectionCompactPoint(
            input.reference,
            input.item.collectionUs,
            input.item.values[metric],
            input.item.continuity,
            input.item.producer,
            input.item.sequence,
            accuracyMetric(metric)?.let { input.item.values[it] },
            input.boundary,
        )

    private fun distance(metric: String, input: Input) =
        DistanceInspectionPoint(
            inspectionPoint(input.item, metric),
            input.plotSegment,
            input.item.collectionUs,
            input.item.collectionUs,
            input.reference,
            input.plotSegment,
        )

    private fun feed(reducer: InspectionPlotReducer, form: Form, input: Input) {
        val metric = reducer.metric
        when (form) {
            Form.item -> reducer.append(input.item, input.selected, input.boundary)
            Form.compact -> reducer.appendCompact(compact(metric, input), input.selected)
            Form.distance -> reducer.appendDistance(distance(metric, input))
            Form.compactDistance ->
                reducer.appendCompactDistance(compact(metric, input), input.plotSegment, input.reference)
        }
    }

    private fun feed(summary: InspectionPlotSummary, form: Form, input: Input) {
        val metric = summary.metric
        when (form) {
            Form.item -> summary.append(input.item, input.selected, input.boundary)
            Form.compact -> {
                val call = CallIdentity()
                try {
                    summary.appendCompact(compact(metric, input), call.function, input.selected)
                } finally {
                    call.open = false
                }
            }
            Form.distance -> summary.appendDistance(distance(metric, input))
            Form.compactDistance ->
                summary.appendCompactDistance(compact(metric, input), input.plotSegment, input.reference)
        }
    }

    private fun step(random: Random, ties: Boolean): Long =
        if (ties && random.nextInt(3) == 0) 0L
        else
            when (random.nextInt(20)) {
                0,
                1 -> 0L
                2,
                3,
                4 -> random.nextLong(1, 2000000)
                5,
                6 -> random.nextLong(2000000, 9000000)
                7 -> random.nextLong(9000000, 60000000)
                8 -> random.nextLong(60000000, 1200000000)
                else -> 125000L
            }

    private fun reading(random: Random, metric: String): Pair<Scalar?, Scalar?> {
        val valid =
            when (metric) {
                "heartRateBpm" -> listOf(60.0, 61.0, 61.0, 150.0)
                "courseDegrees" -> listOf(0.0, 10.0, 170.0, 190.0, 350.0, 359.0)
                "speedMps" -> listOf(0.0, 5.0, 5.0, 12.5)
                else -> listOf(0.0, 100.0, 100.0, 250.0, 400.0)
            }
        val accuracy = accuracyMetric(metric)?.let { Scalar.Real64(if (random.nextInt(12) == 0) -1.0 else 3.0) }
        return when (random.nextInt(12)) {
            0 -> null to accuracy
            1 ->
                Scalar.Real64(
                    when (metric) {
                        "heartRateBpm" -> 0.0
                        "courseDegrees" -> 360.0
                        "speedMps" -> -1.0
                        else -> Double.NaN
                    }
                ) to accuracy
            2 -> Scalar.Integer64(valid[1].toLong()) to accuracy
            else -> Scalar.Real64(valid[random.nextInt(valid.size)]) to accuracy
        }
    }

    private fun ride(random: Random, metric: String, form: Form, count: Int): List<Input> {
        var time =
            when (random.nextInt(4)) {
                0 -> -random.nextLong(1, 40000000)
                1 -> random.nextLong(1, 4000000000)
                else -> 0L
            }
        val ties = random.nextBoolean()
        var clock = 0
        var connection = 0
        var interruption = 0
        var plotSegment = 0
        var meters = 0.0
        return List(count) { index ->
            if (index > 0) time += step(random, ties)
            if (random.nextInt(40) == 0) clock++
            if (random.nextInt(25) == 0) connection++
            if (random.nextInt(40) == 0) interruption++
            val continuity = Continuity("clock-$clock", "connection-$connection", interruption)
            val reference = index + 1L
            if (InspectionMetrics.isProjected(metric)) {
                if (random.nextInt(9) == 0) plotSegment++
                meters += random.nextInt(4) * 2.5
                Input(
                    item(metric, reference, time, Scalar.Real64(meters), continuity),
                    reference,
                    plotSegment = plotSegment,
                )
            } else {
                val (value, accuracy) = reading(random, metric)
                Input(
                    item(
                        metric,
                        reference,
                        time,
                        value,
                        continuity,
                        accuracy,
                        reading = form != Form.item || random.nextInt(30) != 0,
                        producer = if (random.nextInt(10) == 0) "watch" else "controller",
                    ),
                    reference,
                    selected = random.nextInt(12) != 0,
                    boundary = random.nextInt(25) == 0,
                )
            }
        }
    }

    private fun destination(metric: String, range: AnalysisRange, buckets: Int, slot: Long?, pointLimit: Int) =
        InspectionPlotReducer(metric, range, buckets, pointLimit, identity = ::name, alignedSlotUs = slot)

    private fun assertSameOutcome(
        expected: Result<List<InspectionPlotPoint>>,
        actual: Result<List<InspectionPlotPoint>>,
        label: String,
    ) {
        if (expected.isFailure) {
            assertIs<InspectionCapacityException>(expected.exceptionOrNull(), label)
            assertIs<InspectionCapacityException>(actual.exceptionOrNull(), label)
            return
        }
        val points = expected.getOrThrow()
        val merged = actual.getOrThrow()
        assertEquals(points, merged, label)
        assertEquals(points.map { it.point.identity() }, merged.map { it.point.identity() }, label)
    }

    private fun assertSameResult(direct: InspectionPlotReducer, merged: InspectionPlotReducer, label: String) =
        assertSameOutcome(runCatching { direct.result() }, runCatching { merged.result() }, label)

    private class Coverage {
        var compared = 0
        var capacity = 0
        var unaligned = 0
        var exactWidth = 0
        var coarsened = 0
        var maximumLevel = 0L

        fun assertExercised() {
            assertTrue(compared > 1000, "$compared merged results compared")
            assertTrue(capacity > 0 && unaligned > 0 && exactWidth > 0, "$capacity $unaligned $exactWidth")
            assertTrue(coarsened > 100 && maximumLevel >= 4096000000L, "$coarsened coarsenings to $maximumLevel")
        }
    }

    private val coverage = Coverage()

    private fun assertMerges(
        summary: InspectionPlotSummary,
        form: Form,
        fed: List<Input>,
        later: List<Input>,
        random: Random,
        label: String,
    ) {
        val metric = summary.metric
        val first = summary.firstRawUs ?: return
        val last = requireNotNull(summary.lastRawUs)
        val start =
            when (random.nextInt(3)) {
                0 -> first
                1 -> minOf(0, first)
                else -> first - random.nextLong(0, 30000000)
            }
        val end = last + random.nextLong(0, 3000000)
        val range = AnalysisRange(start, end)
        val choices = listOf(1, 2, 3, 7, 64, 161, 333, 512)
        for (buckets in List(2) { choices[random.nextInt(choices.size)] }) {
            val slot = InspectionPlotSlots.alignedSlotUs(range, buckets)
            val pointLimit = if (random.nextInt(4) == 0) random.nextInt(16, 256) else 16384
            if (slot == null) {
                assertFalse(summary.canMergeInto(destination(metric, range, buckets, null, pointLimit)), label)
                coverage.exactWidth++
                continue
            }
            val direct = destination(metric, range, buckets, slot, pointLimit)
            val merged = destination(metric, range, buckets, slot, pointLimit)
            val aligned = slot % summary.slotUs == 0L
            assertEquals(aligned, summary.canMergeInto(merged), "$label buckets $buckets")
            if (buckets <= summary.maximumSlots) assertTrue(aligned, "$label buckets $buckets")
            if (!aligned) {
                assertFailsWith<IllegalArgumentException> { summary.mergeInto(merged) }
                coverage.unaligned++
                continue
            }
            val expected = runCatching {
                (fed + later).forEach { feed(direct, form, it) }
                direct.result()
            }
            val actual = runCatching {
                summary.mergeInto(merged)
                later.forEach { feed(merged, form, it) }
                merged.result()
            }
            assertSameOutcome(expected, actual, "$label buckets $buckets")
            if (expected.isFailure) coverage.capacity++ else coverage.compared++
        }
    }

    private fun differential(
        metric: String,
        form: Form,
        maximumSlots: Int,
        chunkInputs: Int,
        seed: Int,
        generate: (Random) -> List<Input> = { ride(it, metric, form, 200) },
    ) {
        val random = Random(seed)
        val inputs = generate(random)
        val summary = InspectionPlotSummary(metric, maximumSlots, InspectionPlotSlots.maximumPoints, chunkInputs)
        var level = summary.slotUs
        for (index in inputs.indices) {
            feed(summary, form, inputs[index])
            val coarsened = summary.slotUs != level
            if (coarsened) coverage.coarsened++
            level = summary.slotUs
            coverage.maximumLevel = maxOf(coverage.maximumLevel, level)
            if (index < 24 || index % 9 == 0 || coarsened || index == inputs.lastIndex)
                assertMerges(
                    summary,
                    form,
                    inputs.subList(0, index + 1),
                    inputs.subList(index + 1, minOf(inputs.size, index + 1 + random.nextInt(4))),
                    random,
                    "$metric $form $maximumSlots/$chunkInputs seed $seed input $index",
                )
        }
    }

    private val configurations = listOf(512 to 64, 512 to 1, 16 to 3, 4 to 2, 2 to 5, 1 to 1)

    private fun differentials(metrics: List<String>, forms: List<Form>, firstSeed: Int) {
        var seed = firstSeed
        for (metric in metrics) {
            for (form in forms) {
                for ((slots, chunk) in configurations) differential(metric, form, slots, chunk, seed++)
            }
        }
    }

    @Test
    fun summaryMergesExactlyLikeDirectAlignedReductionOfRawReadings() {
        differentials(
            listOf("humanPowerW", "heartRateBpm", "courseDegrees", "speedMps"),
            listOf(Form.item, Form.compact),
            0,
        )
        coverage.assertExercised()
    }

    @Test
    fun summaryMergesExactlyLikeDirectAlignedReductionOfProjectedMetrics() {
        differentials(listOf("distanceMeters", "calculatedEnergyWh"), listOf(Form.distance, Form.compactDistance), 1000)
        coverage.assertExercised()
    }

    private fun coincidentCourses(random: Random, descending: Boolean): List<Input> {
        val values = listOf(0.0, 10.0, 170.0, 190.0, 350.0, 359.0)
        val ordered = if (descending) (1L..160L).sortedByDescending(::name) else (1L..160L).sortedBy(::name)
        var time = 0L
        return ordered.mapIndexed { index, reference ->
            if (index > 0 && random.nextInt(2) == 0) time += listOf(125000L, 250000L, 1000000L)[random.nextInt(3)]
            Input(item("courseDegrees", reference, time, Scalar.Real64(values[random.nextInt(values.size)])), reference)
        }
    }

    @Test
    fun coincidentCourseOriginalsMergeExactlyInEitherIdentityOrder() {
        var seed = 2000
        for (descending in listOf(true, false)) {
            for (form in listOf(Form.item, Form.compact)) {
                for ((slots, chunk) in configurations) {
                    differential("courseDegrees", form, slots, chunk, seed++) { coincidentCourses(it, descending) }
                }
            }
        }
    }

    @Test
    fun courseSegmentsContinueFromCoincidentOriginalsInArrivalOrder() {
        val metric = "courseDegrees"
        val rides =
            listOf(
                listOf(Triple("z", 0L, 0.0), Triple("a", 0L, 170.0), Triple("m", 1000000L, 350.0)) to listOf(0, 0, 0),
                listOf(Triple("m", 0L, 0.0), Triple("z", 1000000L, 170.0), Triple("a", 1000000L, 190.0)) to
                    listOf(0, 0, 0),
            )
        val range = AnalysisRange(0, 3000000)
        for ((readings, segments) in rides) {
            val inputs =
                (readings + Triple("n", 2000000L, 185.0)).mapIndexed { index, (id, time, value) ->
                    Input(item(metric, index + 1L, time, Scalar.Real64(value), id = id), index + 1L)
                }
            for (buckets in listOf(1, 2, 3)) {
                val slot = assertNotNull(InspectionPlotSlots.alignedSlotUs(range, buckets))
                val summary = InspectionPlotSummary(metric)
                inputs.forEach { feed(summary, Form.item, it) }
                val direct = destination(metric, range, buckets, slot, 16384)
                inputs.forEach { feed(direct, Form.item, it) }
                val merged = destination(metric, range, buckets, slot, 16384)
                summary.mergeInto(merged)
                assertSameResult(direct, merged, "${readings.first().first} $buckets buckets")
                assertEquals(segments, merged.result().take(3).map { it.segment })
            }
        }
    }

    @Test
    fun alignedSlotsMatchSharedFixtures() {
        for (row in inspectionFixture("aligned-slots")["slots"].list()) {
            val id = row["id"].text()
            val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
            val buckets = row["buckets"].long().toInt()
            if (row["invalid"] == J.Bool(true)) {
                assertFailsWith<IllegalArgumentException>(id) { InspectionPlotSlots.alignedSlotUs(range, buckets) }
                continue
            }
            val slot = InspectionPlotSlots.alignedSlotUs(range, buckets)
            assertEquals(row["slotUs"].optionalText()?.toLong(), slot, id)
            if (slot == null) continue
            val span = range.endUs - range.startUs
            InspectionPlotReducer("humanPowerW", range, buckets, alignedSlotUs = slot)
            assertTrue(slot == 1000000L || slot / 2 * buckets < span, id)
            if (range.startUs >= 0)
                assertTrue(range.endUs / slot - range.startUs / slot + 1 in buckets / 2 + 1..buckets + 1, id)
        }
    }

    @Test
    fun bucketBudgetsMatchSharedFixtures() {
        for (row in inspectionFixture("aligned-slots")["budgets"].list()) {
            val id = row["id"].text()
            val requested = row["requested"].long().toInt()
            val metrics = row["metrics"].long().toInt()
            if (row["invalid"] == J.Bool(true)) {
                assertFailsWith<IllegalArgumentException>(id) { InspectionPlotSlots.buckets(requested, metrics) }
                continue
            }
            assertEquals(row["buckets"].long().toInt(), InspectionPlotSlots.buckets(requested, metrics), id)
            assertEquals(row["pointLimit"].long().toInt(), InspectionPlotSlots.pointLimit(metrics), id)
        }
    }

    @Test
    fun sixteenHourRideKeepsRetainedPointsBounded() {
        val metric = "humanPowerW"
        val dense = 120 * 8
        val endUs = 16 * 3600 * 1000000L
        val times =
            (0 until dense).map { it * 125000L } +
                (dense * 125000L until endUs - 120000000L step 5000000L) +
                (0 until dense).map { endUs - 120000000L + it * 125000L }
        val random = Random(16)
        val inputs = times.mapIndexed { index, time ->
            Input(item(metric, index + 1L, time, Scalar.Real64(random.nextInt(400).toDouble())), index + 1L)
        }
        val summary = InspectionPlotSummary(metric)
        val bound = 4 * (summary.maximumSlots + 2) + summary.chunkInputs
        var peak = 0
        for (input in inputs) {
            feed(summary, Form.compact, input)
            peak = maxOf(peak, summary.retainedPoints)
            assertTrue(
                summary.retainedPoints <= bound,
                "${summary.retainedPoints} points at ${input.item.collectionUs}",
            )
        }
        assertEquals(128000000L, summary.slotUs)
        assertEquals(inputs.size.toLong(), summary.rawInputs)
        assertTrue(peak > 2 * summary.maximumSlots)
        for (buckets in listOf(161, 512)) {
            val range = AnalysisRange(0, inputs.last().item.collectionUs)
            val slot = InspectionPlotSlots.alignedSlotUs(range, buckets)
            val direct = destination(metric, range, buckets, slot, 16384)
            inputs.forEach { feed(direct, Form.compact, it) }
            val merged = destination(metric, range, buckets, slot, 16384)
            summary.mergeInto(merged)
            assertSameResult(direct, merged, "sixteen hours, $buckets buckets")
        }
    }

    @Test
    fun overflowRefusesMergesAndAppends() {
        val metric = "humanPowerW"
        val summary = InspectionPlotSummary(metric, 512, 40, 64)
        assertFailsWith<InspectionCapacityException> {
            for (index in 0 until 200) summary.append(
                item(metric, index + 1L, index * 125000L, if (index % 2 == 0) Scalar.Real64(100.0) else null)
            )
        }
        assertTrue(summary.overflowed)
        assertEquals(0, summary.retainedPoints)
        val range = AnalysisRange(0, 30000000)
        val reducer = destination(metric, range, 16, InspectionPlotSlots.alignedSlotUs(range, 16), 16384)
        assertFalse(summary.canMergeInto(reducer))
        assertFailsWith<IllegalArgumentException> { summary.mergeInto(reducer) }
        assertFailsWith<InspectionCapacityException> {
            summary.append(item(metric, 1000, 40000000, Scalar.Real64(1.0)))
        }
        val coincident = InspectionPlotSummary(metric, 512, 10, 64)
        assertFailsWith<InspectionCapacityException> {
            repeat(20) { coincident.append(item(metric, it + 1L, 0, Scalar.Real64(it.toDouble()))) }
        }
        assertTrue(coincident.overflowed)
    }

    @Test
    fun summaryRefusesFinerSlotsAndUncoveredRanges() {
        val metric = "humanPowerW"
        val inputs = (0..100).map { Input(item(metric, it + 1L, it * 1000000L, Scalar.Real64(it % 7 * 10.0)), it + 1L) }
        val summary = InspectionPlotSummary(metric, 16, 16384, 4)
        inputs.forEach { feed(summary, Form.item, it) }
        assertEquals(8000000L, summary.slotUs)
        val range = AnalysisRange(0, 100000000)
        fun reducer(slot: Long?, buckets: Int = 100, covered: AnalysisRange = range, name: String = metric) =
            InspectionPlotReducer(name, covered, buckets, alignedSlotUs = slot)
        assertFalse(summary.canMergeInto(reducer(4000000, 64)))
        assertFalse(summary.canMergeInto(reducer(12000000, 16)))
        assertFalse(summary.canMergeInto(reducer(null)))
        assertFalse(summary.canMergeInto(reducer(8000000, 16, AnalysisRange(1, 100000000))))
        assertFalse(summary.canMergeInto(reducer(8000000, 16, AnalysisRange(0, 99999999))))
        assertFalse(summary.canMergeInto(reducer(8000000, 16, name = "cadenceRpm")))
        val consumed = reducer(8000000, 16)
        consumed.append(inputs.first().item)
        assertFalse(summary.canMergeInto(consumed))
        assertFailsWith<IllegalArgumentException> { summary.mergeInto(reducer(4000000, 64)) }
        for (slot in listOf(8000000L, 16000000L, 24000000L)) {
            val direct = reducer(slot, 16)
            inputs.forEach { feed(direct, Form.item, it) }
            val merged = reducer(slot, 16)
            assertTrue(summary.canMergeInto(merged))
            summary.mergeInto(merged)
            assertSameResult(direct, merged, "slot $slot")
        }
        val compactSummary = InspectionPlotSummary(metric)
        inputs.forEach { feed(compactSummary, Form.compact, it) }
        assertFalse(compactSummary.canMergeInto(reducer(8000000, 16)))
        assertTrue(compactSummary.canMergeInto(destination(metric, range, 16, 8000000, 16384)))
    }

    @Test
    fun shortRidesKeepExactWidthBuckets() {
        val metric = "humanPowerW"
        val summary = InspectionPlotSummary(metric)
        val inputs =
            (0 until 240).map { Input(item(metric, it + 1L, it * 125000L, Scalar.Real64(it % 5 * 50.0)), it + 1L) }
        inputs.forEach { feed(summary, Form.item, it) }
        val range = AnalysisRange(0, inputs.last().item.collectionUs)
        assertNull(InspectionPlotSlots.alignedSlotUs(range, 64))
        assertFalse(summary.canMergeInto(InspectionPlotReducer(metric, range, 64)))
        assertEquals(1000000L, summary.slotUs)
        val slot = assertNotNull(InspectionPlotSlots.alignedSlotUs(range, 16))
        val direct = InspectionPlotReducer(metric, range, 16, alignedSlotUs = slot)
        inputs.forEach { feed(direct, Form.item, it) }
        val merged = InspectionPlotReducer(metric, range, 16, alignedSlotUs = slot)
        summary.mergeInto(merged)
        assertSameResult(direct, merged, "short ride")
    }

    @Test
    fun rejectedInputsLeaveTheSummaryUnchanged() {
        val metric = "humanPowerW"
        val summary = InspectionPlotSummary(metric, 512, 16384, 2)
        val inputs = (0 until 12).map { Input(item(metric, it + 1L, it * 500000L, Scalar.Real64(it * 10.0)), it + 1L) }
        inputs.forEach { feed(summary, Form.item, it) }
        val before = summary.rawInputs
        assertFailsWith<IllegalArgumentException> { summary.append(item(metric, 99, 1000000, Scalar.Real64(1.0))) }
        assertFailsWith<IllegalArgumentException> {
            summary.appendCompact(compact(metric, inputs.last()), CallIdentity().function)
        }
        assertFailsWith<IllegalArgumentException> {
            InspectionPlotSummary(metric).appendDistance(distance(metric, inputs[0]))
        }
        summary.append(item(metric, 100, 9000000, Scalar.Real64(1.0)), selected = false)
        summary.append(item(metric, 101, 9000000, Scalar.Real64(1.0), reading = false))
        assertEquals(before, summary.rawInputs)
        assertFalse(summary.overflowed)
        val range = AnalysisRange(0, 6000000)
        val direct = InspectionPlotReducer(metric, range, 4, alignedSlotUs = 2000000)
        inputs.forEach { feed(direct, Form.item, it) }
        val merged = InspectionPlotReducer(metric, range, 4, alignedSlotUs = 2000000)
        summary.mergeInto(merged)
        assertSameResult(direct, merged, "after rejected inputs")
    }

    @Test
    fun compactSummaryResolvesIdentitiesThroughTheCurrentCallOrTheDestination() {
        val metric = "humanPowerW"
        val summary = InspectionPlotSummary(metric, 512, 16384, 3)
        val inputs = (0 until 40).map { Input(item(metric, it + 1L, it / 4 * 1000000L, Scalar.Real64(100.0)), it + 1L) }
        inputs.forEach { feed(summary, Form.compact, it) }
        val resolved = mutableListOf<Long>()
        val range = AnalysisRange(0, 10000000)
        val merged =
            InspectionPlotReducer(
                metric,
                range,
                10,
                identity = {
                    resolved.add(it)
                    name(it)
                },
                alignedSlotUs = 1000000,
            )
        summary.mergeInto(merged)
        val points = merged.result()
        resolved.clear()
        val identities = points.map { it.point.identity() }
        assertEquals(points.map { name(requireNotNull(it.point.reference).reference) }, identities)
        assertEquals(points.map { requireNotNull(it.point.reference).reference }, resolved)
        val direct = destination(metric, range, 10, 1000000, 16384)
        inputs.forEach { feed(direct, Form.compact, it) }
        assertEquals(direct.result(), points)
    }

    @Test
    fun numericPagesFeedTheSummaryLikeTheReducer() {
        val rows =
            mutableListOf(
                longArrayOf(1, 0, 100.0.toBits(), 0, 0, 0, Double.NaN.toBits(), 0),
                longArrayOf(2, 1000000, 0, 2, 0, 1, Double.NaN.toBits(), 0),
                longArrayOf(3, 2000000, 120.0.toBits(), 0, 0, 2, Double.NaN.toBits(), 0),
                longArrayOf(4, 2000000, 90L, 1, 0, 3, Double.NaN.toBits(), 0),
                longArrayOf(5, 9000000, 130.0.toBits(), 4, 0, 4, Double.NaN.toBits(), 0),
            )
        val input =
            object : InspectionNumericInput {
                override val evidenceCount = 1
                override val count
                    get() = rows.size

                override fun word(index: Int, field: Int) = rows[index][field]

                override fun continuity(index: Int) = Continuity()

                override fun producer(index: Int) = "controller"
            }
        val range = AnalysisRange(0, 9000000)
        fun compare(metric: String) {
            val summary = InspectionPlotSummary(metric, 512, 16384, 2)
            InspectionNumericPages.summary(summary, input, ::name)
            val direct = destination(metric, range, 4, 4000000, 16384)
            InspectionNumericPages.plot(direct, input)
            val merged = destination(metric, range, 4, 4000000, 16384)
            summary.mergeInto(merged)
            assertSameResult(direct, merged, metric)
            assertEquals(rows.size.toLong(), summary.rawInputs)
        }
        compare("heartRateBpm")
        rows.removeAt(1)
        rows.forEachIndexed { index, row ->
            row[3] = row[3] and 4 or 8
            row[2] = (index * 10.0).toBits()
            row[7] = index / 2L
        }
        compare("distanceMeters")
    }
}
