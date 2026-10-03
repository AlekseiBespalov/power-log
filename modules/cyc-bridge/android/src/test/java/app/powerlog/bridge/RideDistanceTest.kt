package app.powerlog.bridge

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideDistanceTest {
    @Test
    fun sharedControllerDistanceVectors() {
        val fixture = JSONObject(File("../../../tests/fixtures/distance-controller.json").readText())
        val defaults = fixture.getJSONObject("defaults").map()
        val cases = fixture.getJSONArray("cases")
        RideStore(RuntimeEnvironment.getApplication(), "distance-${UUID.randomUUID()}.sqlite").use { store ->
            val distance = RideDistance(store)
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                val id =
                    store.create(
                        RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                        SystemRecordingClock.read(),
                    )
                val samples = case.getJSONArray("samples")
                store.transaction {
                    for (sampleIndex in 0 until samples.length()) {
                        val sample = defaults + samples.getJSONObject(sampleIndex).map()
                        val profile = CycProtocol.Identity(sample.str("model"), "fixture", sample.str("protocol"))
                        val values =
                            if (profile.knownSpeed) mapOf("controllerSpeedMps" to sample.num("speed")) else emptyMap()
                        val time = sample.num("time")
                        val active = sample.flag("active")
                        val segment = sample.num("continuity").toInt()
                        val identity = sample.str("identity")
                        val epoch = sample.str("connectionEpoch")
                        val row = store.insert(id, time, iso(), "telemetry", active, segment, values, identity, epoch)
                        distance.append(id, row, time, values, active, segment, epoch, identity, false)
                    }
                }
                val total = distance.total(id, "controller")
                assertEquals(name, case.getDouble("distance"), total.first, 1e-9)
                assertEquals(name, case.getDouble("covered"), total.second, 1e-9)
                store.readableDatabase
                    .rawQuery(
                        "SELECT count(*) FROM distance_intervals WHERE ride=?",
                        arrayOf(id),
                    )
                    .use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(name, case.getInt("intervals"), c.getInt(0))
                    }
                case.optJSONObject("range")?.let { range ->
                    val clipped = distance.range(id, "controller", range.getDouble("start"), range.getDouble("end"))
                    assertEquals(name, range.getDouble("distance"), clipped.first, 1e-9)
                    assertEquals(name, range.getDouble("covered"), clipped.second, 1e-9)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun distanceRenderingKeepsShortReconnectsFlatWithoutAddingCoverage() {
        for (gps in listOf(false, true)) for (gap in listOf(2.0, 6.0, 8.0)) {
            RideStore(RuntimeEnvironment.getApplication(), "distance-${UUID.randomUUID()}.sqlite").use { store ->
                val id =
                    store.create(
                        RideOptions(indoor = false, saveToHealth = false, recordGPS = gps),
                        SystemRecordingClock.read(),
                    )
                val distance = RideDistance(store)
                val monitor = RideMonitor(store, distance)
                val source = if (gps) "gps:phone" else "controller"
                store.transaction {
                    listOf(0.0, 1.0, 1 + gap, 2 + gap).forEachIndexed { index, time ->
                        val epoch = if (index < 2) "first" else "second"
                        val values =
                            if (gps)
                                mapOf(
                                    "latitude" to 0.0,
                                    "longitude" to index * 0.00001,
                                    "horizontalAccuracyM" to 3.0,
                                    "speedMps" to 1.0,
                                )
                            else mapOf("controllerSpeedMps" to 10.0)
                        val row =
                            store.insert(
                                id,
                                time,
                                iso(),
                                if (gps) "location" else "telemetry",
                                true,
                                0,
                                values,
                                "bike",
                                epoch,
                            )
                        distance.append(id, row, time, values, true, 0, epoch, "bike", gps)
                    }
                    store.update(id, "running", RideTiming(2 + gap, 2 + gap, iso()))
                }
                fun query(operation: MonitorOperation, vararg args: Pair<String, Any?>): Payload =
                    monitor.query(
                        id,
                        BridgeInputs.monitor(
                            operation,
                            mapOf(
                                "source" to "workout",
                                "id" to id,
                                "generation" to 0,
                                "sinceRevision" to "0",
                                "expectedRevision" to store.revision(id).toString(),
                                "metrics" to listOf("distanceMeters"),
                                "startSeconds" to 0.0,
                                "endSeconds" to 2 + gap,
                                "distanceSource" to source,
                            ) + args,
                        ),
                    )
                for (buckets in listOf(1, 16)) {
                    val points =
                        (query(MonitorOperation.Plot, "buckets" to buckets)["series"] as Map<String, List<Payload>>)
                            .getValue("distanceMeters")
                    assertEquals(
                        "$source gap $gap buckets $buckets",
                        if (gap < 6) 1 else 2,
                        points.count { it.flag("startsSegment") },
                    )
                    if (buckets == 16) {
                        assertEquals(listOf(0.0, 1.0, 1 + gap, 2 + gap), points.map { it.num("elapsedSeconds") })
                        assertEquals(points[1].num("value"), points[2].num("value"), 1e-9)
                    }
                }
                val total = distance.total(id, source)
                val expected = if (gps) 2 * RideDistance.haversine(0.0, 0.0, 0.0, 0.00001) else 20.0
                assertEquals(expected, total.first, 1e-8)
                assertEquals(2.0, total.second, 0.0)
                assertTrue((distance.info(id, source)["selected"] as Payload).flag("partial"))
                val stats =
                    (query(MonitorOperation.Stats)["statistics"] as Map<String, Payload>).getValue("distanceMeters")
                assertEquals(expected, stats.num("distance"), 1e-8)
                assertEquals(2.0, stats.num("coveredSeconds"), 0.0)
                assertEquals(0.0, stats.num("integral"), 0.0)
                val inspection = query(MonitorOperation.Inspect, "seconds" to 1 + gap / 2)["points"] as Payload
                assertNull(inspection["distanceMeters"])
                assertEquals(4L, store.count(id))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun coveredGpsIntervalLongerThanTheDisplayGapStaysConnected() {
        RideStore(RuntimeEnvironment.getApplication(), "distance-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = true),
                    SystemRecordingClock.read(),
                )
            val distance = RideDistance(store)
            val monitor = RideMonitor(store, distance)
            store.transaction {
                listOf(0.0 to 0.0, 1.0 to 0.00001, 9.0 to 0.00005).forEach { (time, longitude) ->
                    val values =
                        mapOf(
                            "latitude" to 0.0,
                            "longitude" to longitude,
                            "horizontalAccuracyM" to 3.0,
                            "speedMps" to 1.0,
                        )
                    val row = store.insert(id, time, iso(), "location", true, 0, values, "bike", "first")
                    distance.append(id, row, time, values, true, 0, "first", "bike", true)
                }
                store.update(id, "running", RideTiming(9.0, 9.0, iso()))
            }
            for (buckets in listOf(1, 16)) {
                val points =
                    (monitor
                            .query(
                                id,
                                BridgeInputs.monitor(
                                    MonitorOperation.Plot,
                                    mapOf(
                                        "source" to "workout",
                                        "id" to id,
                                        "generation" to 0,
                                        "sinceRevision" to "0",
                                        "expectedRevision" to store.revision(id).toString(),
                                        "metrics" to listOf("distanceMeters"),
                                        "startSeconds" to 0.0,
                                        "endSeconds" to 9.0,
                                        "distanceSource" to "gps:phone",
                                        "buckets" to buckets,
                                    ),
                                ),
                            )["series"]
                            as Map<String, List<Payload>>)
                        .getValue("distanceMeters")
                assertEquals("buckets $buckets", 1, points.count { it.flag("startsSegment") })
            }
        }
    }

    @Test
    fun appendRequiresTheOriginalsTransaction() {
        RideStore(RuntimeEnvironment.getApplication(), "distance-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(
                    RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            val distance = RideDistance(store)
            val values = mapOf("controllerSpeedMps" to 10.0)
            assertThrows(IllegalStateException::class.java) {
                distance.append(id, 1L, 0.0, values, true, 0, "fixture", "fixture", false)
            }
            assertEquals(0L, store.count(id))
            assertEquals(0.0 to 0.0, distance.total(id, "controller"))
        }
    }
}

internal fun assertDistanceReplay(original: RideStore, id: String) {
    val originals = mutableListOf<Observation>()
    original.each(id) { originals.add(it) }
    RideStore(RuntimeEnvironment.getApplication(), "replay-${UUID.randomUUID()}.sqlite").use { replay ->
        val target =
            replay.create(
                RideOptions(
                    indoor = original.metadata(id).flag("indoor"),
                    saveToHealth = original.metadata(id).flag("saveToHealth"),
                    recordGPS = original.metadata(id).flag("recordGPS"),
                    sampleHz = original.metadata(id).num("sampleHz").toInt(),
                ),
                SystemRecordingClock.read(),
            )
        val distance = RideDistance(replay)
        replay.transaction {
            replay.writableDatabase.delete("lifecycle", "ride=?", arrayOf(target))
            original.events(id).forEach {
                replay.lifecycle(target, RideTiming(it.time, 0.0, it.timestamp), it.action)
            }
            originals.forEach { row ->
                val values = row.values - setOf("gpsDistanceMeters", "controllerDistanceMeters")
                val inserted =
                    replay.insert(
                        target,
                        row.time,
                        row.timestamp,
                        row.kind,
                        row.active,
                        row.segment,
                        values,
                        row.identity,
                        row.epoch,
                    )
                distance.append(
                    target,
                    inserted,
                    row.time,
                    values,
                    row.active,
                    row.segment,
                    row.epoch,
                    row.identity,
                    row.kind == "location",
                )
            }
            replay.update(target, original.metadata(id).str("phase"), original.timing(id))
        }
        val replayed = mutableListOf<Observation>()
        replay.each(target) { replayed.add(it) }
        assertEquals(
            originals.mapIndexed { i, row -> row.copy(id = i.toLong()) },
            replayed.mapIndexed { i, row -> row.copy(id = i.toLong()) },
        )
        fun intervals(store: RideStore, ride: String, rows: List<Observation>): List<List<Any?>> =
            store.readableDatabase
                .rawQuery(
                    "SELECT source,start,end,meters,cumulative,covered,from_id,to_id,u,v FROM distance_intervals WHERE ride=? ORDER BY source,end",
                    arrayOf(ride),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            listOf(c.getString(0)) +
                                (1..5).map { c.getDouble(it) } +
                                (6..7).map { column -> rows.indexOfFirst { it.id == c.getLong(column) } } +
                                (8..9).map { if (c.isNull(it)) null else c.getDouble(it) }
                        )
                    }
                }
        assertEquals(intervals(original, id, originals), intervals(replay, target, replayed))
        val capturedDistance = RideDistance(original)
        for (source in listOf("auto", "controller", "gps:phone")) {
            assertEquals(capturedDistance.info(id, source), distance.info(target, source))
        }
        val elapsed = original.timing(id).elapsed
        fun statistics(
            store: RideStore,
            ride: String,
            rows: List<Observation>,
            metric: String,
            a: Double,
            b: Double,
        ): Payload =
            store.analytics.stats(ride, metric, a, b).mapValues { (key, value) ->
                if (key in listOf("min", "max") && value is Map<*, *>) {
                    value + ("observationId" to rows.indexOfFirst { it.id.toString() == value["observationId"] })
                } else value
            }
        for ((a, b) in listOf(0.0 to elapsed, 1.5 to 6.5)) {
            assertEquals(capturedDistance.activeSeconds(id, a, b), distance.activeSeconds(target, a, b), 0.0)
            for (source in listOf("controller", "gps:phone")) assertEquals(
                capturedDistance.range(id, source, a, b),
                distance.range(target, source, a, b),
            )
            for (metric in original.available(id)) assertEquals(
                statistics(original, id, originals, metric, a, b),
                statistics(replay, target, replayed, metric, a, b),
            )
        }
    }
}
