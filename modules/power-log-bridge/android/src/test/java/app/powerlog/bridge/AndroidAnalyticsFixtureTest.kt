package app.powerlog.bridge

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidAnalyticsFixtureTest {
    private val eventIds = mutableMapOf<String, String>()

    @Test
    fun healthOriginFixturesUseProviderReadbackDetailAndMonitor() {
        val cases = healthFixture("readback").getJSONArray("cases")
        val names =
            setOf(
                "origin-largest-coverage",
                "origin-union-tie",
                "heart-rate-one-origin",
                "heart-rate-supported-coverage-not-parent-window",
            )
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getString("name") !in names) continue
            for (reverse in listOf(false, true)) {
                RideStore(RuntimeEnvironment.getApplication(), "health-fixtures-${UUID.randomUUID()}.sqlite").use {
                    store ->
                    val input = case.getJSONObject("input")
                    val endUs = input.getJSONArray("windowUs").getString(1).toLong()
                    val id =
                        store.create(
                            RideOptions(true, saveToHealth = false, recordGPS = false),
                            RecordingClockReading(start.toString(), 0),
                        )
                    store.seal(
                        id,
                        RideTiming(endUs / 1000000.0, endUs / 1000000.0, start.plusNanos(endUs * 1000).toString()),
                    )
                    val readback = HealthReadback(store, FakeHealthConnectAccess())
                    fun metadata(record: JSONObject, fallback: String) =
                        HealthMetadata.received(
                            UUID.nameUUIDFromBytes(record.optString("providerId", fallback).toByteArray()).toString(),
                            "generated:$fallback",
                            record.optString("nativeRevision", "1").toLong(),
                            null,
                            record.getString("dataOrigin"),
                            start,
                            androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                        )
                    fun records(key: String): List<JSONObject> =
                        input.optJSONArray(key)?.let { array ->
                            (0 until array.length())
                                .map { array.getJSONObject(it) }
                                .let {
                                    if (reverse) it.reversed() else it
                                }
                        } ?: emptyList()
                    for (parent in records("parents")) {
                        val members = parent.getJSONArray("members")
                        readback.import(
                            id,
                            androidx.health.connect.client.records.HeartRateRecord(
                                start,
                                java.time.ZoneOffset.UTC,
                                start.plusNanos((endUs + 1) * 1000),
                                java.time.ZoneOffset.UTC,
                                (0 until members.length()).map {
                                    val member = members.getJSONObject(it)
                                    val bpm = member.getLong("bpm")
                                    val sample =
                                        androidx.health.connect.client.records.HeartRateRecord.Sample(
                                            start.plusNanos(member.getString("offsetUs").toLong() * 1000),
                                            maxOf(1, bpm),
                                        )
                                    if (bpm < 1) {
                                        androidx.health.connect.client.records.HeartRateRecord.Sample::class
                                            .java
                                            .getDeclaredField("beatsPerMinute")
                                            .apply { isAccessible = true }
                                            .setLong(sample, bpm)
                                    }
                                    sample
                                },
                                metadata(parent, "heart:${parent.getString("dataOrigin")}"),
                            ),
                        )
                    }
                    for (key in listOf("records")) for (record in records(key)) {
                        val a = start.plusNanos(record.getString("startUs").toLong() * 1000)
                        val b = start.plusNanos(record.getString("endUs").toLong() * 1000)
                        val energy =
                            androidx.health.connect.client.units.Energy.kilocalories(record.getDouble("valueKcal"))
                        val metadata = metadata(record, "$key:${record.getString("dataOrigin")}")
                        readback.import(
                            id,
                            androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                                a,
                                java.time.ZoneOffset.UTC,
                                b,
                                java.time.ZoneOffset.UTC,
                                energy,
                                metadata,
                            ),
                        )
                    }
                    val distance = RideDistance(store)
                    val summary = RideDetail(store, distance).detail(id, "auto")["summary"] as Map<*, *>
                    val expected = case.getJSONObject("expected")
                    val origins = summary["healthOrigins"] as Map<*, *>
                    if (input.has("parents")) {
                        assertEquals(expected.getString("dataOrigin"), origins["heartRate"])
                        assertEquals(expected.getDouble("averageBpm"), summary["averageHeartRateBpm"])
                        assertEquals(expected.getDouble("maximumBpm"), summary["maximumHeartRateBpm"])
                        store.withReadPin(id) {
                            val selections = HealthReadings(store).selections(id)
                            assertEquals(
                                expected.getString("coveredUs").toLong(),
                                selections.single { it.metric == "heartRateBpm" }.unionCoverageUs,
                            )
                            assertEquals(
                                expected.getDouble("averageBpm"),
                                store.analytics.stats(id, "heartRateBpm", 0.0, endUs / 1000000.0)["sampleMean"],
                            )
                        }
                    } else {
                        assertEquals(expected.getString("dataOrigin"), origins["activeEnergy"])
                        assertEquals(expected.getDouble("valueKcal"), summary["activeEnergyKcal"])
                        store.withReadPin(id) {
                            assertEquals(
                                expected.getString("coverageUs").toLong(),
                                HealthReadings(store).selections(id).single { it.metric == "active" }.unionCoverageUs,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun install(store: RideStore, input: JSONObject): Pair<String, Int> {
        val create = input.getJSONObject("create")
        val id =
            store.create(
                RideOptions(
                    create.getBoolean("indoor"),
                    saveToHealth = false,
                    recordGPS = create.getBoolean("recordGPS"),
                    sampleHz = 8,
                ),
                RecordingClockReading(create.getString("startedAt"), 0),
            )
        val distance = RideDistance(store)
        val events = input.getJSONArray("events")
        var extra = 2
        val sequences = mutableMapOf<String, Long>("phone" to 1)
        store.transaction {
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                val payload = event.getJSONObject("payload")
                val kind = event.getString("kind")
                val producer = event.getString("source")
                if (kind == "lifecycle" && payload.getString("action") in listOf("start", "stop")) {
                    extra--
                    continue
                }
                val sequence = (sequences[producer] ?: 0) + 1
                sequences[producer] = sequence
                val original = OriginalIdentity(event.getString("eventId"), producer, sequence)
                val time = event.getDouble("elapsedSeconds")
                val stamp = event.getString("timestamp")
                if (kind == "lifecycle")
                    store.lifecycle(
                        id,
                        RideTiming(time, time, stamp),
                        payload.getString("action"),
                        original = original,
                    )
                else {
                    val values =
                        payload
                            .keys()
                            .asSequence()
                            .filter { it in metricBits && payload.get(it) is Number }
                            .associateWith { payload.getDouble(it) }
                    val identity =
                        "${payload.optString("controllerModel", "X6")}|${payload.optString("captureSessionID", "fixture")}|${payload.optString("controllerProtocol", "5.3")}"
                    val epoch = payload.optString("connectionEpoch", "fixture")
                    val row =
                        store.insert(
                            id,
                            time,
                            stamp,
                            kind,
                            true,
                            0,
                            values,
                            identity,
                            epoch,
                            original,
                            OriginalEvidence(clockEpoch = payload.optString("clockEpoch")),
                        )
                    distance.append(
                        id,
                        row,
                        time,
                        values,
                        true,
                        0,
                        epoch,
                        identity,
                        kind == "location",
                    )
                }
            }
        }
        val elapsed =
            input.optDouble(
                "stopElapsedSeconds",
                input.optJSONObject("metadataUpdate")?.optDouble("elapsedSeconds", 0.0) ?: 0.0,
            )
        store.seal(
            id,
            RideTiming(elapsed, elapsed, input.optString("endedAt", create.getString("startedAt"))),
        )
        eventIds.clear()
        store.each(id) { row ->
            eventIds[row.id.toString()] = "event:${requireNotNull(row.original).eventId}"
        }
        return id to extra
    }

    private fun compare(expected: Any?, actual: Any?, path: String) {
        when (expected) {
            JSONObject.NULL,
            null -> assertTrue(path, actual == null || actual == JSONObject.NULL)
            is JSONObject -> {
                val value =
                    when (actual) {
                        is JSONObject -> actual
                        is Map<*, *> -> JSONObject(actual)
                        else -> fail("$path: $actual").let { JSONObject() }
                    }
                for (key in expected.keys()) if (key != "exactValue") {
                    if (key == "observationId")
                        assertEquals(
                            "$path/$key",
                            expected.get(key),
                            eventIds[value.getString(key)],
                        )
                    else compare(expected.get(key), value.opt(key), "$path/$key")
                }
            }
            is JSONArray -> {
                val value =
                    when (actual) {
                        is JSONArray -> actual
                        is List<*> -> JSONArray(actual)
                        else -> fail("$path: $actual").let { JSONArray() }
                    }
                assertEquals(path, expected.length(), value.length())
                for (i in 0 until expected.length()) compare(
                    expected.get(i),
                    value.get(i),
                    "$path/$i",
                )
            }
            is Number ->
                assertEquals(
                    path,
                    expected.toDouble().toBits(),
                    (actual as Number).toDouble().toBits(),
                )
            else -> assertEquals(path, expected, actual)
        }
    }

    @Test
    fun iphoneReferenceSummariesAndChartTargetsUseAndroidProductionSeams() {
        val references = analyticsFixture("iphone-reference").getJSONArray("cases")
        val deltas = analyticsFixture("approved-deltas").getJSONArray("cases")
        val reviews = analyticsFixture("review-required").getJSONArray("cases")
        val gpsApproved =
            (0 until reviews.length())
                .map { reviews.getJSONObject(it) }
                .single { it.getString("testId") == "gps-fixed-point-summary" }
                .getJSONObject("expectedApproved")
        val chart =
            (0 until deltas.length())
                .map { deltas.getJSONObject(it) }
                .single { it.getString("testId") == "chart-reference-targets" }
                .getJSONObject("expectedApproved")
        val labels =
            (0 until deltas.length())
                .map { deltas.getJSONObject(it) }
                .single { it.getString("testId") == "source-labels" }
                .getJSONObject("expectedApproved")
                .getJSONObject("summaries")
        RideStore(RuntimeEnvironment.getApplication(), "fixtures-${UUID.randomUUID()}.sqlite").use { store ->
            for (index in 0 until references.length()) {
                val case = references.getJSONObject(index)
                val input = case.getJSONObject("input")
                val events = input.getJSONArray("events")
                if (
                    (0 until events.length()).any {
                        events.getJSONObject(it).getString("kind") == "health"
                    } || input.getJSONObject("create").getBoolean("watchEnabled")
                )
                    continue
                val name = case.getString("name")
                val (id, extra) = install(store, input)
                val distance = RideDistance(store)
                val actual =
                    RideDetail(store, distance).detail(id, input.optString("distanceSource", "auto"))["summary"]
                        as Map<*, *>
                val expected = JSONObject(case.getJSONObject("expectedSummary").toString())
                if (name == "moving-gps-gap") for (key in gpsApproved.keys()) expected.put(key, gpsApproved.get(key))
                expected.getJSONObject("distance").put("selection", store.metadata(id).str("distanceSource"))
                labels.optJSONObject(name)?.optJSONObject("provenance")?.let { approved ->
                    for (key in approved.keys()) expected.getJSONObject("provenance").put(key, approved.get(key))
                }
                for (key in listOf("distance", "provenance")) expected.put(
                    key,
                    JSONObject(expected.getJSONObject(key).toString().replace("GPS · iPhone", "GPS · Android phone")),
                )
                assertEquals(id, actual["id"])
                for (key in expected.keys()) {
                    if (
                        key in
                            setOf(
                                "id",
                                "eventCount",
                                "telemetryCount",
                                "locationCount",
                                "healthCount",
                            )
                    )
                        continue
                    compare(expected.get(key), actual[key], "$name/summary/$key")
                }
                assertEquals(
                    name,
                    expected.getLong("eventCount") + extra,
                    (actual["eventCount"] as String).toLong(),
                )
                for (key in listOf("telemetryCount", "locationCount", "healthCount")) assertEquals(
                    name,
                    expected.getLong(key),
                    (actual[key] as String).toLong(),
                )
                val absent = case.optJSONArray("expectedAbsentSummaryFields") ?: JSONArray()
                for (j in 0 until absent.length()) assertFalse(
                    "$name/${absent.getString(j)}",
                    actual.containsKey(absent.getString(j)),
                )
                val queries = input.getJSONArray("queries")
                val targets = chart.optJSONArray(name) ?: continue
                for (j in 0 until queries.length()) {
                    val query = queries.getJSONObject(j)
                    val target = targets.getJSONObject(j)
                    for (metric in target.keys()) if (metric != "distanceMeters")
                        compare(
                            target.getJSONObject(metric),
                            store.analytics.stats(
                                id,
                                metric,
                                query.getDouble("startSeconds"),
                                query.getDouble("endSeconds"),
                            ),
                            "$name/query$j/$metric",
                        )
                }
            }
        }
    }

    @Test
    fun checkpointTransitionFixturesComposeWiderQueriesAndRetainExactDurableState() {
        val cases = analyticsFixture("review-required").getJSONArray("cases")
        RideStore(RuntimeEnvironment.getApplication(), "checkpoints-${UUID.randomUUID()}.sqlite").use { store ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                if (
                    case.getString("testId") !in
                        listOf(
                            "checkpoint-valid",
                            "checkpoint-paused",
                            "checkpoint-invalid",
                            "checkpoint-changed-epoch",
                        )
                )
                    continue
                val (id, _) =
                    install(
                        store,
                        case.getJSONObject("input").getJSONObject("archiveJob").getJSONObject("input"),
                    )
                val expected = case.getJSONObject("expectedApproved").getJSONObject("accumulators")
                val result = store.analytics.stats(id, "humanPowerW", 0.0, 64.0)
                assertEquals(
                    case.getString("testId"),
                    expected.getLong("coveredUs") / 1000000.0,
                    result.num("coveredSeconds"),
                    0.0,
                )
                assertEquals(
                    case.getString("testId"),
                    expected.getLong("integralQUs") / 1000000000.0,
                    result.num("integral"),
                    0.0,
                )
                store.select(
                    "SELECT payload FROM analytics WHERE ride=? AND metric='humanPowerW' AND bucket=1 ORDER BY revision DESC LIMIT 1",
                    arrayOf(id),
                ) { c ->
                    assertTrue(c.moveToFirst())
                    val payload = JSONObject(c.getString(0))
                    val state = JSONObject(payload.getJSONObject("checkpoint").getString("state"))
                    assertTrue(state.get("count") is String)
                    assertTrue(state.getJSONObject("integral").get("positive") is String)
                    assertTrue(state.has("firstRaw"))
                    assertTrue(state.has("lastRaw"))
                    assertTrue(state.has("overflow"))
                }
            }
        }
    }
}
