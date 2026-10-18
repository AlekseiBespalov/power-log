package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.test.*

internal fun inspectionFixture(name: String): J = fixture("inspection/$name.json")

internal fun inspectionItem(row: J, metric: String, index: Int): AnalysisItem {
    val value = row["value"]
    val values = mutableMapOf<String, Scalar>()
    if (value != J.Null)
        values[metric] =
            if (value is J.Str) {
                if (value.text() == "NaN") Scalar.Real64(Double.NaN) else Scalar.Integer64(value.long())
            } else Scalar.Real64(value.double())
    row["accuracy"].optionalDouble()?.let {
        values[if (metric == "courseDegrees") "courseAccuracyDegrees" else "speedAccuracyMps"] = Scalar.Real64(it)
    }
    val timeUs = row["timeUs"].long()
    val id = row["id"].text()
    return AnalysisItem(
        timeUs,
        row["elapsedSeconds"].optionalDouble() ?: FixedPoint.divide(timeUs, 1000000),
        id,
        id,
        row["producer"].optionalText() ?: "controller",
        row["sequence"].optionalText()?.toLong() ?: (index.toLong() + 1),
        row["kind"].optionalText() ?: "telemetry",
        "controller",
        "2026-01-01T00:00:00.123456789Z",
        values,
        continuity =
            Continuity(
                row["clock"].optionalText(),
                row["connection"].optionalText(),
                row["interruptionIndex"].optionalText()?.toInt() ?: 0,
            ),
        declaredMetrics = if (row["declared"] == J.Null) listOf(metric) else row["declared"].list().map { it.text() },
    )
}

internal fun inspectionTimeline(row: J, endUs: Long): ActiveTimeline {
    val lifecycle =
        if (row["lifecycle"] == J.Null) emptyList()
        else
            row["lifecycle"].list().mapIndexed { i, event ->
                AnalysisItem(
                    event["timeUs"].long(),
                    FixedPoint.divide(event["timeUs"].long(), 1000000),
                    "event-$i",
                    "event-$i",
                    "phone",
                    i.toLong() + 1,
                    "lifecycle",
                    "phone",
                    "2026-01-01T00:00:00Z",
                    action = event["action"].text(),
                    interrupted = event["interrupted"] != J.Null && event["interrupted"].bool(),
                    controllerSequence = event["controllerSequence"].optionalText()?.toLong(),
                )
            }
    return ActiveTimeline(endUs, lifecycle)
}

class InspectionContractTest {
    @Test
    fun renderedContinuityDoesNotDiscardRetainedOriginals() {
        fun point(seconds: Long, value: Double) =
            InspectionPoint(
                seconds * 1000000,
                Original("$seconds", "$seconds", "2026-01-01T00:00:00Z", seconds.toDouble(), Scalar.Real64(value)),
                Continuity(),
            )
        val first = point(0, 100.0)
        val last = point(100, 200.0)
        assertFalse(InspectionOrder.startsRenderedSegment("humanPowerW", first, last, false))
        assertTrue(InspectionOrder.startsRenderedSegment("humanPowerW", first, last, null))
        assertTrue(InspectionOrder.startsRenderedSegment("courseDegrees", point(0, 350.0), point(1, 10.0), false))
        val query = OriginalInspection("humanPowerW", InspectionTarget(50000000))
        query.append(first)
        query.append(last)
        assertNull(query.result())
        assertEquals(first.original, query.resultForRendered(true))
        assertNull(query.resultForRendered(false))
    }

    private fun gapPoint(timeUs: Long, id: String, interruption: Int = 0) =
        InspectionPoint(
            timeUs,
            Original(id, id, "", FixedPoint.divide(timeUs, 1000000), Scalar.Real64(100.0)),
            Continuity(interruptionIndex = interruption),
        )

    @Test
    fun n1PlotGapsPreserveExclusiveBoundaries() {
        val first = gapPoint(0, "first")
        val breaks =
            listOf(5999999L, 6000000L, 6000001L).map {
                InspectionOrder.startsSegment("humanPowerW", first, gapPoint(it, "next"))
            }
        assertEquals(listOf(false, true, true), breaks)
    }

    @Test
    fun n1NeighbourGapsPreserveExclusiveBoundaries() {
        val selected =
            listOf(5999999L, 6000000L, 6000001L).map {
                val inspection = OriginalInspection("humanPowerW", InspectionTarget(it))
                inspection.append(gapPoint(0, "first"))
                inspection.result(allowTrailing = true)?.observationId
            }
        assertEquals(listOf("first", null, null), selected)
    }

    @Test
    fun n1SelectionEqualKeysPreserveIdentityOrder() {
        val inspection = OriginalInspection("humanPowerW", InspectionTarget(1000000))
        inspection.append(gapPoint(1000000, "z-closing"), interruptionBoundary = true)
        inspection.append(gapPoint(1000000, "a-resumed", 1), interruptionBoundary = true)
        assertEquals("a-resumed", inspection.result()?.observationId)
    }

    @Test
    fun n1InspectionRangeAndStopUseFlooredTime() {
        val points =
            listOf(1.0000002, 1.0000008).mapIndexed { i, time ->
                AnalysisItem(
                    1000000,
                    time,
                    "$i",
                    "$i",
                    "controller",
                    i.toLong(),
                    "telemetry",
                    "controller",
                    "",
                    mapOf("humanPowerW" to Scalar.Real64(100.0 + i * 100)),
                )
            }
        val inspection = OriginalInspection("humanPowerW", InspectionTarget(FixedPoint.timeUs(1.0000008)))
        points.forEach {
            inspection.append(InspectionPoint(it.collectionUs, it.original("humanPowerW")!!, it.continuity))
        }
        assertEquals("0", inspection.result()!!.observationId)
        fun distance(timeUs: Long, id: String) =
            DistanceInspectionPoint(
                InspectionPoint(
                    timeUs,
                    Original(id, id, "", FixedPoint.divide(timeUs, 1000000), Scalar.Real64(timeUs.toDouble())),
                    Continuity(),
                ),
                0,
                0,
                2000000,
                timeUs,
            )
        assertEquals(
            "before",
            DistanceInspection.select(
                    InspectionTarget(FixedPoint.timeUs(1.0000001)),
                    distance(0, "before"),
                    distance(2000000, "after"),
                )!!
                .observationId,
        )
        fun query(start: Double, end: Double, stop: Double? = null) =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(FixedPoint.timeUs(start), FixedPoint.timeUs(end)),
                ActiveTimeline(FixedPoint.timeUs(stop ?: 30.0), emptyList()),
                identity = { it.toString() },
            )
        val range = query(1.0000005, 1.0000009)
        val stop = query(0.0, 30.0, 1.0000005)
        val plot = InspectionPlotReducer("humanPowerW", AnalysisRange(0, 2000000), 1, identity = { it.toString() })
        points.forEachIndexed { i, item ->
            val compact =
                InspectionCompactPoint(
                    i.toLong(),
                    item.collectionUs,
                    item.values["humanPowerW"],
                    item.continuity,
                    item.producer,
                    item.sequence,
                )
            range.appendCompact(compact)
            stop.appendCompact(compact)
            plot.appendCompact(compact)
        }
        assertEquals(listOf(1000000L, 1000000L), plot.result().map { it.point.timeUs })
        assertEquals(listOf(1.0000002, 1.0000008), points.map { it.original("humanPowerW")!!.elapsedSeconds })
        assertEquals(2L, (range.result().statistics as MetricOutcome.Complete).result.statistics.count)
        assertEquals(2L, (stop.result().statistics as MetricOutcome.Complete).result.statistics.count)
    }

    @Test
    fun numericPagesRetainRawBarriersAndDistanceSegments() {
        val rows =
            mutableListOf(
                longArrayOf(1, 0, 100.0.toBits(), 0, 0, 0, Double.NaN.toBits(), 0),
                longArrayOf(2, 1000000, 0, 2, 0, 0, Double.NaN.toBits(), 0),
                longArrayOf(3, 2000000, 120.0.toBits(), 0, 0, 0, Double.NaN.toBits(), 0),
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
        val plot = InspectionPlotReducer("heartRateBpm", AnalysisRange(0, 3000000), 1, identity = { it.toString() })
        InspectionNumericPages.plot(plot, input)
        assertEquals(listOf(0, 1), plot.result().map { it.segment })
        val query =
            InspectionQuery(
                "heartRateBpm",
                AnalysisRange(0, 3000000),
                ActiveTimeline(3000000, emptyList()),
                identity = { it.toString() },
            )
        InspectionNumericPages.query(query, input)
        assertEquals(0.0, (query.result().statistics as MetricOutcome.Complete).result.statistics.integral)
        val distance =
            InspectionPlotReducer("distanceMeters", AnalysisRange(0, 3000000), 1, identity = { it.toString() })
        rows.removeAt(1)
        rows.forEachIndexed { i, row ->
            row[3] = 8
            row[7] = i.toLong()
        }
        InspectionNumericPages.plot(distance, input)
        assertEquals(listOf(0, 1), distance.result().map { it.segment })
    }

    @Test fun inspectionFullPlotOrderFixtures() = plotOrderFixtures(false)

    @Test fun inspectionCompactPlotOrderFixtures() = plotOrderFixtures(true)

    private fun plotOrderFixtures(compact: Boolean) {
        for (row in inspectionFixture("plot-order")["cases"].list()) {
            val metric = row["metric"].text()
            val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
            val originals = row["points"].list().mapIndexed { i, p -> inspectionItem(p, metric, i) }
            val references = row["points"].list().map { it["reference"].long() }
            val identity: (Long) -> String = { originals[references.indexOf(it)].observationId }
            fun reducer(owned: Boolean = false) =
                InspectionPlotReducer(metric, range, 1, owned = owned, identity = identity)
            fun append(to: InspectionPlotReducer) {
                originals.forEachIndexed { i, item ->
                    val boundary = row["points"].list()[i]["boundary"] == J.Bool(true)
                    if (compact)
                        to.appendCompact(
                            InspectionCompactPoint(
                                references[i],
                                item.collectionUs,
                                item.values[metric],
                                item.continuity,
                                item.producer,
                                item.sequence,
                                interruptionBoundary = boundary,
                            )
                        )
                    else to.append(item, interruptionBoundary = boundary)
                }
            }
            fun check(points: List<InspectionPlotPoint>) {
                assertEquals(
                    row["expected"].list().map { it.text() },
                    points.map {
                        if (compact) identity(requireNotNull(it.point.reference).reference)
                        else it.point.original.observationId
                    },
                    row["id"].text(),
                )
                assertEquals(
                    row["segments"].list().map { it.long().toInt() },
                    points.map { it.segment },
                    row["id"].text(),
                )
            }
            val raw = reducer()
            append(raw)
            check(raw.result())
            if (row["aggregate"] == J.Bool(true)) {
                val leaf = reducer(true)
                append(leaf)
                val aggregate = leaf.aggregate()
                val adopted =
                    if (compact)
                        InspectionPlotAggregate(
                            metric,
                            range,
                            aggregate.points,
                            aggregate.firstRawUs,
                            aggregate.lastRawUs,
                            false,
                            false,
                            true,
                        )
                    else InspectionCacheCodec.readPlot(InspectionCacheCodec.plot(aggregate))
                val parent = reducer(true)
                parent.merge(adopted)
                val merged = reducer()
                merged.merge(parent.aggregate())
                check(merged.result())
                val following = inspectionItem(row["following"], metric, originals.size)
                if (compact)
                    merged.appendCompact(
                        InspectionCompactPoint(
                            row["following"]["reference"].long(),
                            following.collectionUs,
                            following.values[metric],
                            following.continuity,
                            following.producer,
                            following.sequence,
                        )
                    )
                else merged.append(following)
                assertEquals(1, merged.result().last().segment, row["id"].text())
            }
        }
    }

    @Test
    fun inspectionAlignedPlotFixtures() {
        for (row in inspectionFixture("aligned-plot")["cases"].list()) {
            val metric = row["metric"].text()
            val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
            val nodeRange = AnalysisRange(row["nodeStartUs"].long(), row["nodeEndUs"].long())
            val originals = row["points"].list().mapIndexed { i, p -> inspectionItem(p, metric, i) }
            for (compact in listOf(false, true)) {
                val identity: (Long) -> String = { originals[it.toInt() - 1].observationId }
                fun append(to: InspectionPlotReducer) {
                    originals.forEachIndexed { i, item ->
                        if (compact)
                            to.appendCompact(
                                InspectionCompactPoint(
                                    (i + 1).toLong(),
                                    item.collectionUs,
                                    item.values[metric],
                                    item.continuity,
                                    item.producer,
                                    item.sequence,
                                )
                            )
                        else to.append(item)
                    }
                }
                val leaf = InspectionPlotReducer(metric, nodeRange, 1, owned = true, identity = identity)
                append(leaf)
                val aggregate = leaf.aggregate()
                assertFalse(InspectionPlotReducer(metric, range, row["buckets"].long().toInt()).canMerge(aggregate))
                fun query() =
                    InspectionPlotReducer(
                        metric,
                        range,
                        row["buckets"].long().toInt(),
                        identity = identity,
                        alignedSlotUs = row["alignedSlotUs"].long(),
                    )
                val raw = query()
                append(raw)
                val cached = query()
                assertTrue(cached.canMerge(aggregate), row["id"].text())
                cached.merge(aggregate)
                assertEquals(raw.result(), cached.result(), row["id"].text())
                assertEquals(
                    row["expected"].list().map { it.text() },
                    cached.result().map {
                        if (compact) identity(requireNotNull(it.point.reference).reference)
                        else it.point.original.observationId
                    },
                    row["id"].text(),
                )
                assertEquals(row["segments"].list().map { it.long().toInt() }, cached.result().map { it.segment })
                assertFailsWith<IllegalArgumentException> {
                    InspectionPlotReducer(metric, range, 2, alignedSlotUs = 10000000)
                }
            }
        }
    }

    @Test
    fun inspectionDistancePlotFixtures() {
        for (row in inspectionFixture("distance-plot")["cases"].list()) {
            val plot =
                InspectionPlotReducer("distanceMeters", AnalysisRange(row["startUs"].long(), row["endUs"].long()), 1)
            row["points"].list().forEachIndexed { i, point ->
                val item = inspectionItem(point, "distanceMeters", i)
                plot.appendDistance(
                    DistanceInspectionPoint(
                        inspectionPoint(item, "distanceMeters"),
                        point["segment"].long().toInt(),
                        point["startUs"].long(),
                        point["endUs"].long(),
                        point["tieKey"].long(),
                        point["plotSegment"].long().toInt(),
                    )
                )
            }
            val result = plot.result()
            assertEquals(
                row["expected"].list().map { it.text() },
                result.map { it.point.original.observationId },
                row["id"].text(),
            )
            assertEquals(row["segments"].list().map { it.long().toInt() }, result.map { it.segment }, row["id"].text())
        }
    }

    @Test
    fun inspectionCompactParentFixtures() {
        for (row in inspectionFixture("compact")["cases"].list()) {
            val metric = row["metric"].text()
            val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
            val timeline = ActiveTimeline(range.endUs, emptyList())
            val originals = row["points"].list().mapIndexed { i, p -> inspectionItem(p, metric, i) }
            val compact =
                row["points"].list().mapIndexed { i, p ->
                    InspectionCompactPoint(
                        p["reference"].long(),
                        p["timeUs"].long(),
                        originals[i].values[metric],
                        originals[i].continuity,
                        originals[i].producer,
                        originals[i].sequence,
                        p["accuracy"].optionalDouble()?.let(Scalar::Real64),
                    )
                }
            val rawStats = InspectionQuery(metric, range, timeline)
            val rawPlot = InspectionPlotReducer(metric, range, 1)
            originals.forEach {
                rawStats.append(it)
                rawPlot.append(it)
            }
            val byReference = compact.mapIndexed { i, point -> point.reference to originals[i] }.toMap()
            val identityReads = mutableListOf<Long>()
            val identity: (Long) -> String = { ref ->
                identityReads.add(ref)
                byReference.getValue(ref).observationId
            }
            val parentRange = AnalysisRange(row["parentStartUs"].optionalText()?.toLong() ?: range.startUs, range.endUs)
            val statsParent =
                InspectionQuery(
                    metric,
                    parentRange,
                    timeline,
                    bucket = true,
                    compactPredecessor = compact.lastOrNull { it.timeUs < parentRange.startUs },
                    identity = identity,
                )
            val plotParent = InspectionPlotReducer(metric, parentRange, 1, owned = true, identity = identity)
            for (bounds in row["buckets"].list()) {
                val owned = AnalysisRange(bounds.list()[0].long(), bounds.list()[1].long())
                val statsLeaf =
                    InspectionQuery(
                        metric,
                        owned,
                        timeline,
                        bucket = true,
                        compactPredecessor = compact.lastOrNull { it.timeUs < owned.startUs },
                        identity = identity,
                    )
                val plotLeaf = InspectionPlotReducer(metric, owned, 1, owned = true, identity = identity)
                compact
                    .filter { it.timeUs >= owned.startUs && it.timeUs < owned.endUs }
                    .forEach {
                        statsLeaf.appendCompact(it)
                        plotLeaf.appendCompact(it)
                    }
                statsParent.mergeOwned(assertNotNull(statsLeaf.checkpoint()))
                plotParent.merge(plotLeaf.aggregate())
            }
            val stats = InspectionQuery(metric, range, timeline, identity = identity)
            compact.filter { it.timeUs < parentRange.startUs }.forEach { stats.appendCompact(it) }
            stats.mergeOwned(assertNotNull(statsParent.checkpoint()))
            val plot = InspectionPlotReducer(metric, range, 1, identity = identity)
            compact.filter { it.timeUs < parentRange.startUs }.forEach { plot.appendCompact(it) }
            plot.merge(plotParent.aggregate())
            val result = assertIs<MetricOutcome.Complete>(stats.result().statistics).result
            assertEquals(row["count"].long(), result.statistics.count, row["id"].text())
            assertEquals(row["coveredUs"].long(), result.coveredUs, row["id"].text())
            assertEquals(row["integral"].optionalDouble(), result.statistics.integral, row["id"].text())
            assertEquals(row["minimum"].long(), result.minimum?.reading?.reference?.reference, row["id"].text())
            assertEquals(row["maximum"].long(), result.maximum?.reading?.reference?.reference, row["id"].text())
            assertNull(result.statistics.min)
            assertNull(result.statistics.max)
            val plotted = plot.result()
            assertEquals(
                row["expected"].list().map { it.long() },
                plotted.map { it.point.reference?.reference },
                row["id"].text(),
            )
            assertEquals(row["segments"].list().map { it.long().toInt() }, plotted.map { it.segment }, row["id"].text())
            assertEquals(row["identityReads"].bool(), identityReads.isNotEmpty(), row["id"].text())
            assertTrue(identityReads.all { byReference.getValue(it).collectionUs == 0L }, row["id"].text())
            val hydrated = plotted.map {
                byReference.getValue(requireNotNull(it.point.reference).reference).original(metric)
            }
            assertEquals(
                row["expected"].list().map { byReference.getValue(it.long()).observationId },
                hydrated.map { it?.observationId },
            )
            assertEquals(rawPlot.result().map { it.point.original.observationId }, hydrated.map { it?.observationId })
            assertFailsWith<IllegalArgumentException> {
                InspectionCacheCodec.statistics(assertNotNull(statsParent.checkpoint()))
            }
            val rawResult = assertIs<MetricOutcome.Complete>(rawStats.result().statistics).result
            assertEquals(rawResult.sumQ, result.sumQ)
            assertEquals(rawResult.integralQUs, result.integralQUs)
            assertEquals(
                rawResult.statistics.min?.observationId,
                byReference.getValue(row["minimum"].long()).observationId,
            )
            assertEquals(
                rawResult.statistics.max?.observationId,
                byReference.getValue(row["maximum"].long()).observationId,
            )
        }
    }

    @Test
    fun inspectionPersistedStatisticsBoundsFixtures() {
        for (row in inspectionFixture("accelerators")["invalidStatistics"].list()) {
            val metric = row["metric"].text()
            val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
            val query = InspectionQuery(metric, range, ActiveTimeline(range.endUs, emptyList()), bucket = true)
            row["points"].list().forEachIndexed { i, point -> query.append(inspectionItem(point, metric, i)) }
            val saved = assertNotNull(query.checkpoint())
            val outside = saved.firstRaw?.copy(timeUs = row["outsideUs"].optionalText()?.toLong() ?: 0)
            val checkpoint =
                when (row["field"].optionalText()) {
                    "firstRaw" -> saved.copy(firstRaw = outside)
                    "lastRaw" -> saved.copy(lastRaw = outside)
                    "minimum" -> saved.copy(minimum = saved.minimum?.copy(reading = requireNotNull(outside)))
                    "maximum" -> saved.copy(maximum = saved.maximum?.copy(reading = requireNotNull(outside)))
                    "previous" -> saved.copy(previous = saved.previous?.copy(reading = requireNotNull(outside)))
                    "originalElapsed" ->
                        saved.copy(
                            firstRaw =
                                saved.firstRaw?.copy(
                                    original =
                                        saved.firstRaw.original?.copy(
                                            elapsedSeconds = FixedPoint.divide(row["outsideUs"].long(), 1000000)
                                        )
                                )
                        )
                    else -> saved.copy(range = AnalysisRange(row["shiftedStartUs"].long(), row["shiftedEndUs"].long()))
                }
            assertFailsWith<IllegalArgumentException>(row["id"].text()) {
                InspectionCacheCodec.readStatistics(InspectionCacheCodec.statistics(checkpoint))
            }
        }
    }

    @Test
    fun retainedEncodingContextPreservesFractionalOriginalsAcrossEviction() {
        val row = inspectionFixture("accelerators")["statistics"].list().first()
        val metric = row["metric"].text()
        val item = inspectionItem(row["points"].list().first(), metric, 0)
        val range = AnalysisRange(row["startUs"].long(), row["endUs"].long())
        val timeline = inspectionTimeline(row, row["rideEndUs"].long())
        val encoding = InspectionCacheEncoding()
        repeat(160) { index ->
            val original = item.copy(collectionElapsedSeconds = item.collectionElapsedSeconds + index / 1000000000.0)
            val query = InspectionQuery(metric, range, timeline, bucket = true)
            query.append(original)
            val checkpoint = assertNotNull(query.checkpoint())
            val reducer = InspectionPlotReducer(metric, range, 1, 16, owned = true)
            reducer.append(original)
            val plot = reducer.aggregate()
            repeat(2) {
                assertEquals(InspectionCacheCodec.statistics(checkpoint), encoding.statistics(checkpoint))
                assertEquals(InspectionCacheCodec.plot(plot), encoding.plot(plot))
                assertEquals(checkpoint, InspectionCacheCodec.readStatistics(encoding.statistics(checkpoint)))
                val restored = InspectionCacheCodec.readPlot(encoding.plot(plot))
                assertEquals(plot.points, restored.points)
                assertEquals(original.collectionElapsedSeconds, restored.points.single().point.original.elapsedSeconds)
                assertEquals(original.timestamp, restored.points.single().point.original.timestamp)
            }
        }
        assertEquals(128, encoding.retainedNumberCount)
    }

    @Test
    fun inspectionStatisticsAcceleratorFixtures() {
        for (row in inspectionFixture("accelerators")["statistics"].list()) {
            val metric = row["metric"].text()
            val timeline = inspectionTimeline(row, row["rideEndUs"].long())
            val originals = row["points"].list().mapIndexed { i, point -> inspectionItem(point, metric, i) }
            val query =
                InspectionQuery(
                    metric,
                    AnalysisRange(row["startUs"].long(), row["endUs"].long()),
                    timeline,
                    InspectionTarget(16125000),
                )
            val restored = InspectionQuery(metric, query.range, timeline)
            for (bounds in row["buckets"].list()) {
                val range = AnalysisRange(bounds.list()[0].long(), bounds.list()[1].long())
                val bucket =
                    InspectionQuery(
                        metric,
                        range,
                        timeline,
                        bucket = true,
                        predecessor = originals.lastOrNull { it.collectionUs < range.startUs },
                    )
                val rows = originals.filter { it.collectionUs >= range.startUs && it.collectionUs < range.endUs }
                rows.forEach { bucket.append(it) }
                val checkpoint = assertNotNull(bucket.checkpoint())
                val reopened = InspectionCacheCodec.readStatistics(InspectionCacheCodec.statistics(checkpoint))
                assertEquals(checkpoint, reopened)
                if (query.canMerge(reopened)) query.mergeOwned(reopened) else rows.forEach { query.append(it) }
            }
            originals.forEach { query.appendInspection(it) }
            val result = assertIs<MetricOutcome.Complete>(query.result().statistics).result
            assertEquals(row["count"].long(), result.statistics.count, row["id"].text())
            assertEquals(row["coveredUs"].long(), result.coveredUs, row["id"].text())
            assertEquals(row["integral"].optionalDouble(), result.statistics.integral, row["id"].text())
            assertEquals(row["mean"].optionalDouble(), result.statistics.sampleMean, row["id"].text())
            assertEquals(row["minimum"].text(), result.statistics.min?.observationId, row["id"].text())
            assertEquals(row["maximum"].text(), result.statistics.max?.observationId, row["id"].text())
            restored.restore(assertNotNull(query.checkpoint()))
            assertEquals(result, assertIs<MetricOutcome.Complete>(restored.result().statistics).result)
        }
    }

    @Test
    fun inspectionPlotAcceleratorFixtures() {
        for (row in inspectionFixture("accelerators")["plot"].list()) {
            val metric = row["metric"].text()
            val originals = row["points"].list().mapIndexed { i, point -> inspectionItem(point, metric, i) }
            val query = InspectionPlotReducer(metric, AnalysisRange(row["startUs"].long(), row["endUs"].long()), 1)
            for (bounds in row["buckets"].list()) {
                val range = AnalysisRange(bounds.list()[0].long(), bounds.list()[1].long())
                val bucket = InspectionPlotReducer(metric, range, 1, owned = true)
                originals.forEach { bucket.append(it) }
                val aggregate = bucket.aggregate()
                val reopened = InspectionCacheCodec.readPlot(InspectionCacheCodec.plot(aggregate))
                assertEquals(aggregate.points, reopened.points)
                assertTrue(query.canMerge(reopened), row["id"].text())
                query.merge(reopened)
            }
            val result = query.result()
            assertEquals(
                row["expected"].list().map { it.text() },
                result.map { it.point.original.observationId },
                row["id"].text(),
            )
            assertEquals(row["segments"].list().map { it.long().toInt() }, result.map { it.segment }, row["id"].text())
        }
    }

    @Test
    fun inspectionAcceleratorAdmissionGuards() {
        val row = inspectionFixture("accelerators")["statistics"].list().first()
        val timeline = inspectionTimeline(row, 20000000)
        val points = row["points"].list().mapIndexed { i, point -> inspectionItem(point, "humanPowerW", i) }
        val bucket =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(16000000, 17000000),
                timeline,
                bucket = true,
                predecessor = points.first(),
            )
        points.drop(1).forEach { bucket.append(it) }
        val checkpoint = assertNotNull(bucket.checkpoint())
        assertFalse(InspectionQuery("humanPowerW", AnalysisRange(16000000, 20000000), timeline).canMerge(checkpoint))
        assertFalse(InspectionQuery("cadenceRpm", AnalysisRange(0, 20000000), timeline).canMerge(checkpoint))
        val query = InspectionQuery("humanPowerW", AnalysisRange(0, 20000000), timeline)
        query.mergeOwned(checkpoint)
        assertFalse(query.canMerge(checkpoint))
        assertFailsWith<IllegalArgumentException> { query.append(points.last()) }
        val plot = InspectionPlotReducer("humanPowerW", AnalysisRange(15000000, 17000000), 1, owned = true)
        points.forEach { plot.append(it) }
        val aggregate = plot.aggregate()
        assertFalse(InspectionPlotReducer("humanPowerW", AnalysisRange(15000000, 17000000), 4).canMerge(aggregate))
        assertFalse(InspectionPlotReducer("humanPowerW", AnalysisRange(16000000, 17000000), 1).canMerge(aggregate))
        assertFalse(InspectionPlotReducer("cadenceRpm", AnalysisRange(0, 20000000), 1).canMerge(aggregate))
        val complete = InspectionPlotReducer("humanPowerW", AnalysisRange(0, 20000000), 1)
        complete.merge(aggregate)
        assertFalse(complete.canMerge(aggregate))
        val incomplete =
            InspectionPlotAggregate(
                "humanPowerW",
                aggregate.range,
                aggregate.points.mapIndexed { index, point -> point.copy(segment = index) },
                aggregate.firstRawUs,
                aggregate.lastRawUs,
                false,
                false,
            )
        assertFalse(InspectionPlotReducer("humanPowerW", AnalysisRange(0, 20000000), 1).canMerge(incomplete))
        val invalid = points.first().copy(values = mapOf("humanPowerW" to Scalar.Real64(Double.NaN)))
        val invalidBucket = InspectionQuery("humanPowerW", AnalysisRange(15000000, 16000000), timeline, bucket = true)
        invalidBucket.append(invalid)
        assertTrue(
            InspectionCacheCodec.readStatistics(
                    InspectionCacheCodec.statistics(assertNotNull(invalidBucket.checkpoint()))
                )
                .firstRaw
                ?.original
                ?.value
                ?.real()
                ?.isNaN() == true
        )
        assertFailsWith<IllegalArgumentException> { InspectionCacheCodec.readPlot("{}") }
    }

    @Test
    fun inspectionTimeKeysAndExactIntegers() {
        val query =
            InspectionQuery("consumedWh", AnalysisRange(0, 1), ActiveTimeline(1, emptyList()), InspectionTarget(0))
        val rows =
            listOf(
                AnalysisItem(
                    0,
                    0.0000009,
                    "z",
                    "z",
                    "controller",
                    1,
                    "telemetry",
                    "controller",
                    "original-z",
                    mapOf("consumedWh" to Scalar.Integer64(9007199254740993)),
                ),
                AnalysisItem(
                    0,
                    0.0000008,
                    "a",
                    "a",
                    "controller",
                    2,
                    "telemetry",
                    "controller",
                    "original-a",
                    mapOf("consumedWh" to Scalar.Integer64(9007199254740992)),
                ),
            )
        rows.forEach { query.append(it) }
        val result = query.result()
        assertEquals("a", result.selection?.observationId)
        assertEquals(0.0000008, result.selection?.elapsedSeconds)
        val stats = assertIs<MetricOutcome.Complete>(result.statistics).result.statistics
        assertEquals(Scalar.Integer64(9007199254740992), stats.min?.value)
        assertEquals(Scalar.Integer64(9007199254740993), stats.max?.value)
        assertNull(stats.sampleMean)
    }

    @Test
    fun inspectionInterruptionAndOverflowGuards() {
        val row =
            inspectionFixture("statistics")["cases"].list().first { it["id"].text() == "committed-interruption-close" }
        val query =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(0, 2000000),
                inspectionTimeline(row, 2000000),
                InspectionTarget(1250000),
                allowTrailing = true,
            )
        row["points"].list().take(2).forEachIndexed { index, point ->
            query.append(inspectionItem(point, "humanPowerW", index))
        }
        assertNull(query.result().selection)
        val overflow =
            InspectionQuery("motorInputPowerW", AnalysisRange(0, 1000000), ActiveTimeline(1000000, emptyList()))
        overflow.append(
            AnalysisItem(
                0,
                0.0,
                "a",
                "a",
                "controller",
                1,
                "telemetry",
                "controller",
                "timestamp",
                mapOf("motorInputPowerW" to Scalar.Real64(1e30)),
            )
        )
        assertEquals(
            NumericOverflow("motorInputPowerW", Quantity.sumQ),
            assertIs<MetricOutcome.Overflow>(overflow.result().statistics).failure,
        )
        val unordered = AnalysisItem(-1, -0.000001, "b", "b", "controller", 2, "telemetry", "controller", "timestamp")
        assertFailsWith<IllegalArgumentException> { overflow.append(unordered) }
    }

    @Test
    fun inspectionDistanceFixtures() {
        fun point(row: J): DistanceInspectionPoint? {
            if (row == J.Null) return null
            val item = inspectionItem(row, "distanceMeters", 0)
            return DistanceInspectionPoint(
                InspectionPoint(item.collectionUs, requireNotNull(item.original("distanceMeters")), item.continuity),
                row["segment"].long().toInt(),
                row["startUs"].long(),
                row["endUs"].long(),
                row["tieKey"].long(),
            )
        }
        for (row in inspectionFixture("distance")["cases"].list()) {
            val timeUs = row["timeUs"].long()
            val target = InspectionTarget(timeUs, row["anchor"].optionalText()?.let { InspectionAnchor(it, timeUs) })
            assertEquals(
                row["expected"].optionalText(),
                DistanceInspection.select(target, point(row["before"]), point(row["after"]), point(row["anchored"]))
                    ?.observationId,
                row["id"].text(),
            )
        }
    }

    @Test
    fun inspectionStatisticsFixtures() {
        for (case in inspectionFixture("statistics")["cases"].list()) {
            val id = case["id"].text()
            val metric = case["metric"].text()
            val query =
                InspectionQuery(
                    metric,
                    AnalysisRange(case["startUs"].long(), case["endUs"].long()),
                    inspectionTimeline(case, case["rideEndUs"].long()),
                )
            case["points"].list().forEachIndexed { i, row ->
                query.append(inspectionItem(row, metric, i), row["selected"] == J.Null || row["selected"].bool())
            }
            val outcome = query.result().statistics
            if (case["absent"] == J.Bool(true)) {
                assertNull(outcome, id)
                continue
            }
            val result = assertIs<MetricOutcome.Complete>(outcome, id).result
            assertEquals(case["count"].long(), result.statistics.count, "$id count")
            assertEquals(case["minimum"].optionalText(), result.statistics.min?.observationId, "$id min")
            assertEquals(case["maximum"].optionalText(), result.statistics.max?.observationId, "$id max")
            assertEquals(case["mean"].optionalDouble(), result.statistics.sampleMean, "$id sampleMean")
            assertEquals(case["coveredUs"].long(), result.coveredUs, "$id coveredUs")
            assertEquals(case["integral"].optionalDouble(), result.statistics.integral, "$id integral")
            if (case["suppressed"] == J.Bool(true)) {
                assertNull(result.statistics.coveredSeconds, id)
                assertNull(result.sumQ, id)
                assertNull(result.integralQUs, id)
            }
        }
    }

    @Test
    fun inspectionExactSelectionFixtures() {
        for (case in inspectionFixture("selection")["cases"].list()) {
            val time = case["timeUs"].long()
            val target =
                InspectionTarget(
                    time,
                    case["anchor"].optionalText()?.let { InspectionAnchor(it, case["anchorTimeUs"].long()) },
                )
            val metric = case["metric"].text()
            val query =
                InspectionQuery(
                    metric,
                    AnalysisRange(0, 20000000),
                    inspectionTimeline(case, 20000000),
                    target,
                    allowTrailing = case["allowTrailing"] == J.Bool(true),
                )
            case["points"].list().forEachIndexed { i, row -> query.append(inspectionItem(row, metric, i)) }
            val selected = query.result().selection
            assertEquals(case["expected"].optionalText(), selected?.observationId, case["id"].text())
            if (selected != null) assertEquals("2026-01-01T00:00:00.123456789Z", selected.timestamp)
        }
    }

    @Test
    fun inspectionPlotFixtures() {
        for (case in inspectionFixture("plot")["cases"].list()) {
            val metric = case["metric"].text()
            val reducer =
                InspectionPlotReducer(
                    metric,
                    AnalysisRange(
                        case["startUs"].optionalText()?.toLong() ?: 0,
                        case["endUs"].optionalText()?.toLong() ?: 20000000,
                    ),
                    1,
                )
            case["points"].list().forEachIndexed { i, row ->
                reducer.append(
                    inspectionItem(row, metric, i),
                    interruptionBoundary = row["boundary"] == J.Bool(true),
                )
            }
            val result = reducer.result()
            assertEquals(
                case["expected"].list().map { it.text() },
                result.map { it.point.original.observationId },
                case["id"].text(),
            )
            assertEquals(
                case["segments"].list().map { it.long().toInt() },
                result.map { it.segment },
                case["id"].text(),
            )
        }
    }

    @Test
    fun inspectionOriginalsSurviveDecimation() {
        val case = inspectionFixture("plot")["cases"].list().first()
        val query =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(0, 4000000),
                ActiveTimeline(4000000, emptyList()),
                InspectionTarget(2000000, InspectionAnchor("omitted", 2000000)),
                InspectionTarget(1000000),
            )
        val plot = InspectionPlotReducer("humanPowerW", AnalysisRange(0, 4000000), 1)
        case["points"].list().forEachIndexed { i, row ->
            val item = inspectionItem(row, "humanPowerW", i)
            query.append(item)
            plot.append(item)
        }
        assertFalse(plot.result().any { it.point.original.observationId == "omitted" })
        val result = query.result()
        assertEquals("omitted", result.selection?.observationId)
        assertEquals("b", result.reference?.observationId)
        assertEquals("b", assertIs<MetricOutcome.Complete>(result.statistics).result.statistics.min?.observationId)
    }

    @Test
    fun inspectionAcceptanceFixtures() {
        val f = inspectionFixture("acceptance")
        val c = f["context"]
        fun context(row: J) =
            InspectionContext(
                row["source"].optionalText() ?: c["source"].text(),
                row["revision"].optionalText() ?: c["revision"].text(),
                row["selection"].optionalText() ?: c["selection"].text(),
                (row["epoch"].optionalText() ?: c["epoch"].text()).toLong(),
                (row["sequence"].optionalText() ?: c["sequence"].text()).toLong(),
            )
        val current = InspectionTicket(context(c), 1)
        for (row in f["cases"].list()) {
            val result = InspectionTicket(context(row), row["generation"].optionalText()?.toLong() ?: 1)
            assertEquals(row["accepted"].bool(), InspectionAcceptance.accepts(current, result), row["id"].text())
        }
        val requests = InspectionRequests()
        val tickets = mutableListOf<InspectionTicket>()
        for (row in f["requests"].list()) {
            val ticket = requests.begin(context(row))
            for (old in tickets) assertFalse(requests.accepts(old), "A-B-A cannot revive an old request")
            tickets.add(ticket)
            assertTrue(requests.accepts(ticket))
        }
        requests.cancel()
        tickets.forEach { assertFalse(requests.accepts(it)) }
    }

    @Test
    fun inspectionHeldTailFixtures() {
        val original = Original("a", "a", "2026-01-01T00:00:10Z", 10.0, Scalar.Real64(200.0))
        for (row in inspectionFixture("tail")["cases"].list()) {
            val tail =
                HeldTail.render(
                    "humanPowerW",
                    original,
                    row["evidence"].text(),
                    row["acquired"].optionalDouble(),
                    row["now"].double(),
                    row["elapsed"].double(),
                )
            assertEquals(row["expected"].optionalDouble(), tail?.endSeconds, row["id"].text())
            if (tail != null) assertEquals(10.0, tail.startSeconds)
            assertEquals("2026-01-01T00:00:10Z", original.timestamp)
            assertEquals(10.0, original.elapsedSeconds)
        }
    }

    @Test
    fun plotGapsMatchTheCatalogAndNonCatalogMetrics() {
        for (row in fixture("telemetry/metrics.json")["metrics"].list()) {
            val metric = row["id"].text()
            assertEquals((row["gapSeconds"].double() * 1000000).toLong(), InspectionMetrics.plotGapUs(metric), metric)
        }
        assertFailsWith<IllegalArgumentException> { InspectionMetrics.plotGapUs("totalEnergyKcal") }
        assertEquals(10000000L, InspectionMetrics.plotGapUs("speedAccuracyMps"))
        assertEquals(6000000L, InspectionMetrics.plotGapUs("crankRevolutions"))
        assertFailsWith<IllegalArgumentException> { InspectionMetrics.plotGapUs("unknown") }
        assertFailsWith<IllegalArgumentException> { InspectionMetrics.plotGapUs("") }
    }

    @Test
    fun inspectionPolicyAndBounds() {
        assertEquals(InspectionChannel.telemetry, InspectionMetrics.policy("humanPowerW").channel)
        assertEquals(InspectionChannel.health, InspectionMetrics.policy("heartRateBpm").channel)
        assertEquals(InspectionChannel.location, InspectionMetrics.policy("courseDegrees").channel)
        assertEquals(6000000L, InspectionMetrics.policy("humanPowerW").plotGapUs)
        assertEquals(2500000L, InspectionMetrics.policy("humanPowerW").statistics.gapUs)
        assertEquals(15000000L, InspectionMetrics.policy("heartRateBpm").plotGapUs)
        assertEquals(10000000L, InspectionMetrics.policy("heartRateBpm").statistics.gapUs)
        assertFailsWith<IllegalArgumentException> { InspectionMetrics.policy("distanceMeters") }
        assertFailsWith<IllegalArgumentException> { InspectionMetrics.policy("unknown") }
        assertFailsWith<IllegalArgumentException> { InspectionPlotReducer("humanPowerW", AnalysisRange(0, 1), 513) }
        val plot = InspectionPlotReducer("courseDegrees", AnalysisRange(0, 20000000), 1, 4)
        repeat(5) { i ->
            plot.append(
                AnalysisItem(
                    i * 1000000L,
                    i.toDouble(),
                    "$i",
                    "$i",
                    "phone",
                    i.toLong() + 1,
                    "location",
                    "phone",
                    "timestamp",
                    mapOf("courseDegrees" to Scalar.Real64(if (i % 2 == 0) 350.0 else 10.0)),
                )
            )
        }
        assertFailsWith<InspectionCapacityException> { plot.result() }
    }

    @Test
    fun inheritedBucketPredecessorMatchesRawReduction() {
        val timeline = ActiveTimeline(32000000, emptyList())
        fun point(time: Long) =
            AnalysisItem(
                time,
                time / 1e6,
                "$time",
                "$time",
                "controller",
                time + 1000001,
                "telemetry",
                "controller",
                "2026-01-01T00:00:00Z",
                mapOf("humanPowerW" to Scalar.Real64(100.0)),
            )
        val before = point(-1000000)
        val first =
            InspectionQuery("humanPowerW", AnalysisRange(0, 16000000), timeline, bucket = true, predecessor = before)
        listOf(point(0), point(15000000)).forEach(first::append)
        val second =
            InspectionQuery(
                "humanPowerW",
                AnalysisRange(16000000, 32000000),
                timeline,
                bucket = true,
                predecessor = point(15000000),
            )
        listOf(point(16000000), point(31000000)).forEach(second::append)
        val combined = InspectionQuery("humanPowerW", AnalysisRange(0, 32000000), timeline, bucket = true)
        combined.inheritPredecessor(first.checkpoint()!!)
        combined.mergeOwned(first.checkpoint()!!)
        combined.mergeOwned(second.checkpoint()!!)
        val raw =
            InspectionQuery("humanPowerW", AnalysisRange(0, 32000000), timeline, bucket = true, predecessor = before)
        listOf(point(0), point(15000000), point(16000000), point(31000000)).forEach(raw::append)
        assertEquals(raw.checkpoint(), combined.checkpoint())
    }

    @Test
    fun packetReferencesAndDistanceRenderingUseCoreEvidence() {
        val ticket = InspectionTicket(InspectionContext("source", "revision", "auto", 1, 2), 1)
        assertTrue(InspectionAcceptance.acceptsSelection(ticket, ticket, 1000000, 1000000))
        assertFalse(InspectionAcceptance.acceptsSelection(ticket, ticket, 1000000, 2000000))
        fun point(time: Long, clock: String = "clock") =
            InspectionPoint(
                time,
                Original("$time", "$time", "", time / 1e6, Scalar.Real64(0.0)),
                Continuity(clockEpoch = clock),
            )
        assertEquals(7, DistanceInspection.plotSegment(7, point(1000000), point(3000000)))
        assertEquals(8, DistanceInspection.plotSegment(7, point(1000000), point(7000000)))
        assertEquals(8, DistanceInspection.plotSegment(7, point(1000000), point(3000000, "next")))
    }
}
