package app.powerlog.core.health

import app.powerlog.core.analytics.*
import kotlin.test.*

class HealthReadbackTest : HealthFixtureTest() {
    private fun intervals(j: J): List<EnergyInterval> =
        j.list().map { r ->
            EnergyInterval(
                "phone",
                Origin(
                    "healthConnect",
                    r["dataOrigin"].text(),
                    r["providerId"].text(),
                    r["providerId"].text(),
                    "phone",
                    1,
                    r["nativeRevision"].text(),
                ),
                r["nativeRevision"].long(),
                r["startUs"].long(),
                r["endUs"].long(),
                UTC,
                UTC,
                r["valueKcal"].double(),
            )
        }

    private fun tuple(j: J) =
        MemberTuple(
            j["startUs"].long(),
            j["startSubUsNs"].long().toInt(),
            j["endUs"].long(),
            j["endSubUsNs"].long().toInt(),
            j["metric"].text(),
            j["unit"].text(),
            j["valueBits"].text(),
        )

    @Test
    fun healthReadbackFixtures() {
        for (c in cases("readback")) {
            val i = c["input"]
            val e = c["expected"]
            val name = c["name"].text()
            when {
                i["callbackMembers"] != J.Null -> {
                    val tuples = i["callbackMembers"].list().map(::tuple).sortedWith(ProviderMembers::compare)
                    val builder = ParentManifestBuilder()
                    tuples.chunked(128).forEach { builder.append(it.toTypedArray()) }
                    val digest = builder.finish(tuples.size.toLong(), true)
                    eq(e["snapshotDigest"].text(), digest)
                    val parent =
                        ProviderParent(
                            Provider.valueOf(i["provider"].text()),
                            i["dataOrigin"].text(),
                            i["parentId"].text(),
                            tuples.first().metric,
                            UTC,
                            UTC,
                            null,
                            digest,
                            tuples.size.toLong(),
                        )
                    var cursor = MemberCursor(digest, 0, null, 0)
                    val members = mutableListOf<ProviderMember>()
                    for (page in tuples.chunked(128)) {
                        val result =
                            ProviderMembers.page(
                                parent,
                                cursor,
                                page.map { MemberInput(it, UTC, UTC) }.toTypedArray(),
                            )
                        cursor = result.cursor
                        members.addAll(result.members)
                    }
                    eq(e["memberCount"].long(), members.size.toLong())
                    val suffix =
                        if (i["committedCanonicalIndices"] == J.Null) members
                        else members.drop(i["committedCanonicalIndices"].list().size)
                    val prefix = if (name == "partial-parent-128") "remaining" else ""
                    val keys =
                        if (prefix.isEmpty())
                            listOf(
                                "canonicalIndices",
                                "occurrenceIndices",
                                "logicalKeys",
                                "eventIds",
                            )
                        else
                            listOf(
                                "remainingCanonicalIndices",
                                "remainingOccurrenceIndices",
                                "remainingLogicalKeys",
                                "remainingEventIds",
                            )
                    eq(e[keys[0]].list().map { it.long() }, suffix.map { it.canonicalIndex })
                    eq(e[keys[1]].list().map { it.long() }, suffix.map { it.occurrenceIndex })
                    eq(e[keys[2]].list().map { it.text() }, suffix.map { it.logicalKey })
                    eq(e[keys[3]].list().map { it.text() }, suffix.map { it.eventId })
                    if (e["orderedValueBits"] != J.Null)
                        eq(
                            e["orderedValueBits"].list().map { it.text() },
                            members.map { it.valueBits },
                        )
                    if (e["allEventIdsSha256LF"] != J.Null)
                        eq(e["allEventIdsSha256LF"].text(), digestLines(members.map { it.eventId }))
                    val reversed =
                        i["callbackMembers"].list().reversed().map(::tuple).sortedWith(ProviderMembers::compare)
                    val replay = ParentManifestBuilder()
                    reversed.chunked(128).forEach { replay.append(it.toTypedArray()) }
                    eq(digest, replay.finish(reversed.size.toLong(), true))
                }
                name == "provider-original-never-republished" -> {
                    val t = tuple(i["member"])
                    val manifest = ParentManifestBuilder()
                    manifest.append(arrayOf(t))
                    val digest = manifest.finish(1, true)
                    val parent =
                        ProviderParent(
                            Provider.healthConnect,
                            i["dataOrigin"].text(),
                            i["parentId"].text(),
                            t.metric,
                            UTC,
                            UTC,
                            null,
                            digest,
                            1,
                        )
                    val member =
                        ProviderMembers.page(
                                parent,
                                MemberCursor(digest, 0, null, 0),
                                arrayOf(MemberInput(t, UTC, UTC)),
                            )
                            .members
                            .single()
                    eq(
                        e["publicationPlans"].list().size,
                        ProviderMembers.publishImportedMember(member).size,
                    )
                }
                name == "apple-anchored-short-pages" -> {
                    val samples = mutableListOf<String>()
                    val deletes = mutableListOf<String>()
                    var anchor = ""
                    var count = 0
                    for (page in i["pages"].list()) {
                        count++
                        samples.addAll(page["samples"].list().map { it.text() })
                        deletes.addAll(page["deleted"].list().map { it.text() })
                        anchor = page["nextAnchor"].text()
                        if (
                            ProviderMembers.exhausted(
                                page["samples"].list().size,
                                page["deleted"].list().size,
                            )
                        )
                            break
                    }
                    eq(e["consumedPages"].long(), count.toLong())
                    eq(e["committedSamples"].list().map { it.text() }, samples)
                    eq(e["committedDeletions"].list().map { it.text() }, deletes)
                    eq(e["finalAnchor"].text(), anchor)
                }
                i["parents"] != J.Null -> {
                    val window =
                        AnalysisRange(
                            i["windowUs"].list()[0].long(),
                            i["windowUs"].list()[1].long(),
                        )
                    val evidence =
                        i["parents"].list().flatMap { parent ->
                            parent["members"].list().mapIndexed { n, m ->
                                HeartRateEvidence(
                                    Origin(
                                        "healthConnect",
                                        parent["dataOrigin"].text(),
                                        parent["dataOrigin"].text(),
                                        "hr-$n",
                                        "phone",
                                        n + 1L,
                                    ),
                                    "phone",
                                    Association.RideWindow(window.startUs, window.endUs),
                                    UTC,
                                    UTC,
                                    m["offsetUs"].long(),
                                    m["offsetUs"].long(),
                                    m["offsetUs"].long(),
                                    m["offsetUs"].long(),
                                    Continuity(),
                                    "rawSeries",
                                    "${parent["dataOrigin"].text()}:$n",
                                    1,
                                    m["bpm"].double(),
                                    "bpm",
                                    1,
                                )
                            }
                        }
                    val selected = requireNotNull(HealthOriginSelector.heartRate(evidence, "phone", window))
                    eq(e["dataOrigin"].text(), selected.dataOrigin)
                    eq(e["coveredUs"].long(), selected.unionCoverageUs)
                    val reducer = MetricReducer(Metrics.policy("heartRateBpm"), window)
                    evidence
                        .filter { it.origin.dataOrigin == selected.dataOrigin }
                        .sortedBy { it.collectionUs }
                        .forEach {
                            reducer.append(
                                MetricReading(
                                    it.collectionUs,
                                    Original(
                                        it.memberIdentity!!,
                                        it.origin.originEventId,
                                        UTC,
                                        0.0,
                                        Scalar.Real64(it.valueBpm!!),
                                    ),
                                    0,
                                    it.continuity,
                                )
                            )
                        }
                    eq(e["averageBpm"].double(), reducer.result().average)
                    eq(e["maximumBpm"].double(), reducer.result().statistics.max?.value?.real())
                }
                i["records"] != J.Null -> {
                    val window =
                        AnalysisRange(
                            i["windowUs"].list()[0].long(),
                            i["windowUs"].list()[1].long(),
                        )
                    val records = intervals(i["records"])
                    val selection = HealthOriginSelector.energy(records, "phone", "active", window, 2)!!
                    val reducer = EnergyReducer("active", window.endUs, "phone", selection.dataOrigin)
                    HealthOriginSelector.latestIntervals(records, 2).forEach {
                        reducer.append(EnergyEvidence.IntervalRecord("active", it))
                    }
                    val value = FixedPoint.divide(reducer.valueQ()!!, 1000000)
                    if (e["dataOrigin"] != J.Null) eq(e["dataOrigin"].text(), selection.dataOrigin)
                    if (e["coverageUs"] != J.Null) eq(e["coverageUs"].long(), selection.unionCoverageUs)
                    eq(
                        (if (e["valueKcal"] != J.Null) e["valueKcal"] else e["activeKcal"]).double(),
                        value,
                    )
                }
                else -> error(name)
            }
        }
    }
}
