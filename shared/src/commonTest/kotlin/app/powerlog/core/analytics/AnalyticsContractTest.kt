package app.powerlog.core.analytics

import app.powerlog.core.health.*
import kotlin.test.*

class AnalyticsContractTest {
    @Test
    fun analyticsControllerSingleRounding() {
        val base = controller(case("controller-counter-equal")["input"]["projectedControllerEvidence"].list().first())
        fun edge(endUs: Long, speed: Double): DistanceInterval {
            val kernel = ControllerDistanceKernel()
            assertNull(kernel.append(base.copy(timeUs = 0, speedMps = 0.0)))
            return requireNotNull(kernel.append(base.copy(timeUs = endUs, speedMps = speed, monotonicSeconds = null)))
        }
        assertEquals(117189L, edge(125001, 1.875001).distanceQ)
        val clipped = DistanceReducer(AnalysisRange(0, 125001))
        clipped.append(edge(250002, 3.750002))
        assertEquals(117189L, clipped.result().distanceQ)
        assertEquals(125001L, clipped.result().coveredUs)
    }

    @Test
    fun analyticsBucketCheckpointQueries() {
        val policy = Metrics.policy("humanPowerW")
        val query = AnalysisRange(0, 48000000)
        for (boundary in listOf("valid", "invalid", "paused", "epoch")) {
            val rows =
                (0..384).map { index ->
                    val time = index * 125000L
                    MetricReading(
                        time,
                        if (index == 128 && boundary == "invalid") null
                        else
                            Original(
                                "$index",
                                "$index",
                                "synthetic",
                                FixedPoint.divide(time, 1000000),
                                Scalar.Real64(100.1 + index % 7 * 0.0625),
                            ),
                        if (index == 128 && boundary == "paused") null else 0,
                        Continuity(clockEpoch = if (boundary == "epoch" && index >= 128) "new" else "old"),
                    )
                }
            val raw = MetricReducer(policy, query)
            rows.forEach(raw::append)
            var predecessor: MetricAdmission? = null
            val persisted =
                (0..2).map { index ->
                    val bucket =
                        MetricReducer(
                            policy,
                            AnalysisRange(index * 16000000L, (index + 1) * 16000000L),
                            predecessor,
                            bucket = true,
                        )
                    rows.subList(index * 128, (index + 1) * 128).forEach(bucket::append)
                    val saved = requireNotNull(bucket.checkpoint())
                    val restored = MetricReducer(policy, saved.range, checkpoint = saved, bucket = true)
                    requireNotNull(restored.checkpoint()).also { predecessor = it.previous }
                }
            val merged = MetricReducer(policy, query)
            persisted.forEach(merged::mergeOwned)
            merged.append(rows.last())
            assertEquals(raw.result(), merged.result(), boundary)
            val mixed = MetricReducer(policy, query)
            rows.take(128).forEach(mixed::append)
            mixed.mergeOwned(persisted[1])
            rows.drop(256).forEach(mixed::append)
            assertEquals(raw.result(), mixed.result(), "$boundary/raw-boundaries")
            val clippedQuery = MetricReducer(policy, AnalysisRange(15900000, 48000000))
            assertFailsWith<IllegalArgumentException> { clippedQuery.mergeOwned(persisted[1]) }
        }
    }

    private fun syntheticHealthPin(endSeconds: Double = 2.0, watch: Boolean = false): AnalysisPin {
        val original = RideFixture(reference("moving-gps-gap")["input"]).pin
        val metadata =
            original.metadata.copy(
                elapsedSeconds = endSeconds,
                stopElapsedSeconds = endSeconds,
                watchEnabled = watch,
                recordGPS = false,
                completeEncodedMetadata = "synthetic-review-health",
            )
        return AnalysisPin(metadata.id, 1, metadata, "auto")
    }

    @Test
    fun analyticsOriginRepresentationPrecedence() {
        val pin = syntheticHealthPin()
        val selection =
            HealthSelection("heartRateBpm", "phone", "app.a", 2000000, emptyList(), Association.RideWindow(0, 2000000))
        val rows =
            listOf(0L, 2000000L).flatMap { time ->
                listOf("app.a", "app.b").map { packageName ->
                    val id = "$packageName:$time"
                    AnalysisItem(
                        time,
                        FixedPoint.divide(time, 1000000),
                        id,
                        id,
                        "phone",
                        time + 1,
                        "health",
                        "phone",
                        "synthetic",
                        mapOf("heartRateBpm" to Scalar.Real64(if (packageName == "app.a") 100.0 else 200.0)),
                        representation = if (packageName == "app.a") "builderSnapshot" else "rawQuantity",
                        origin = Origin("healthConnect", packageName, id, id, "phone", time + 1),
                        declaredMetrics = listOf("heartRateBpm"),
                    )
                }
            }
        val preparation = SummaryPreparation(pin)
        rows.forEach(preparation::append)
        val prepared = preparation.finish()
        assertTrue(prepared.heartRateEligible(rows[0], "app.a"))
        assertTrue(prepared.heartRateEligible(rows[1], "app.b"))
        assertFalse(prepared.heartRateEligible(rows[0].copy(origin = rows[1].origin), "app.b"))
        assertFalse(prepared.heartRateEligible(rows[0], null))
        val composer =
            SummaryComposer(preparation.finish(), selectDistance("auto", emptyList(), false, false), listOf(selection))
        rows.forEach(composer::append)
        val result = (composer.finish() as SummaryOutcome.Complete).summary
        assertEquals(100.0, result.averageHeartRateBpm)
        assertEquals(100.0, result.maximumHeartRateBpm)
        assertEquals(2.0, result.heartRateCoveredSeconds)
    }

    @Test
    fun analyticsEnergyTimeGuard() {
        for (outside in listOf(-1000001L, 2678400000001L)) {
            val pin = syntheticHealthPin(1.0, true)
            val origin = Origin("appleHealth", null, "energy", "energy", "phone", 1)
            val interval =
                EnergyEvidence.IntervalRecord(
                    "active",
                    EnergyInterval("phone", origin, 1, 0, 1000000, "synthetic", "synthetic", 100.0),
                )
            val excluded =
                EnergyEvidence.FinalWorkoutTotal(
                    "active",
                    EnergyPoint(
                        "phone",
                        origin.copy(originEventId = "excluded"),
                        1,
                        outside,
                        "synthetic",
                        1.0,
                        Association.RideWindow(0, 1000000),
                    ),
                )
            val excludedWatch =
                excluded.copy(
                    point =
                        excluded.point.copy(
                            source = app.powerlog.core.health.Source.watch,
                            origin =
                                origin.copy(
                                    producer = app.powerlog.core.health.Source.watch,
                                    originEventId = "excluded-watch",
                                ),
                        )
                )
            val projections =
                listOf(
                    AnalysisProjection.Energy(interval, "interval", 1000000),
                    AnalysisProjection.Energy(excluded, "excluded", outside),
                    AnalysisProjection.Energy(excludedWatch, "excluded-watch", outside),
                )
            val preparation = SummaryPreparation(pin)
            projections.forEach(preparation::append)
            val prepared = preparation.finish()
            assertEquals("phone", prepared.healthSources["active"])
            assertEquals(3L, prepared.eventCount)
            val composer = SummaryComposer(prepared, selectDistance("auto", emptyList(), true, false))
            projections.forEach(composer::append)
            val result = (composer.finish() as SummaryOutcome.Complete).summary
            assertEquals(100.0, result.activeEnergyKcal)
            assertEquals(3L, result.healthCount)
        }
    }

    @Test
    fun analyticsEnergyIntervalsUseEndCollectionTime() {
        val pin = syntheticHealthPin(10.0, true)
        val origin = Origin("healthConnect", "app.generated", "energy", "energy", "phone", 1)
        val projections =
            listOf(Triple(-2000000L, 2000000L, 4.0), Triple(8000000L, 12000000L, 6.0)).mapIndexed {
                index,
                (start, end, kcal) ->
                AnalysisProjection.Energy(
                    EnergyEvidence.IntervalRecord(
                        "active",
                        EnergyInterval(
                            "phone",
                            origin.copy(originEventId = "$index"),
                            1,
                            start,
                            end,
                            "synthetic",
                            "synthetic",
                            kcal,
                        ),
                    ),
                    "$index",
                    start,
                )
            }
        assertEquals(listOf(2000000L, 12000000L), projections.map { it.evidence.collectionUs })
        val preparation = SummaryPreparation(pin)
        projections.forEach(preparation::append)
        val composer = SummaryComposer(preparation.finish(), selectDistance("auto", emptyList(), true, false))
        projections.forEach(composer::append)
        assertEquals(5.0, (composer.finish() as SummaryOutcome.Complete).summary.activeEnergyKcal)
    }

    @Test
    fun analyticsDeferredOverflowOrdering() {
        val pin = syntheticHealthPin()
        val origin = Origin("appleHealth", null, "energy", "energy", "phone", 1)
        val projections =
            (0..1).map { index ->
                AnalysisProjection.Energy(
                    EnergyEvidence.IntervalRecord(
                        "active",
                        EnergyInterval(
                            "phone",
                            origin.copy(originEventId = "$index"),
                            1,
                            index * 1000000L,
                            (index + 1) * 1000000L,
                            "synthetic",
                            "synthetic",
                            5e12,
                        ),
                    ),
                    "$index",
                    (index + 1) * 1000000L,
                )
            }
        val preparation = SummaryPreparation(pin)
        projections.forEach(preparation::append)
        val composer =
            SummaryComposer(
                preparation.finish(),
                selectDistance("auto", emptyList(), false, false),
            )
        projections.forEach(composer::append)
        assertEquals(
            NumericOverflow("activeEnergyKcal", Quantity.energyQ),
            (composer.finish() as SummaryOutcome.Overflow).failure,
        )
    }

    @Test
    fun analyticsPreviewElapsedTail() {
        val many = RideFixture(reference("route-preview-750")["input"])
        val points = many.items.filter { it.kind == "location" }
        val anchor = points[747]
        for (differentOriginalTime in listOf(false, true)) {
            val composer = SummaryComposer(many.prepared, many.distance)
            points.forEachIndexed { index, point ->
                composer.append(
                    if (index < 747) point
                    else
                        point.copy(
                            collectionUs = anchor.collectionUs,
                            collectionElapsedSeconds =
                                anchor.collectionElapsedSeconds +
                                    if (differentOriginalTime) (index - 747) * 0.0000001 else 0.0,
                        )
                )
            }
            val preview = (composer.finish() as SummaryOutcome.Complete).summary.routePreview
            assertEquals(if (differentOriginalTime) 251 else 250, preview.size)
            assertEquals(
                anchor.collectionElapsedSeconds + if (differentOriginalTime) 0.0000002 else 0.0,
                preview.last().elapsedSeconds,
            )
        }
    }

    private val reference by lazy { fixture("analytics/iphone-reference.json")["cases"].list() }
    private val deltas by lazy { fixture("analytics/approved-deltas.json")["cases"].list() }
    private val review by lazy { fixture("analytics/review-required.json")["cases"].list() }

    private fun delta(id: String): J = deltas.first { it["testId"].text() == id }

    private fun case(id: String): J = review.first { it["testId"].text() == id }

    private fun reference(id: String): J = reference.first { it["name"].text() == id }

    private fun approvedSummary(c: J): J {
        val name = c["name"].text()
        val labelled = overlay(c["expectedSummary"], delta("source-labels")["expectedApproved"]["summaries"][name])
        return if (name == "moving-gps-gap") overlay(labelled, case("gps-fixed-point-summary")["expectedApproved"])
        else labelled
    }

    @Test
    fun summaryCacheIdentityRetainsHealthMetadata() {
        val ride = RideFixture(reference("final-health-received")["input"])
        val pin = ride.pin
        for (metadata in
            listOf(
                pin.metadata.copy(phase = "finishing"),
                pin.metadata.copy(healthOutcome = HealthOutcome.unavailable),
                pin.metadata.copy(healthReadbackPending = false),
            )) {
            assertFalse(CacheIdentity.mayPublish(pin, pin.copy(metadata = metadata)))
        }
    }

    @Test
    fun analyticsReferenceFixtures() {
        for (c in reference) {
            val ride = RideFixture(c["input"])
            assertJson(approvedSummary(c), summaryJson(ride.summary), c["name"].text())
            c["expectedAbsentSummaryFields"].list().forEach {
                assertEquals(J.Null, summaryJson(ride.summary)[it.text()], "${c["name"].text()}/${it.text()}")
            }
            if (c["expectedCurrentIphoneStatistics"] != J.Null)
                assertJson(
                    delta("chart-reference-targets")["expectedApproved"][c["name"].text()],
                    ride.statistics(),
                    c["name"].text() + "/statistics",
                )
        }
    }

    @Test
    fun analyticsCatalogFixtures() {
        val catalog = fixture("analytics/visible-catalog-page.json")
        val rides = catalog["input"]["rides"].list().associateBy { it["input"]["create"]["id"].text() }
        val overrides = delta("visible-catalog-approved-wording")["expectedApproved"]
        for ((index, row) in catalog["expected"]["visible"].list().withIndex()) {
            val id = row["id"].text()
            val summary = RideFixture(rides.getValue(id)["input"]).summary
            val expected =
                overlay(
                    row["summary"],
                    obj(
                        "provenance" to
                            obj(
                                "speed" to overrides["/visible/$index/summary/provenance/speed"],
                                "rawData" to overrides["/visible/$index/summary/provenance/rawData"],
                            )
                    ),
                )
            assertJson(expected, summaryJson(summary), "catalog/$id")
            if (row["projection"] != J.Null)
                assertJson(
                    row["projection"],
                    obj(
                        "distanceMeters" to summary.distanceMeters,
                        "averageRiderPowerW" to summary.averageRiderPowerW,
                        "riderWorkJoules" to summary.riderWorkJoules,
                    ),
                    "catalog/$id/projection",
                )
        }
        assertTrue(overrides != J.Null)
    }

    @Test
    fun analyticsApprovedDeltaFixtures() {
        val zero = delta("chart-zero-width-end")
        assertJson(
            zero["expectedApproved"]["zeroWidth"],
            RideFixture(zero["input"]["zeroWidthRide"]).statistics(),
            "zeroWidth",
        )
        for (id in listOf("all-channel-barriers")) {
            val c = delta(id)
            for ((name, input) in c["input"]["rides"].fields()) assertJson(
                c["expectedApproved"][name],
                RideFixture(input).statistics(),
                "$id/$name",
            )
        }
        val time = delta("time-microsecond-boundaries")
        time["input"]["normalizationSeconds"].list().zip(time["expectedApproved"]["normalizationUs"].list()).forEach {
            (n, expected) ->
            assertEquals(expected.long(), FixedPoint.timeUs(n.double()))
        }
        for ((name, input) in time["input"]["rides"].fields()) {
            val s = RideFixture(input).summary
            val e = time["expectedApproved"]["rides"][name]
            val hr = name.startsWith("hr-")
            assertEquals(
                e["coveredSeconds"].double(),
                if (hr) s.heartRateCoveredSeconds else s.telemetryCoveredSeconds,
                name,
            )
            assertEquals(e["average"].optionalDouble(), if (hr) s.averageHeartRateBpm else s.averageRiderPowerW, name)
            assertEquals(e["riderWorkJoules"].optionalDouble(), s.riderWorkJoules, name)
            assertEquals(
                e["completeness"].text(),
                if (hr) s.completeness.heartRate else s.completeness.riderPower,
                name,
            )
        }
        val speed = delta("speed-forty-boundary")
        for ((name, input) in speed["input"]["rides"].fields()) assertEquals(
            speed["expectedApproved"]["maximumSpeedMps"][name].optionalDouble(),
            RideFixture(input).summary.maximumSpeedMps,
            name,
        )
        speed["input"]["distanceEdges"].list().zip(speed["expectedApproved"]["acceptedDistanceEdges"].list()).forEach {
            (edge, expected) ->
            assertEquals(expected.bool(), DistancePolicy.edge(edge["dtUs"].long(), edge["chordMeters"].double()))
        }
        val labels = delta("source-labels")
        val expectedLabels = labels["expectedApproved"]["wording"]
        for ((index, input) in labels["input"]["wordingCases"].list().withIndex()) {
            val d = input["descriptor"]
            val descriptors =
                listOf(MeasurementSource(d["source"].text(), d["device"].text(), d["provider"].optionalText()))
            val label =
                if (input["channel"].text() == "gps") DistancePolicy.label("gps:${d["source"].text()}", descriptors)
                else ProviderNames.label(d["source"].text(), descriptors)
            if (expectedLabels is J.Arr) assertEquals(expectedLabels.values[index].text(), label)
        }
        val imported = delta("imported-work-availability")
        for ((name, samples) in imported["input"]["samplesByCase"].fields()) {
            val reducer = MetricReducer(Metrics.policy("humanPowerW"), AnalysisRange(0, 1000000))
            samples.list().forEachIndexed { index, sample ->
                val time = sample["elapsedSeconds"].double()
                reducer.append(
                    MetricReading(
                        FixedPoint.timeUs(time),
                        Original(
                            "sample:$index",
                            "sample:$index",
                            sample["timestamp"].text(),
                            time,
                            scalar(sample["humanPowerW"]),
                        ),
                        0,
                        Continuity(),
                    )
                )
            }
            assertEquals(
                imported["expectedApproved"][name]["humanEnergyWh"].optionalDouble(),
                riderWorkWh(reducer.result()),
                name,
            )
        }
    }

    @Test
    fun analyticsFixedPointFixtures() {
        val admission = case("admission-quantization")
        admission["input"]["readings"].list().zip(admission["expectedApproved"]["quantization"].list()).forEach {
            (reading, expected) ->
            val o = reading["original"]
            val value =
                if (o["representation"].text() == "integer64") Scalar.Integer64(o["value"].long())
                else Scalar.Real64(o["value"].double())
            val q = Metrics.policy(reading["metric"].text()).admit(value)
            assertEquals(expected["valueQ"].optionalText()?.toLong(), q, reading.toString())
        }
        admission["input"]["intervalShares"]
            .list()
            .zip(admission["expectedApproved"]["intervalShares"].list())
            .forEach { (input, expected) ->
                val q = FixedPoint.quantize(input["original"].double(), input["scale"].long())
                assertEquals(expected["admittedValueQ"].long(), q)
                val bounds = input["boundsUs"].list().map { it.long() }
                val clip = input["clipUs"].list().map { it.long() }
                assertEquals(expected["valueQ"].long(), FixedPoint.share(q, clip[1] - clip[0], bounds[1] - bounds[0]))
            }
        val admittedRide = RideFixture(admission["input"]["archiveJob"]["input"])
        assertJson(admission["expectedApproved"]["statistics"], admittedRide.statistics(), "admission")
        assertJson(
            admission["expectedApproved"]["summary"],
            summaryJson(admittedRide.summary),
            "admission/summary",
            partial = true,
        )
        val clipped = case("clipped-rational-half-even")
        clipped["input"]["archiveJobs"].list().forEachIndexed { index, job ->
            val expected = clipped["expectedApproved"][if (index == 0) "trapezoid" else "endpoint"]
            for (limit in listOf(1, 2, 256)) {
                val ride = RideFixture(job["input"], pageLimit = limit)
                assertJson(expected["statistics"], ride.statistics(), "clip/$index/$limit")
                assertJson(expected["summary"], summaryJson(ride.summary), "clip/summary/$index/$limit", partial = true)
            }
            for (reduction in expected["reductions"].list()) {
                val events = job["input"]["events"].list()
                val q0 = FixedPoint.quantize(events[0]["payload"]["humanPowerW"].double(), 1000)
                val q1 = FixedPoint.quantize(events[1]["payload"]["humanPowerW"].double(), 1000)
                val duration = FixedPoint.timeUs(events[1]["elapsedSeconds"].double())
                val endpoints = reduction["clipUs"].list().map { FixedPoint.interpolate(q0, q1, it.long(), duration) }
                assertEquals(reduction["endpointsQ"].list().map { it.long() }, endpoints)
                assertEquals(
                    reduction["integralQUs"].long(),
                    FixedPoint.trapezoid(endpoints[0], endpoints[1], reduction["coveredUs"].long()),
                )
            }
        }
        val output = case("int64-output-rounding")
        output["input"]["integers"].list().zip(output["expectedApproved"]["conversions"].list()).forEach {
            (input, expected) ->
            assertEquals(
                expected["bitsHex"].text(),
                input.long().toDouble().toBits().toULong().toString(16).padStart(16, '0'),
            )
        }
        val division = output["input"]["division"]
        assertEquals(
            output["expectedApproved"]["division"]["bitsHex"].text(),
            FixedPoint.divide(division["numerator"].long(), division["denominator"].long())
                .toBits()
                .toULong()
                .toString(16),
        )
        val overflow = case("integer-overflow")
        for ((_, partition) in overflow["input"]["partitions"].fields()) {
            val terms = overflow["input"]["originalSignedTermsQ"].list().map { it.long() }
            var offset = 0
            var sum = SignedSum()
            partition.list().forEach { size ->
                val partial =
                    terms.subList(offset, offset + size.long().toInt()).fold(SignedSum()) { state, value ->
                        state.add(value)
                    }
                sum = sum.merge(partial)
                offset += size.long().toInt()
            }
            assertTrue(sum.positiveOverflow && sum.negativeOverflow)
            val error = assertFailsWith<NumericFailure> { sum.value("syntheticSignedMetric", Quantity.sumQ) }
            assertEquals(NumericOverflow("syntheticSignedMetric", Quantity.sumQ), error.failure)
        }
        assertEquals(1L, FixedPoint.quantize(0.0005, 1000))
        assertEquals(3L, FixedPoint.quantize(0.0025, 1000))
        assertEquals(-3L, FixedPoint.quantize(-0.0025, 1000))
        assertEquals(Long.MAX_VALUE - 1, FixedPoint.share(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { FixedPoint.timeUs(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FixedPoint.timeUs(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FixedPoint.timeUs(9223372036854775808.0 / 1000000) }
        assertEquals(0L, FixedPoint.halfEven(1, 2))
        assertEquals(-2L, FixedPoint.halfEven(-3, 2))
        assertEquals(0L, FixedPoint.interpolate(-1, 0, 1, 2))
        assertEquals(Long.MAX_VALUE, FixedPoint.share(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, FixedPoint.quantize(Scalar.Integer64(Long.MIN_VALUE), 1))
        assertFailsWith<ArithmeticException> { FixedPoint.quantize(Scalar.Integer64(Long.MAX_VALUE), 1000) }
    }

    @Test
    fun analyticsDistanceFixtures() {
        for (c in review.filter { it["testId"].text() in listOf("controller-valid", "explicit-unavailable") }) {
            val input = c["input"]
            val expected = c["expectedApproved"]
            val name = c["testId"].text()
            for (limit in listOf(1, 2, 256)) {
                val ride = RideFixture(input["archiveJob"]["input"], pageLimit = limit)
                assertJson(expected["distance"], distanceJson(ride.distance), "$name/distance/$limit")
                assertJson(expected["statistics"], ride.statistics(), "$name/statistics/$limit")
                assertEquals(expected["distanceMeters"].optionalDouble(), ride.summary.distanceMeters, name)
                val kernel = ControllerDistanceKernel()
                val emitted =
                    input["projectedControllerEvidence"].list().map(::controller).chunked(limit).flatMap { page ->
                        page.mapNotNull(kernel::append)
                    }
                if (expected["emittedIntervalsUsMetersSegment"] != J.Null)
                    assertJson(
                        expected["emittedIntervalsUsMetersSegment"],
                        j(
                            emitted.map {
                                listOf(it.startUs, it.endUs, FixedPoint.divide(it.distanceQ, 1000000), it.segment)
                            }
                        ),
                        "$name/emitted/$limit",
                    )
            }
        }
        val equal = case("controller-counter-equal")
        for (limit in listOf(1, 2)) {
            val kernel = ControllerDistanceKernel()
            val result =
                equal["input"]["projectedControllerEvidence"]
                    .list()
                    .map(::controller)
                    .chunked(limit)
                    .flatMap { it.mapNotNull(kernel::append) }
                    .single()
            assertEquals(equal["expectedApproved"]["distanceQ"].long(), result.distanceQ)
            assertEquals(equal["expectedApproved"]["coveredUs"].long(), result.endUs - result.startUs)
        }
        val partial = case("distance-partial-microsecond")
        partial["input"]["coveredUs"].list().forEachIndexed { index, covered ->
            val uncovered = uncoveredUs(partial["input"]["timerUs"].long(), covered.long())
            assertEquals(partial["expectedApproved"]["uncoveredUs"].list()[index].long(), uncovered)
            val profile =
                requireNotNull(
                    distanceProfile(
                        "controller",
                        DistanceRange(0, covered.long(), false, false),
                        partial["input"]["timerUs"].long(),
                        emptyList(),
                    )
                )
            assertEquals(partial["expectedApproved"]["partial"].list()[index].bool(), profile.partial)
        }
        val gps = case("gps-fixed-point-summary")
        for (limit in listOf(1, 2, 256)) assertJson(
            gps["expectedApproved"],
            summaryJson(
                RideFixture(reference(gps["input"]["referenceCase"].text())["input"], pageLimit = limit).summary
            ),
            "GPS/$limit",
            partial = true,
        )
    }

    @Test
    fun analyticsTimingAndPageFixtures() {
        for (id in
            listOf(
                "pause-stop-coincident",
                "paused-final-endpoint",
                "checkpoint-valid",
                "checkpoint-paused",
                "checkpoint-changed-epoch",
            )) {
            val c = case(id)
            for (limit in listOf(1, 2, 256)) {
                val ride = RideFixture(c["input"]["archiveJob"]["input"], pageLimit = limit)
                assertJson(
                    c["expectedApproved"]["summary"],
                    summaryJson(ride.summary),
                    "$id/summary/$limit",
                    partial = true,
                )
                assertJson(c["expectedApproved"]["statistics"], ride.statistics(), "$id/statistics/$limit")
                if (c["expectedApproved"]["accumulators"] != J.Null) {
                    val reducer =
                        MetricReducer(Metrics.policy("humanPowerW"), AnalysisRange(0, ride.prepared.actualEndUs))
                    ride.items.forEach {
                        if (it.kind == "telemetry")
                            reducer.append(
                                MetricReading(
                                    it.collectionUs,
                                    it.original("humanPowerW"),
                                    ride.prepared.timeline.membership(it),
                                    it.continuity,
                                )
                            )
                    }
                    val result = reducer.result()
                    assertEquals(c["expectedApproved"]["accumulators"]["coveredUs"].long(), result.coveredUs)
                    assertEquals(c["expectedApproved"]["accumulators"]["integralQUs"].long(), result.integralQUs)
                }
            }
        }
        val recovery = case("recovery-interruption")
        val job = recovery["input"]["archiveJob"]
        val committed = recovery["expectedApproved"]["committedInterruptions"].list().single()
        val input =
            overlay(
                job["input"],
                obj("events" to (job["input"]["events"].list() + committed + job["afterInterruptEvents"].list())),
            )
        val ride = RideFixture(input, pageLimit = 1)
        assertJson(
            recovery["expectedApproved"]["summary"],
            summaryJson(ride.summary),
            "interruption/summary",
            partial = true,
        )
        assertJson(recovery["expectedApproved"]["statistics"], ride.statistics(), "interruption/statistics")
        val boundary = ride.prepared.timeline.interruptions.single()
        assertEquals(recovery["expectedApproved"]["normalizedBoundary"]["cutoffUs"].long(), boundary.cutoffUs)
        for (membership in recovery["input"]["equalTimeMembership"].list()) {
            val item =
                ride.items
                    .first { it.kind == "telemetry" }
                    .copy(
                        collectionUs = boundary.cutoffUs,
                        producer = membership["producer"].text(),
                        sequence = membership["sequence"].long(),
                        continuity = Continuity(membership["clockEpoch"].optionalText()),
                    )
            assertEquals(membership["expected"].text() == "closing", boundary.closing(item))
        }
        val equal = case("equal-time-continuation")
        for (limit in equal["input"]["pageLimits"].list().map { it.long().toInt() }) {
            val r = RideFixture(reference(equal["input"]["referenceCase"].text())["input"], pageLimit = limit)
            assertEquals(equal["expectedApproved"]["workJoules"].double(), r.summary.riderWorkJoules)
            assertEquals(
                equal["expectedApproved"]["count"].long(),
                r.statistics().list().first()["humanPowerW"]["count"].long(),
            )
            assertEquals(
                equal["expectedApproved"]["extremumObservationId"].text(),
                r.statistics().list().first()["humanPowerW"]["min"]["observationId"].text(),
            )
            assertEquals(equal["input"]["orderedEventIds"].list().map { it.text() }, r.items.map { it.eventId })
        }
        val overfull = SummaryPreparation(ride.pin)
        repeat(10001) { index ->
            overfull.append(
                ride.items
                    .first()
                    .copy(
                        kind = "lifecycle",
                        action = "lap",
                        collectionUs = 0,
                        observationId = "life:$index",
                        eventId = "life:$index",
                        sequence = index.toLong() + 1,
                    )
            )
        }
        assertTrue(overfull.finishOutcome() is PreparationOutcome.InvalidEvidence)
        val pin = ride.pin
        val identity = CacheIdentity.of(pin)
        val request = AnalysisPageRequest(pin, AnalysisPass.compose, AnalysisStream.observations, "cancel-4", limit = 1)
        val cursor = AnalysisCursor(identity, AnalysisPass.compose, "after-original-1")
        val page = AnalysisPage(pin, listOf(AnalysisProjection.Observation(ride.items.first())), cursor, false, 256)
        assertTrue(page.validFor(request))
        assertFalse(page.validFor(request.copy(cursor = cursor)))
        assertFalse(page.validFor(request.copy(pin = pin.copy(observationRevision = pin.observationRevision + 1))))
        assertFailsWith<IllegalArgumentException> { request.copy(limit = 257) }
        assertFailsWith<IllegalArgumentException> { page.copy(serializedBytes = 524289) }
        val cancel = case("cancel-drain-cache")
        assertEquals("after-original-1", cancel["input"]["page1"]["nextCursor"].text())
        val composer = SummaryComposer(ride.prepared, ride.distance)
        val session = AnalysisPageSession(pin, AnalysisPass.compose, AnalysisStream.observations, "cancel-4", limit = 1)
        assertNotNull(session.request())
        session.cancel()
        composer.cancel()
        assertTrue(session.pinHeld && session.slotHeld && session.uiSettled)
        assertEquals(PageAdmission.Cancelled, session.admit(PageOutcome.Page(page)))
        assertNull(session.request())
        composer.append(ride.items.first())
        assertEquals(SummaryOutcome.Cancelled, composer.finish())
        assertFalse(session.mayPublish(pin))
        session.drain()
        assertFalse(session.pinHeld || session.slotHeld)
        val failedSession = AnalysisPageSession(pin, AnalysisPass.prepare, AnalysisStream.observations, "error")
        assertNotNull(failedSession.request())
        assertEquals(PageAdmission.Rejected(PageOutcome.Deleted), failedSession.admit(PageOutcome.Deleted))
        assertFalse(failedSession.mayPublish(pin))
        val empty = case("empty-rebuild")
        val reduced =
            MetricReducer(
                    Metrics.policy("humanPowerW"),
                    AnalysisRange(
                        empty["input"]["bucketStartUs"].long(),
                        empty["input"]["bucketStartUs"].long() + 16000000,
                    ),
                )
                .result()
        assertEquals(0L, reduced.statistics.count)
        assertEquals(null, reduced.statistics.min)
        assertEquals(null, reduced.statistics.max)
        assertEquals(null, reduced.statistics.sampleMean)
        assertEquals(0L, reduced.integralQUs)
        assertEquals(0L, reduced.coveredUs)
    }

    @Test
    fun analyticsFractionalPartitions() {
        for (id in listOf("fractional-partition-100.1", "fractional-partition-0.1")) {
            val c = case(id)
            val input = c["input"]["reading"]
            val expected = c["expectedApproved"]
            val metric = input["metric"].text()
            val count = input["count"].long().toInt()
            val step = input["stepUs"].long()
            val value = Scalar.Real64(input["value"].double())
            for ((name, partition) in c["input"]["partitions"].fields()) {
                val range = AnalysisRange(0, count * step)
                val reducer = MetricReducer(Metrics.policy(metric), range)
                var predecessor: MetricAdmission? = null
                var index = 0
                for (size in partition.list()) {
                    val start = input["startUs"].long() + index * step
                    val bucketRange = AnalysisRange(start, start + size.long() * step)
                    val page = MetricReducer(Metrics.policy(metric), bucketRange, predecessor, bucket = true)
                    repeat(size.long().toInt()) {
                        val time = input["startUs"].long() + index * step
                        page.append(
                            MetricReading(
                                time,
                                Original("$index", "$index", "synthetic", FixedPoint.divide(time, 1000000), value),
                                0,
                                Continuity(),
                            )
                        )
                        index++
                    }
                    val checkpoint = requireNotNull(page.checkpoint())
                    reducer.mergeOwned(checkpoint)
                    predecessor = checkpoint.previous
                }
                assertEquals(count, index)
                val result = reducer.result()
                assertEquals(expected["sumQ"].long(), result.sumQ, "$id/$name/sum")
                assertEquals(expected["integralQUs"].long(), result.integralQUs, "$id/$name/integral")
                assertEquals(expected["coveredUs"].long(), result.coveredUs, "$id/$name/covered")
                assertEquals(
                    expected["sequentialSampleMean"].double(),
                    result.statistics.sampleMean,
                    "$id/$name/sampleMean",
                )
                assertEquals(expected["weightedMean"].double(), result.average, "$id/$name/weighted")
                assertEquals(expected["integral"].double(), result.statistics.integral, "$id/$name/work")
                assertEquals(expected["count"].long(), result.statistics.count)
                assertEquals(expected["valueQ"].long(), Metrics.policy(metric).admit(value))
                assertEquals(
                    expected["edgeContributionQUs"].long(),
                    FixedPoint.trapezoid(expected["valueQ"].long(), expected["valueQ"].long(), step),
                )
            }
        }
    }

    @Test
    fun analyticsHealthEnergyFixtures() {
        val representations = delta("energy-representations")
        for ((name, values) in representations["input"]["energyEvidence"].fields()) {
            val records = values.list().map(::energy)
            val owner =
                representations["input"]["ownerByScenario"][name].optionalText()
                    ?: representations["input"]["ownerByScenario"]["default"].text()
            val available =
                records
                    .filter {
                        it.valueKcal.isFinite() &&
                            it.valueKcal >= 0 &&
                            (it !is EnergyEvidence.IntervalRecord || HealthOriginSelector.valid(it.interval))
                    }
                    .map { it.source.name }
                    .toSet()
            val source = listOf(owner, "phone", "watch").firstOrNull { it in available }
            val reducer = EnergyReducer("active", representations["input"]["actualEndUs"].long(), source)
            records.forEach(reducer::append)
            assertEquals(
                representations["expectedApproved"][name].optionalDouble(),
                reducer.valueQ()?.let { FixedPoint.divide(it, 1000000) },
                name,
            )
        }
    }

    @Test
    fun analyticsHealthOriginFixtures() {
        val shared = case("health-origin-selection-shared")
        val cases = fixture("health/readback.json")["cases"].list()
        for (name in shared["input"]["cases"].list().map { it.text() }) {
            val c = cases.first { it["name"].text() == name }
            val input = c["input"]
            val expected = c["expected"]
            val bounds = input["windowUs"].list().map { it.long() }
            val window = AnalysisRange(bounds[0], bounds[1])
            fun interval(value: J, index: Int): EnergyInterval {
                val o =
                    Origin(
                        "healthConnect",
                        value["dataOrigin"].text(),
                        value["providerId"].text(),
                        value["providerId"].text(),
                        "phone",
                        index.toLong() + 1,
                        value["nativeRevision"].text(),
                    )
                return EnergyInterval(
                    "phone",
                    o,
                    value["nativeRevision"].long(),
                    value["startUs"].long(),
                    value["endUs"].long(),
                    "synthetic",
                    "synthetic",
                    value["valueKcal"].double(),
                )
            }
            for (reverse in listOf(false, true)) {
                fun records(key: String): List<EnergyInterval> =
                    input[key]
                        .list()
                        .mapIndexed { index, value -> interval(value, index) }
                        .let { if (reverse) it.reversed() else it }
                if (input["parents"] != J.Null) {
                    val hr =
                        input["parents"]
                            .list()
                            .flatMapIndexed { parentIndex, parent ->
                                parent["members"].list().mapIndexed { memberIndex, member ->
                                    val t = member["offsetUs"].long()
                                    val o =
                                        Origin(
                                            "healthConnect",
                                            parent["dataOrigin"].text(),
                                            "parent:$parentIndex",
                                            "parent:$parentIndex",
                                            "phone",
                                            parentIndex.toLong() + 1,
                                        )
                                    HeartRateEvidence(
                                        o,
                                        "phone",
                                        Association.RideWindow(window.startUs, window.endUs),
                                        "synthetic",
                                        "synthetic",
                                        t,
                                        t,
                                        t,
                                        t,
                                        Continuity(),
                                        "rawSeries",
                                        "parent:$parentIndex:$memberIndex",
                                        parent["members"].list().size.toLong(),
                                        member["bpm"].optionalDouble(),
                                        "bpm",
                                        1,
                                    )
                                }
                            }
                            .let { if (reverse) it.reversed() else it }
                    val selection = requireNotNull(HealthOriginSelector.heartRate(hr, "phone", window))
                    assertEquals(expected["dataOrigin"].text(), selection.dataOrigin, name)
                    assertEquals(expected["coveredUs"].long(), selection.unionCoverageUs, name)
                    val reducer = MetricReducer(Metrics.policy("heartRateBpm"), window)
                    hr.filter { it.origin.dataOrigin == selection.dataOrigin }
                        .sortedBy { it.collectionUs }
                        .forEach { item ->
                            reducer.append(
                                MetricReading(
                                    item.collectionUs,
                                    item.valueBpm?.let {
                                        Original(
                                            item.memberIdentity.orEmpty(),
                                            item.origin.originEventId,
                                            "synthetic",
                                            FixedPoint.divide(item.collectionUs, 1000000),
                                            Scalar.Real64(it),
                                        )
                                    },
                                    0,
                                    item.continuity,
                                )
                            )
                        }
                    assertEquals(expected["averageBpm"].double(), reducer.result().average, name)
                    assertEquals(expected["maximumBpm"].double(), reducer.result().statistics.max?.value?.real(), name)
                } else {
                    val evidence = records("records")
                    val selected = requireNotNull(HealthOriginSelector.energy(evidence, "phone", "active", window, 2))
                    val latest = HealthOriginSelector.latestIntervals(evidence, 2)
                    val reducer = EnergyReducer("active", window.endUs, "phone", selected.dataOrigin)
                    latest.forEach { reducer.append(EnergyEvidence.IntervalRecord("active", it)) }
                    val value = FixedPoint.divide(requireNotNull(reducer.valueQ()), 1000000)
                    assertEquals(expected["valueKcal"].optionalDouble() ?: expected["activeKcal"].double(), value, name)
                    if (expected["dataOrigin"] != J.Null)
                        assertEquals(expected["dataOrigin"].text(), selected.dataOrigin, name)
                    if (expected["coverageUs"] != J.Null)
                        assertEquals(expected["coverageUs"].long(), selected.unionCoverageUs, name)
                }
            }
        }
    }

    @Test
    fun analyticsCacheRevisionFixtures() {
        for (id in
            listOf(
                "append-between-passes",
                "correction-between-pages",
                "deletion-between-pages",
                "metadata-only-finality",
                "rollback",
            )) {
            val c = case(id)
            val job = c["input"]["archiveJob"]
            val expected = c["expectedApproved"]
            val pinned = RideFixture(job["input"], expected["initialRevision"].long(), pageLimit = 1)
            assertJson(expected["initial"], summaryJson(pinned.summary), "$id/initial", partial = true)
            var latestInput = job["input"]
            job["mutations"].list().forEachIndexed { index, mutation ->
                val event = mutation["event"]
                if (event != J.Null) {
                    val supersedes = event["payload"]["supersedesEventId"].optionalText()
                    val deleted =
                        if (event["payload"]["deleted"] == J.Bool(true)) event["payload"]["sampleUUID"].optionalText()
                        else null
                    val selectedEvents =
                        latestInput["events"].list().filter {
                            it["eventId"].optionalText() != supersedes &&
                                (deleted == null || it["payload"]["sampleUUID"].optionalText() != deleted)
                        }
                    latestInput = overlay(latestInput, obj("events" to (selectedEvents + event)))
                }
                if (mutation["metadataUpdate"] != J.Null)
                    latestInput =
                        overlay(
                            latestInput,
                            obj("metadataUpdate" to overlay(latestInput["metadataUpdate"], mutation["metadataUpdate"])),
                        )
                val state = expected["states"].list()[index]
                val latest = RideFixture(latestInput, state["revision"].long(), pageLimit = 1)
                assertJson(state["pinned"], summaryJson(pinned.summary), "$id/pinned", partial = true)
                assertJson(state["latest"], summaryJson(latest.summary), "$id/latest", partial = true)
                if (id != "rollback") assertFalse(CacheIdentity.mayPublish(pinned.pin, latest.pin))
            }
            if (id == "rollback") {
                val checkpoint =
                    MetricReducer(Metrics.policy("humanPowerW"), AnalysisRange(0, pinned.prepared.actualEndUs))
                pinned.items
                    .filter { it.kind == "telemetry" }
                    .forEach {
                        checkpoint.append(monitorReading(it, "humanPowerW", pinned.prepared.timeline.membership(it)))
                    }
                assertEquals(
                    expected["checkpointAfterRollback"]["bucket"]["sumQ"].long(),
                    requireNotNull(checkpoint.checkpoint()).sum.value("humanPowerW", Quantity.sumQ),
                )
                val retryEvents = job["input"]["events"].list() + job["mutations"].list().single()["rollbackEvent"]
                val retry =
                    RideFixture(
                        overlay(job["input"], obj("events" to retryEvents)),
                        expected["retry"]["revision"].long(),
                    )
                assertEquals(expected["retry"]["workJoules"].double(), retry.summary.riderWorkJoules)
            }
        }
    }

    @Test
    fun analyticsAltitudeFixtures() {
        val approved = delta("altitude-msl-capture")
        val success = RideFixture(approved["input"]["referenceGeometryRides"]["msl"])
        val failure = RideFixture(approved["input"]["referenceGeometryRides"]["failed"])
        assertEquals(approved["expectedApproved"]["ascentMeters"].double(), success.summary.ascentMeters)
        assertEquals(approved["expectedApproved"]["descentMeters"].double(), success.summary.descentMeters)
        assertEquals(approved["expectedApproved"]["failedAscentMeters"].optionalDouble(), failure.summary.ascentMeters)
        assertEquals(
            approved["expectedApproved"]["failedDescentMeters"].optionalDouble(),
            failure.summary.descentMeters,
        )
        val c = case("altitude-capture-export")
        for (scenario in c["input"]["scenarios"].list()) {
            val input = scenario["input"]
            val geometry = GPSKernel(true)
            input["timesUs"].list().forEachIndexed { index, time ->
                val coordinate = input["horizontalFixes"].list()[index].list()
                val msl = input["converterOutcome"]["msl"].list()[index]
                val values =
                    mutableMapOf<String, Scalar>(
                        "latitude" to Scalar.Real64(coordinate[0].double()),
                        "longitude" to Scalar.Real64(coordinate[1].double()),
                        "horizontalAccuracyM" to Scalar.Real64(input["horizontalAccuracyM"].double()),
                        "speedMps" to Scalar.Real64(input["speedMps"].double()),
                    )
                if (msl != J.Null) {
                    values["altitudeMeters"] = Scalar.Real64(msl["meters"].double())
                    values["verticalAccuracyM"] = Scalar.Real64(msl["verticalAccuracyM"].double())
                }
                val item =
                    AnalysisItem(
                        time.long(),
                        FixedPoint.divide(time.long(), 1000000),
                        "$index",
                        "$index",
                        "phone",
                        index.toLong() + 1,
                        "location",
                        "phone",
                        "synthetic",
                        values,
                    )
                assertTrue(geometry.append(GPSFix(item, 0)).accepted)
            }
            val expected = c["expectedApproved"][scenario["name"].text()]
            assertEquals(
                expected["ascentMeters"].optionalDouble(),
                geometry.ascentQ()?.let { FixedPoint.divide(it, 1000) },
            )
            assertEquals(
                expected["descentMeters"].optionalDouble(),
                geometry.descentQ()?.let { FixedPoint.divide(it, 1000) },
            )
        }
        val raw = case("raw-data-wording")
        for (platform in raw["input"]["platforms"].list()) assertEquals(
            raw["expectedApproved"][platform.text()].text(),
            SummaryComposer.RAW_DATA,
        )
    }

    @Test
    fun analyticsProductionKernelC15() {
        val normalized =
            app.powerlog.core.PowerKernel.integrate(
                doubleArrayOf(0.0, 2.5000005),
                doubleArrayOf(100.0, 100.0),
                intArrayOf(0, 0),
            )
        assertEquals(250.0, normalized.integral)
        val quantized =
            app.powerlog.core.PowerKernel.integrate(
                doubleArrayOf(0.0, 1.0),
                doubleArrayOf(0.0625, 0.0625),
                intArrayOf(0, 0),
            )
        assertEquals(0.062, quantized.integral)
        assertEquals(0.0625, quantized.maximum)
    }

    @Test
    fun analyticsCheckpointAndGeometryEdges() {
        val range = AnalysisRange(0, 2000000)
        fun reading(time: Long, value: Scalar?, id: String, run: Continuity = Continuity()) =
            MetricReading(
                time,
                value?.let { Original(id, id, "synthetic", FixedPoint.divide(time, 1000000), it) },
                0,
                run,
            )
        val rows =
            listOf(
                reading(0, Scalar.Real64(100.0), "a"),
                reading(1000000, Scalar.Real64(-1.0), "barrier"),
                reading(2000000, Scalar.Real64(100.0), "b"),
            )
        val raw = MetricReducer(Metrics.policy("humanPowerW"), range)
        rows.forEach(raw::append)
        val first = MetricReducer(Metrics.policy("humanPowerW"), range)
        rows.take(2).forEach(first::append)
        val restored = MetricReducer(Metrics.policy("humanPowerW"), range, checkpoint = first.checkpoint())
        restored.append(rows.last())
        assertEquals(raw.result(), restored.result())
        val validPrefix = MetricReducer(Metrics.policy("humanPowerW"), range)
        validPrefix.append(rows.first())
        val validRestored = MetricReducer(Metrics.policy("humanPowerW"), range, checkpoint = validPrefix.checkpoint())
        validRestored.append(rows.last())
        assertEquals(200.0, validRestored.result().statistics.integral)

        val zero = MetricReducer(Metrics.policy("humanPowerW"), range)
        assertNull(zero.checkpoint())
        zero.append(reading(0, null, "absent"))
        assertNotNull(zero.checkpoint())
        assertNull(zero.checkpoint()?.previous)
        val exact = MetricReducer(Metrics.policy("consumedWh"), range)
        exact.append(reading(0, Scalar.Integer64(9007199254740993), "a"))
        exact.append(reading(1, Scalar.Integer64(9007199254740992), "b"))
        assertEquals("a", exact.result().statistics.max?.eventId)
        assertEquals("b", exact.result().statistics.min?.eventId)
        assertNull(exact.result().sumQ)
        for (values in listOf(listOf(-0.0, 0.0), listOf(0.0, -0.0))) {
            val signedZeros = MetricReducer(Metrics.policy("humanPowerW"), range)
            values.forEachIndexed { index, value ->
                signedZeros.append(
                    reading(index.toLong(), Scalar.Real64(value), if (index == 0) "first-zero" else "second-zero")
                )
            }
            assertEquals("first-zero", signedZeros.result().statistics.min?.eventId)
            assertEquals("first-zero", signedZeros.result().statistics.max?.eventId)
            assertEquals(values.first().toBits(), signedZeros.result().statistics.max?.value?.real()?.toBits())
        }

        assertEquals(0, compareScalars(Scalar.Integer64(0), Scalar.Real64(-0.0)))
        assertEquals(0, compareScalars(Scalar.Real64(-0.0), Scalar.Integer64(0)))
        val falseReading = reading(1000000, null, "unrelated").copy(selected = false)
        val continuous = MetricReducer(Metrics.policy("humanPowerW"), range)
        continuous.append(rows.first())
        continuous.append(falseReading)
        continuous.append(rows.last())
        assertEquals(200.0, continuous.result().statistics.integral)
        val controller = ControllerDistanceKernel()
        val originals =
            case("controller-counter-equal")["input"]["projectedControllerEvidence"].list().map(::controller)
        assertNull(controller.append(originals.first()))
        assertNull(controller.append(originals.first()))
        assertNull(controller.append(originals.last()))
        val gps = GPSKernel()
        val ride = RideFixture(reference("moving-gps-gap")["input"])
        val firstFix = GPSFix(ride.items.first(), 0)
        assertTrue(gps.append(firstFix).accepted)
        assertNull(gps.append(firstFix).interval)
        assertNull(gps.append(GPSFix(ride.items[1], 0)).interval)
        assertEquals(0.0, PureMath.haversine(0.0, 0.0, 0.0, 0.0))
        assertEquals(1.0, PureMath.sqrt(1.0))
        assertEquals(0.0, PureMath.sin(0.0))
        assertEquals(1.0, PureMath.cos(0.0))
        assertEquals(PureMath.PI / 2, PureMath.atan2(1.0, 0.0))
        val chords = case("gps-fixed-point-summary")["input"]["acceptedChordMeters"].list()
        listOf(0 to 1, 2 to 3).forEachIndexed { index, pair ->
            val a = ride.items[pair.first]
            val b = ride.items[pair.second]
            val chord =
                PureMath.haversine(
                    requireNotNull(a.number("latitude")),
                    requireNotNull(a.number("longitude")),
                    requireNotNull(b.number("latitude")),
                    requireNotNull(b.number("longitude")),
                )
            assertEquals(FixedPoint.quantize(chords[index].double(), 1000000), FixedPoint.quantize(chord, 1000000))
        }
    }

    @Test
    fun analyticsStreamingHealthAndMonitorPolicy() {
        val window = AnalysisRange(0, 2000000000)
        fun origin(id: String, app: String = "app.a") = Origin("healthConnect", app, id, id, "phone", 1)
        fun interval(index: Int, metric: String, app: String = "app.a") =
            EnergyInterval(
                "phone",
                origin("$metric:$index", app),
                1,
                index * 1000000L,
                (index + 1) * 1000000L,
                "synthetic",
                "synthetic",
                if (metric == "total") 2.0 else 1.0,
            )
        for (pageSize in listOf(1, 2, 256)) {
            val coverage = EnergyOriginPreparation("phone", window)
            (0 until 2000).chunked(pageSize).forEach { page ->
                page.forEach { index ->
                    for (metric in listOf("active")) {
                        val record = interval(index, metric)
                        coverage.append(metric, record)
                    }
                    coverage.append("active", interval(index, "active", "app.b"))
                }
            }
            assertEquals("app.a", coverage.selected()?.dataOrigin)
            assertEquals(window.endUs, coverage.selected()?.coveredUs)
        }
        val initial = interval(0, "active")
        val revisions =
            listOf(
                initial,
                initial,
                initial.copy(observationRevision = 2, valueKcal = 3.0),
                initial.copy(observationRevision = 3, valueKcal = 99.0),
            )
        val cursor = HealthIntervalRevisionCursor(2)
        revisions.chunked(1).forEach { page -> page.forEach { assertNull(cursor.append(it)) } }
        assertEquals(3.0, cursor.finish()?.valueKcal)
        val conflicting = HealthIntervalRevisionCursor(1)
        conflicting.append(initial)
        assertFailsWith<IllegalArgumentException> { conflicting.append(initial.copy(valueKcal = 5.0)) }
        val upper = "AAAAAAAA-0000-0000-0000-000000000000"
        assertEquals(0, compareOrigins(origin(upper), origin(upper.lowercase())))
        assertEquals(
            1,
            HealthOriginSelector.latestIntervals(
                    listOf(initial.copy(origin = origin(upper)), initial.copy(origin = origin(upper.lowercase()))),
                    1,
                )
                .size,
        )
        assertFailsWith<ArithmeticException> { FixedPoint.quantize(1e30, 1000) }
        val overflow = MetricReducer(Metrics.policy("motorInputPowerW"), AnalysisRange(0, 1))
        overflow.append(
            MetricReading(0, Original("big", "big", "synthetic", 0.0, Scalar.Real64(1e30)), 0, Continuity())
        )
        assertEquals(
            NumericOverflow("motorInputPowerW", Quantity.sumQ),
            (metricOutcome(overflow) as MetricOutcome.Overflow).failure,
        )
        val ride = RideFixture(reference("moving-gps-gap")["input"])
        val base = ride.items.first()
        val chart = MetricReducer(Metrics.monitorPolicy("speedMps"), AnalysisRange(0, 2000000))
        val rows =
            listOf(
                base.copy(values = base.values + ("speedMps" to Scalar.Real64(41.0))),
                base.copy(
                    collectionUs = 1000000,
                    values =
                        base.values +
                            mapOf("speedMps" to Scalar.Real64(41.0), "speedAccuracyMps" to Scalar.Real64(-1.0)),
                ),
                base.copy(collectionUs = 2000000, values = base.values + ("speedMps" to Scalar.Real64(41.0))),
            )
        rows.forEach { chart.append(monitorReading(it, "speedMps", 0)) }
        assertEquals(2L, chart.result().statistics.count)
        assertEquals(41.0, chart.result().statistics.sampleMean)
        assertEquals(0L, chart.result().coveredUs)
        assertFalse(Metrics.validSpeed(41.0))
        assertTrue(Metrics.monitorPolicy("altitudeMeters").valid(Scalar.Real64(21000.0)))
        assertFalse(Metrics.policy("altitudeMeters").valid(Scalar.Real64(21000.0)))
        val metadata =
            ride.pin.metadata.copy(
                watchEnabled = true,
                recordGPS = false,
                elapsedSeconds = 1.0,
                stopElapsedSeconds = 1.0,
                completeEncodedMetadata = "synthetic-derived-basal",
            )
        val pin = AnalysisPin(metadata.id, 1, metadata, "auto")
        val preparation = SummaryPreparation(pin)
        val phone =
            EnergyEvidence.CumulativeSnapshot(
                "basal",
                EnergyPoint("phone", origin("snapshot"), 1, 0, "synthetic", 10.0, Association.RideWindow(0, 1000000)),
            )
        val healthRow =
            base.copy(kind = "health", source = "phone", values = mapOf("basalEnergyKcal" to Scalar.Real64(10.0)))
        preparation.append(AnalysisProjection.Observation(healthRow))
        preparation.append(AnalysisProjection.Energy(phone, healthRow.observationId, phone.point.collectionUs))
        val prepared = preparation.finish()
        assertEquals(1L, prepared.eventCount)
        assertEquals("phone", prepared.healthSources["basal"])
        val composer =
            SummaryComposer(
                prepared,
                selectDistance("auto", emptyList(), true, false),
            )
        composer.energy(phone)
        assertEquals(10.0, (composer.finish() as SummaryOutcome.Complete).summary.basalEnergyKcal)
        composer.energy(
            EnergyEvidence.CumulativeSnapshot(
                "basal",
                phone.point.copy(valueKcal = 1e30),
            )
        )
        assertEquals(
            NumericOverflow("basalEnergyKcal", Quantity.energyQ),
            (composer.finish() as SummaryOutcome.Overflow).failure,
        )
        assertFailsWith<IllegalArgumentException> { pin.copy(distanceSelection = "unknown") }
        assertFailsWith<IllegalArgumentException> { selectDistance("unknown", emptyList(), false, false) }
        val hrMetadata =
            metadata.copy(
                watchEnabled = false,
                elapsedSeconds = 2.0,
                stopElapsedSeconds = 2.0,
                sources = listOf(MeasurementSource("phone", "androidPhone", "healthConnect")),
                completeEncodedMetadata = "synthetic-hr-origins",
            )
        val hrPin = AnalysisPin(hrMetadata.id, 1, hrMetadata, "auto")
        val hrRows =
            (0..2)
                .flatMap { second ->
                    listOf("app.a", "app.b").map { packageName ->
                        base.copy(
                            kind = "health",
                            source = "phone",
                            collectionUs = second * 1000000L,
                            collectionElapsedSeconds = second.toDouble(),
                            observationId = "$packageName:$second",
                            representation = "rawQuantity",
                            values =
                                mapOf("heartRateBpm" to Scalar.Real64(if (packageName == "app.a") 100.0 else 200.0)),
                            origin =
                                Origin("healthConnect", packageName, "$second", "$packageName:$second", "phone", 1),
                            declaredMetrics = listOf("heartRateBpm"),
                        )
                    }
                }
                .toMutableList()
        hrRows[2] =
            hrRows[2].copy(
                values = mapOf("respiratoryRate" to Scalar.Real64(15.0)),
                declaredMetrics = listOf("respiratoryRate"),
            )
        val hrPreparation = SummaryPreparation(hrPin)
        hrRows.forEach(hrPreparation::append)
        val hrPrepared = hrPreparation.finish()
        val hrSelection =
            HealthSelection("heartRateBpm", "phone", "app.a", 2000000, emptyList(), Association.RideWindow(0, 2000000))
        val hrComposer =
            SummaryComposer(hrPrepared, selectDistance("auto", emptyList(), false, false), listOf(hrSelection))
        hrRows.forEach(hrComposer::append)
        val hrSummary = (hrComposer.finish() as SummaryOutcome.Complete).summary
        assertEquals(100.0, hrSummary.averageHeartRateBpm)
        assertEquals(100.0, hrSummary.maximumHeartRateBpm)
        assertEquals(2.0, hrSummary.heartRateCoveredSeconds)
        assertEquals(6L, hrSummary.healthCount)
        val missingOrigin =
            SummaryComposer(hrPrepared, selectDistance("auto", emptyList(), false, false), listOf(hrSelection))
        missingOrigin.append(hrRows.first().copy(origin = null))
        assertEquals("Missing heart-rate origin", (missingOrigin.finish() as SummaryOutcome.InvalidEvidence).reason)
        assertFalse(monitorReading(hrRows[2], "heartRateBpm", 0).selected)
        assertTrue(monitorReading(hrRows.first().copy(values = emptyMap()), "heartRateBpm", 0).selected)
    }
}
