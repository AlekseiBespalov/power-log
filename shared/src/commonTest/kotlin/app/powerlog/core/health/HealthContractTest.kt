package app.powerlog.core.health

import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.Timestamps
import kotlin.test.*

class HealthContractTest : HealthFixtureTest() {
    @Test
    fun healthCanonicalIdentityContract() {
        for (c in cases("identity")) {
            val i = c["input"]
            val e = c["expected"]
            when (c["name"].text()) {
                "canonical-ids" -> {
                    val r = i["rideId"].text()
                    eq(e["workout"].text(), HealthIds.workout(r))
                    eq(e["second"].text(), HealthIds.second(r, HealthKind.riderPower, i["second"].long()))
                    eq(e["route"].text(), HealthIds.route(r))
                    eq(e["lap"].text(), HealthIds.lap(r, i["eventId"].text()))
                    eq(e["distance"].text(), HealthIds.distance(r, i["from"].text(), i["to"].text()))
                    eq(PHONE, HealthIds.uuid(PHONE.uppercase()))
                }
                "bucket-boundaries" -> {
                    eq(i["startUs"].long(), Timestamps.parse(i["canonicalStart"].text()).comparisonUs)
                    val start = i["startUs"].long()
                    val end = start + i["endOffsetUs"].long()
                    i["offsetsUs"].list().forEachIndexed { n, v ->
                        val index = SecondBuckets.index(start, start + v.long())
                        eq(e["computedIndices"].list()[n].long(), index)
                        eq(e["publishableOffset"].list()[n].bool(), v.long() >= 0 && start + v.long() < end)
                    }
                    e["rangesOffsetUs"].list().forEachIndexed { n, range ->
                        val bounds = SecondBuckets.bounds(start, end, n.toLong())
                        eq(range.list()[0].long(), bounds.startUs - start)
                        eq(range.list()[1].long(), bounds.endUs - start)
                    }
                }
                "reject-uuid" -> fails<IllegalArgumentException> { HealthIds.uuid(i["rideId"].text()) }
                else -> fails<IllegalArgumentException> { HealthIds.origin(i["producer"].text(), i["sequence"].text()) }
            }
        }
        eq(-1L, SecondBuckets.index(0, -1))
    }

    @Test
    fun healthSecondFixtures() {
        for (c in cases("seconds")) {
            val i = c["input"]
            val e = c["expected"]
            if (c["name"].text() == "phone-grace-and-stop-partial-finality") {
                val eligible =
                    i["closedBucketEndsUs"].list().mapIndexedNotNull { index, v ->
                        if (SecondBuckets.eligible(v.long(), i["nowUs"].long(), false)) index.toLong() else null
                    }
                eq(e["liveEligibleN"].list().map { it.long() }, eligible)
                val count = SecondBuckets.count(0, i["cutoffUs"].long())
                eq(e["atStop"]["requiredBuckets"].long(), count)
                eq(
                    e["atStop"]["endOffsetsUs"].list().map { it.long() },
                    (0 until count).map { SecondBuckets.bounds(0, i["cutoffUs"].long(), it).endUs },
                )
                continue
            }
            val kind = HealthKind.entries.first { it.wire == i["metric"].text() }
            val end = i["endOffsetUs"].long()
            val rs = readings(i)
            val outcomes =
                (0 until SecondBuckets.count(0, end)).map {
                    SecondKernel.aggregate(SecondBuckets.bounds(0, end, it), kind, rs)
                }
            val samples = outcomes.filterIsInstance<SecondOutcome.Sample>().map { it.sample }
            if (e["samples"] != J.Null) {
                eq(e["samples"].list().size, samples.size)
                e["samples"].list().zip(samples).forEach { (a, b) -> sampleExpected(a, b) }
            }
            if (e["seconds"] != J.Null)
                for (s in e["seconds"].list()) eq(
                    s["outcome"].text().substringAfter(':'),
                    (outcomes[s["n"].long().toInt()] as SecondOutcome.Absent).reason.name,
                )
            if (e["absentSeconds"] != J.Null)
                for (s in e["absentSeconds"].list()) eq(
                    s["reason"].text(),
                    (outcomes[s["n"].long().toInt()] as SecondOutcome.Absent).reason.name,
                )
            if (e["admittedQ"] != J.Null)
                eq(
                    e["admittedQ"].list().map { it.long() },
                    rs.map { SecondKernel.policy(kind).admit(it.originalScalar) },
                )
            if (e["phoneSecond"] != J.Null) {
                sampleExpected(e["phoneSecond"], samples.single())
                val cadence =
                    SecondKernel.aggregate(
                        SecondBuckets.bounds(0, end, 0),
                        HealthKind.cyclingCadence,
                        readings(i, valueKey = "cadenceRpm"),
                    ) as SecondOutcome.Sample
                sampleExpected(e["phoneCadenceSecond"], cadence.sample)
            }
        }
    }

    @Test
    fun healthRouteFixtures() {
        val root = fixture("health/route.json")
        val f = root["recordFields"]
        val encoding =
            RouteEncoding(
                f["providerUUIDReserve"].optionalText(),
                f["packageName"].optionalText(),
                f["appName"].optionalText(),
                f["clientRecordId"].optionalText(),
                f["manufacturer"].optionalText(),
                f["model"].optionalText(),
                f["notes"].optionalText(),
                f["title"].optionalText(),
                f["plannedExerciseSessionId"].optionalText(),
                1,
                0,
            )
        eq(root["baseBytes"].long(), HealthRoutes.baseBytes(encoding))
        root["stringBytes"].fields().forEach { (k, v) -> eq(v.long(), HealthRoutes.stringBytes(f[k].optionalText())) }
        for (c in root["cases"].list()) {
            val i = c["input"]
            val e = c["expected"]
            val name = c["name"].text()
            if (name == "utf16-not-utf8") {
                eq(
                    e["stringBytes"].list().map { it.long() },
                    i["texts"].list().map { HealthRoutes.stringBytes(it.optionalText()) },
                )
                continue
            }
            if (name == "duplicate-end-ms-collapse") {
                val points =
                    i["points"]
                        .list()
                        .map { RoutePoint(it["id"].text(), it["offsetUs"].long(), 0.0, 0.0, null, null, null) }
                        .toTypedArray()
                val result =
                    HealthRoutes.project(
                        Provider.healthConnect,
                        i["sessionStartOffsetUs"].long(),
                        i["sessionEndOffsetUs"].long(),
                        points,
                        i["baseBytes"].long(),
                    ) as RouteProjection.Reduced
                eq(e["selectedIds"].list().map { it.text() }, result.ids)
                eq(e["selectedEncodedBytes"].long(), result.encodedBytes)
                val apple =
                    HealthRoutes.project(Provider.appleHealth, 0, 2000000, points, null, 0) as RouteProjection.Full
                eq(e["appleAdmittedOriginalCount"].long(), apple.ids.size.toLong())
                continue
            }
            val count =
                when {
                    i["syntheticPoints"] != J.Null -> i["syntheticPoints"]["count"].long().toInt()
                    i["pointCount"] != J.Null -> i["pointCount"].long().toInt()
                    else -> i["eligibleCount"].long().toInt()
                }
            val base = i["baseBytes"].optionalText()?.toLong()
            val budget = i["budgetBytes"].optionalText()?.toLong() ?: 900000L
            val selected = HealthRoutes.indices(count, base, budget)
            if (e["outcome"] != J.Null) {
                eq(null, selected)
                val points =
                    Array(count) { index ->
                        RoutePoint("seq:phone:${index+1}", index * 1000000L, 0.0, 0.0, null, null, null)
                    }
                val projection =
                    HealthRoutes.project(Provider.healthConnect, 0, count * 1000000L, points, base, budget)
                        as RouteProjection.Unavailable
                eq(e["outcome"].text().substringAfter(':'), projection.reason.name)
                continue
            }
            val ids = requireNotNull(selected)
            val bytes = base!! + ids.size * 48L
            if (e["priorityIndices"] != J.Null)
                eq(e["priorityIndices"].list().map { it.long().toInt() }, HealthRoutes.priority(count).toList())
            if (e["selectedIndices"] != J.Null) eq(e["selectedIndices"].list().map { it.long().toInt() }, ids.toList())
            if (e["selectedCount"] != J.Null) eq(e["selectedCount"].long(), ids.size.toLong())
            for (key in listOf("encodedBytes", "selectedEncodedBytes")) if (e[key] != J.Null) eq(e[key].long(), bytes)
            if (e["fullEncodedBytes"] != J.Null) eq(e["fullEncodedBytes"].long(), base + count * 48L)
            if (e["nextEncodedBytes"] != J.Null) eq(e["nextEncodedBytes"].long(), bytes + 48)
            if (e["sortedIndicesSha256DecimalLF"] != J.Null) {
                eq(e["sortedIndicesSha256DecimalLF"].text(), digestLines(ids.map { it.toString() }))
                eq(e["firstTenSelectedIndices"].list().map { it.long().toInt() }, ids.take(10))
                eq(e["lastTenSelectedIndices"].list().map { it.long().toInt() }, ids.takeLast(10))
                val spec = i["syntheticPoints"]
                val points =
                    Array(count) { index ->
                        RoutePoint(
                            HealthIds.origin(
                                spec["originProducer"].text(),
                                (spec["firstOriginSequence"].long() + index).toString(),
                            ),
                            spec["firstOffsetUs"].long() + index * spec["stepUs"].long(),
                            spec["latitude"].double(),
                            index % spec["longitudeCycleCount"].long() * spec["longitudeStepDegrees"].double(),
                            spec["horizontalAccuracyM"].double(),
                            null,
                            null,
                        )
                    }
                val projection =
                    HealthRoutes.project(Provider.healthConnect, 0, i["sessionEndOffsetUs"].long(), points, base)
                        as RouteProjection.Reduced
                val eligibility = RouteEligibilityCursor(Provider.healthConnect, 0, i["sessionEndOffsetUs"].long())
                val retention = RouteRetentionCursor(ids)
                val retained =
                    points.toList().chunked(128).flatMap {
                        retention.append(eligibility.append(it.toTypedArray())).toList()
                    }
                eq(count.toLong(), eligibility.count)
                yes(retention.exhausted())
                eq(projection.ids, retained.map { it.originKey })
                eq(ids.map { points[it].originKey }, projection.ids)
                eq(e["originalCount"].long(), projection.originalCount)
                eq(e["eligibleCount"].long(), projection.eligibleCount)
            }
        }
    }
}
