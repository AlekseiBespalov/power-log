package app.powerlog.bridge

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
class AndroidAnalyticsTest {
    private fun withRide(body: (RideStore, String) -> Unit) {
        RideStore(RuntimeEnvironment.getApplication(), "analytics-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    RecordingClockReading("2026-01-01T00:00:00.000Z", 0),
                )
            body(store, id)
        }
    }

    private fun row(
        store: RideStore,
        id: String,
        time: Double,
        value: Double?,
        epoch: String = "one",
    ) =
        store.insert(
            id,
            time,
            iso(1767225600000L + (time * 1000).toLong()),
            "telemetry",
            true,
            0,
            value?.let { mapOf("humanPowerW" to it) } ?: mapOf("cadenceRpm" to 80.0),
            epoch = epoch,
        )

    private fun importHeart(
        store: RideStore,
        id: String,
        parent: String,
        origin: String,
        times: List<Long>,
        revision: Long = 1,
    ) {
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.HeartRateRecord(
                    start,
                    java.time.ZoneOffset.UTC,
                    start.plusSeconds(times.last() + 1),
                    java.time.ZoneOffset.UTC,
                    times.map {
                        androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), 100)
                    },
                    HealthMetadata.received(
                        UUID.nameUUIDFromBytes(parent.toByteArray()).toString(),
                        "generated:$parent",
                        revision,
                        null,
                        origin,
                        start.plusSeconds(revision),
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
    }

    @Test
    fun overlappingHealthEnergyIsClippedIntoTheRide() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                    start.minusSeconds(5),
                    java.time.ZoneOffset.UTC,
                    start.plusSeconds(15),
                    java.time.ZoneOffset.UTC,
                    androidx.health.connect.client.units.Energy.kilocalories(20.0),
                    HealthMetadata.received(
                        UUID.randomUUID().toString(),
                        "generated:overlap",
                        1,
                        null,
                        "a.generated",
                        start,
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
        assertEquals(10.0, HealthReadings(store).summary(id)["activeEnergyKcal"])
    }

    @Test
    fun healthConnectDistanceStaysAnOriginalButIsNoDistanceSource() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.DistanceRecord(
                    start,
                    java.time.ZoneOffset.UTC,
                    start.plusSeconds(10),
                    java.time.ZoneOffset.UTC,
                    androidx.health.connect.client.units.Length.meters(25.0),
                    HealthMetadata.received(
                        UUID.randomUUID().toString(),
                        "generated:distance",
                        1,
                        null,
                        "a.generated",
                        start,
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
        val distance = RideDistance(store)
        val summary = RideDetail(store, distance).detail(id, "auto")["summary"] as Map<*, *>
        assertNull(summary["distanceMeters"])
        assertTrue(distance.coreInfo(id, "auto").available.isEmpty())
        val described =
            RideMonitor(store, distance)
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Describe,
                        mapOf("source" to "workout", "id" to id, "generation" to 0),
                    ),
                )
        assertFalse("distanceMeters" in described["availableMetrics"] as List<*>)
        val representations = mutableListOf<Any?>()
        store.healthOriginalRows(id, "cyclingDistance") { representations.add(it.evidence["representation"]) }
        assertEquals(listOf("intervalRecord"), representations)
    }

    @Test
    fun healthCheckpointFlushPreparesOriginSelectionOnceAcrossAllBuckets() = withRide { store, id ->
        store.seal(id, RideTiming(128.0, 128.0, "2026-01-01T00:02:08Z"))
        var preparationPages = 0
        val familyQueries = mutableListOf<String>()
        store.queryObserver = { sql, _ ->
            if ("AS health_selected" in sql && "IN ('rawQuantity','rawSeries')" in sql) familyQueries.add(sql)
            if ("AS health_selected" in sql && "o.kind='health'" in sql && "LIMIT 256" in sql) preparationPages++
        }
        importHeart(store, id, "flush", "a.generated", (0L until 128L).toList())
        store.queryObserver = null
        assertTrue("Origin preparation repeated across buckets: $preparationPages pages", preparationPages <= 10)
        assertTrue(familyQueries.isNotEmpty())
        assertTrue(
            familyQueries.joinToString("\n"),
            familyQueries.all { "o.kind='health'" in it && "LIMIT 128" in it && "o.id>?" in it },
        )
        assertEquals(128, store.analytics.plot(id, "heartRateBpm", 0.0, 128.0, 128).size)
    }

    @Test
    fun originSelectionChangeRebuildsPreviouslyEmptyBuckets() = withRide { store, id ->
        store.seal(id, RideTiming(50.0, 50.0, "2026-01-01T00:00:50Z"))
        importHeart(store, id, "a", "a.generated", listOf(0, 5))
        importHeart(store, id, "b1", "b.generated", listOf(20, 24))
        assertEquals(
            listOf(0.0, 5.0),
            store.analytics.plot(id, "heartRateBpm", 0.0, 50.0, 100).map { it["elapsedSeconds"] },
        )
        importHeart(store, id, "b2", "b.generated", listOf(40, 44))
        assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"])
        assertEquals(
            listOf(20.0, 24.0, 40.0, 44.0),
            store.analytics.plot(id, "heartRateBpm", 0.0, 50.0, 100).map { it["elapsedSeconds"] },
        )
    }

    @Test
    fun healthOriginsSurviveMonitorRequestsAndFollowSummaryCacheIdentity() = withRide { store, id ->
        store.seal(id, RideTiming(601.0, 601.0, "2026-01-01T00:10:01Z"))
        importHeart(store, id, "cached-heart", "a.generated", (0L..600L).toList())
        val monitor = RideMonitor(store, RideDistance(store))
        var pages = 0
        store.queryObserver = { sql, _ ->
            if ("o.kind='health'" in sql && "LIMIT 256" in sql) pages++
        }
        fun query(operation: MonitorOperation): Payload =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    operation,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.revision(id).toString(),
                        "distanceSource" to store.distanceSelection(id).selection,
                        "metrics" to listOf("heartRateBpm"),
                        "seconds" to 100.5,
                    ),
                ),
            )
        fun warm() {
            pages = 0
            repeat(5) {
                for (operation in listOf(MonitorOperation.Latest, MonitorOperation.Inspect)) {
                    val point = (query(operation)["points"] as Map<*, *>)["heartRateBpm"] as Map<*, *>
                    assertEquals(100.0, point["value"])
                }
            }
            assertEquals("Warm HR requests prepared origins again", 0, pages)
        }
        fun cold() {
            pages = 0
            query(MonitorOperation.Latest)
            assertTrue("Changed summary inputs did not invalidate HR origins", pages > 0)
            warm()
        }
        try {
            cold()
            val revision = store.revision(id)
            store.select("SELECT metadata FROM rides WHERE id=?", arrayOf(id)) { c ->
                assertTrue(c.moveToFirst())
                val metadata = JSONObject(c.getString(0)).put("sampleHz", 4)
                store.writableDatabase.execSQL(
                    "UPDATE rides SET metadata=? WHERE id=?",
                    arrayOf(metadata.toString(), id),
                )
            }
            assertEquals(revision, store.revision(id))
            cold()
            store.selectDistance(id, DistanceSource.Controller)
            assertEquals(revision, store.revision(id))
            warm()
            val cutoff = store.timing(id)
            val endedAt = store.metadata(id)["endedAt"]
            store.seal(id, RideTiming(602.0, 602.0, "2026-01-01T00:10:02Z"))
            assertEquals(cutoff, store.timing(id))
            assertEquals(endedAt, store.metadata(id)["endedAt"])
            warm()
            importHeart(store, id, "cached-heart", "a.generated", (0L..599L).toList(), revision = 2)
            warm()
            importHeart(store, id, "new-origin", "b.generated", (0L..602L).toList())
            cold()
            assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"])
        } finally {
            store.queryObserver = null
        }
    }

    @Test
    fun olderOriginPreparationCannotDisplaceTheCurrentMonitorPin() = withRide { store, id ->
        store.seal(id, RideTiming(15.0, 15.0, "2026-01-01T00:00:15Z"))
        importHeart(store, id, "old-origin", "a.generated", listOf(0, 5))
        val older = store.pin(id)
        importHeart(store, id, "current-origin", "b.generated", listOf(0, 5, 10))
        store.withReadPin(id) { assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"]) }
        store.withPin(older) { assertEquals("a.generated", HealthReadings(store).origins(id)["heartRate"]) }
        var pages = 0
        store.queryObserver = { sql, _ ->
            if ("o.kind='health'" in sql && "LIMIT 256" in sql) pages++
        }
        try {
            store.withReadPin(id) { assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"]) }
            assertEquals("Older preparation displaced the current semantic pin", 0, pages)
        } finally {
            store.queryObserver = null
        }
    }

    @Test
    fun rolledBackHealthOriginsCannotEnterTheMonitorCache() = withRide { store, id ->
        store.seal(id, RideTiming(10.0, 10.0, "2026-01-01T00:00:10Z"))
        importHeart(store, id, "committed", "b.generated", listOf(0, 5))
        val revision = store.revision(id)
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                assertEquals(
                    "a.generated",
                    store.healthOrigins(id) { mapOf("heartRate" to "a.generated") }["heartRate"],
                )
                error("Rollback generated Health data")
            }
        }
        assertEquals(revision, store.revision(id))
        store.withReadPin(id) { assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"]) }
    }

    @Test
    fun cancelledOriginPreparationDoesNotPopulateTheMonitorCache() = withRide { store, id ->
        store.seal(id, RideTiming(601.0, 601.0, "2026-01-01T00:10:01Z"))
        importHeart(store, id, "cancelled-heart", "a.generated", (0L..600L).toList())
        store.queryObserver = { sql, _ ->
            if ("o.kind='health'" in sql && "LIMIT 256" in sql) Thread.currentThread().interrupt()
        }
        try {
            assertThrows(IllegalStateException::class.java) {
                store.withReadPin(id) { HealthReadings(store).origins(id) }
            }
        } finally {
            Thread.interrupted()
            store.queryObserver = null
        }
        val selected = mapOf("heartRate" to "a.generated", "activeEnergy" to null)
        try {
            store.withReadPin(id) {
                assertThrows(IllegalStateException::class.java) {
                    store.healthOrigins(id) {
                        HealthReadings(store).selections(id, selected)
                        Thread.currentThread().interrupt()
                        selected
                    }
                }
            }
        } finally {
            Thread.interrupted()
        }
        var pages = 0
        store.queryObserver = { sql, _ ->
            if ("o.kind='health'" in sql && "LIMIT 256" in sql) pages++
        }
        try {
            store.withReadPin(id) { assertEquals("a.generated", HealthReadings(store).origins(id)["heartRate"]) }
            assertTrue("Cancelled origin preparation was cached", pages > 0)
            pages = 0
            store.withReadPin(id) { assertEquals("a.generated", HealthReadings(store).origins(id)["heartRate"]) }
            assertEquals(0, pages)
        } finally {
            store.queryObserver = null
        }
    }

    @Test
    fun corruptedBrokenFlagReplaysContinuityFromOriginals() = withRide { store, id ->
        store.transaction { for (time in listOf(16.0, 17.0, 25.0, 26.0)) row(store, id, time, 100.0) }
        val expected = store.analytics.plot(id, "humanPowerW", 0.0, 64.0, 1)
        assertTrue(expected.single { it.num("elapsedSeconds") == 25.0 }.flag("startsSegment"))
        store.select(
            "SELECT revision,payload FROM analytics WHERE ride=? AND metric='humanPowerW' AND bucket=1 ORDER BY revision DESC LIMIT 1",
            arrayOf(id),
        ) { c ->
            assertTrue(c.moveToFirst())
            val payload = JSONObject(c.getString(1)).put("broken", false)
            store.writableDatabase.execSQL(
                "UPDATE analytics SET payload=? WHERE ride=? AND metric='humanPowerW' AND bucket=1 AND revision=?",
                arrayOf(payload.toString(), id, c.getLong(0)),
            )
        }
        assertEquals(expected, store.analytics.plot(id, "humanPowerW", 0.0, 64.0, 1))
    }

    @Test
    fun clockEpochAndRecoveryBoundariesSplitPlotAndInspection() = withRide { store, id ->
        store.transaction {
            for (i in 0..2) store.insert(
                id,
                i.toDouble(),
                iso(),
                "location",
                true,
                if (i == 2) 1 else 0,
                mapOf("speedMps" to 5.0),
                epoch = "gps",
                evidence =
                    OriginalEvidence(
                        clockEpoch = if (i == 0) "one" else "two",
                        interruptionIndex = if (i == 2) 1 else 0,
                    ),
            )
        }
        store.lifecycle(id, RideTiming(1.0, 1.0, iso()), "pause", true, evidence = mapOf("controllerSequence" to "0"))
        store.lifecycle(id, RideTiming(2.0, 1.0, iso()), "resume")
        store.seal(id, RideTiming(3.0, 3.0, "2026-01-01T00:00:03Z"))
        val points = store.analytics.plot(id, "speedMps", 0.0, 3.0, 100)
        assertEquals(listOf(true, true, true), points.map { it["startsSegment"] })
        for (time in listOf(0.5, 1.5)) assertNull(inspectHealth(store, id, "speedMps", time))
    }

    @Test
    fun widerStatisticsMergeStoredCheckpointsWithoutReplayingInteriorOriginals() = withRide { store, id ->
        store.transaction { for (i in 0 until 384) row(store, id, i / 8.0, 100.0) }
        store.seal(id, RideTiming(48.0, 48.0, "2026-01-01T00:00:48Z"))
        val reads = mutableListOf<String>()
        store.queryObserver = { sql, _ -> reads.add(sql) }
        try {
            assertEquals(384L, store.analytics.stats(id, "humanPowerW", 0.0, 48.0)["count"])
            val originals = reads.filter {
                it.contains("LIMIT 16") && it.contains("ORDER BY elapsed_us,producer,producer_sequence,id")
            }
            assertTrue("Raw page reads=${originals.size}", originals.isNotEmpty() && originals.size <= 10)
        } finally {
            store.queryObserver = null
        }
    }

    @Test
    fun healthPagingUsesElapsedIndexAndAnAdvancingScalarSeek() = withRide { store, id ->
        store.seal(id, RideTiming(601.0, 601.0, "2026-01-01T00:10:01Z"))
        importHeart(store, id, "pages", "a.generated", (0L..600L).toList())
        val queries = mutableListOf<Pair<String, List<Any>>>()
        val originals = mutableListOf<Observation>()
        val expectedSeeks = mutableListOf<Long>()
        store.queryObserver = { sql, args ->
            if (sql.contains("o.kind='health'") && sql.contains("LIMIT 256")) {
                queries.add(sql to args.toList())
                expectedSeeks.add(originals.lastOrNull()?.elapsedUs ?: Long.MIN_VALUE)
            }
        }
        try {
            store.healthOriginalRows(id, "heartRate") { originals.add(it) }
            assertEquals(601, originals.size)
            assertTrue(queries.size >= 4)
            assertTrue(
                queries.joinToString("\n"),
                queries.all {
                    it.first.contains("AS health_selected") &&
                        it.first.contains("o.elapsed_us>=") &&
                        it.first.contains("o.elapsed_us<=")
                },
            )
            assertEquals(expectedSeeks, queries.map { it.second[2] })
            assertTrue(queries[1].second[2] as Long > 0)
            assertTrue(queries.all { it.second[3] == 600000000L })
            store.queryObserver = null
            val plan =
                store
                    .select("EXPLAIN QUERY PLAN " + queries[1].first, queries[1].second.toTypedArray()) { c ->
                        buildList { while (c.moveToNext()) add(c.getString(3)) }
                    }
                    .joinToString("\n")
            assertTrue(plan, plan.contains("ride=? AND <expr>=? AND (elapsed_us,producer,producer_sequence)>"))
            assertFalse(plan, plan.contains("TEMP B-TREE FOR ORDER BY"))
        } finally {
            store.queryObserver = null
        }
    }

    @Test
    fun indexedNeighborsKeepOriginalSubMicrosecondTimesAndUnboundedLatestReads() = withRide { store, id ->
        store.transaction {
            row(store, id, 1.0000001, 100.0)
            row(store, id, 1.0000009, 200.0)
        }
        store.seal(id, RideTiming(2.0, 2.0, "2026-01-01T00:00:02.000Z"))
        val analytics = store.analytics
        assertEquals(1.0000001, analytics.plotNeighbor(id, "humanPowerW", 1.0000005, true)!!.time, 0.0)
        assertEquals(1.0000009, analytics.plotNeighbor(id, "humanPowerW", 1.0000005, false)!!.time, 0.0)
        assertEquals(1.0000009, analytics.plotNeighbor(id, "humanPowerW", Double.MAX_VALUE, true)!!.time, 0.0)
        assertEquals(1.0000001, analytics.plotNeighbor(id, "humanPowerW", -Double.MAX_VALUE, false)!!.time, 0.0)
    }

    @Test
    fun missingReadingsAreBarriersAcrossBucketsAndCorruptCachesRecompute() = withRide { store, id ->
        store.transaction {
            row(store, id, 15.875, 100.0)
            row(store, id, 16.0, null)
            row(store, id, 16.125, 100.0)
        }
        store.seal(id, RideTiming(17.0, 17.0, "2026-01-01T00:00:17.000Z"))
        assertEquals(
            0.0,
            store.analytics.stats(id, "humanPowerW", 0.0, 32.0).num("coveredSeconds"),
            0.0,
        )
        store.writableDatabase.execSQL(
            "UPDATE analytics SET payload='broken' WHERE ride=?",
            arrayOf(id),
        )
        assertEquals(
            0.0,
            store.analytics.stats(id, "humanPowerW", 0.0, 32.0).num("coveredSeconds"),
            0.0,
        )
        assertEquals(2L, store.analytics.stats(id, "humanPowerW", 0.0, 32.0)["count"])
    }

    @Test
    fun checkpointQueriesUseHalfEvenQuantizationAndOwnEachBridgeOnce() = withRide { store, id ->
        store.transaction {
            for (time in listOf(15.875, 16.0, 16.125)) row(store, id, time, 0.0625)
        }
        store.seal(id, RideTiming(17.0, 17.0, "2026-01-01T00:00:17.000Z"))
        val wide = store.analytics.stats(id, "humanPowerW", 0.0, 32.0)
        val narrow = store.analytics.stats(id, "humanPowerW", 15.875, 16.125)
        assertEquals(0.0155, wide.num("integral"), 0.0)
        assertEquals(wide, narrow)
    }

    @Test
    fun categoricalStatisticsOmitIntegralsAndMeans() = withRide { store, id ->
        store.transaction {
            for (time in listOf(0.0, 1.0)) store.insert(
                id,
                time,
                iso(),
                "telemetry",
                true,
                0,
                mapOf("assistLevel" to 2.0),
            )
        }
        val stats = store.analytics.stats(id, "assistLevel", 0.0, 2.0)
        assertEquals(2L, stats["count"])
        assertFalse(stats.containsKey("sampleMean"))
        assertFalse(stats.containsKey("integral"))
        assertFalse(stats.containsKey("coveredSeconds"))
    }

    @Test
    fun equalTimeExtremaUseOriginalIdentityInsteadOfDestinationRowOrder() = withRide { store, id ->
        val ids = listOf("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111")
        val rows = store.transaction {
            ids.mapIndexed { index, event ->
                store.insert(
                    id,
                    16.0,
                    "2026-01-01T00:00:16Z",
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to 100.0),
                    original = OriginalIdentity(event, "controller", index + 1L),
                )
            }
        }
        val stats = store.analytics.stats(id, "humanPowerW", 0.0, 32.0)
        for (key in listOf("min", "max")) assertEquals(rows[1].toString(), (stats[key] as Map<*, *>)["observationId"])
        store.writableDatabase.execSQL("UPDATE analytics SET payload='broken' WHERE ride=?", arrayOf(id))
        assertEquals(stats, store.analytics.stats(id, "humanPowerW", 0.0, 32.0))
    }

    @Test
    fun exactAnchorsRetainOriginalTimeAndRejectMismatchedTimes() = withRide { store, id ->
        val row = store.transaction { row(store, id, 1.0, 123.0) }
        val monitor = RideMonitor(store, RideDistance(store))
        fun inspect(time: Double) =
            monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Inspect,
                        mapOf(
                            "source" to "workout",
                            "id" to id,
                            "generation" to 0,
                            "expectedRevision" to store.revision(id).toString(),
                            "metrics" to listOf("humanPowerW"),
                            "seconds" to time,
                            "anchor" to mapOf("metric" to "humanPowerW", "observationId" to row.toString()),
                        ),
                    ),
                )["points"]
                as Map<*, *>
        val point = inspect(1.0000005)["humanPowerW"] as Map<*, *>
        assertEquals(1.0, point["elapsedSeconds"])
        assertEquals(row.toString(), point["observationId"])
        assertNull(inspect(1.000002)["humanPowerW"])
        assertNull(inspect(1.5)["humanPowerW"])
    }

    @Test
    fun corruptedPlotPointsRecomputeFromOriginals() = withRide { store, id ->
        store.transaction { for (time in listOf(15.875, 16.0, 16.125)) row(store, id, time, 100.0) }
        val expected = store.analytics.plot(id, "humanPowerW", 0.0, 48.0, 1)
        store.select(
            "SELECT revision,payload FROM analytics WHERE ride=? AND metric='humanPowerW' AND bucket=1 ORDER BY revision DESC LIMIT 1",
            arrayOf(id),
        ) { c ->
            assertTrue(c.moveToFirst())
            val payload = JSONObject(c.getString(1))
            payload.getJSONObject("plot").put("state", "broken")
            store.writableDatabase.execSQL(
                "UPDATE analytics SET payload=? WHERE ride=? AND metric='humanPowerW' AND bucket=1 AND revision=?",
                arrayOf(payload.toString(), id, c.getLong(0)),
            )
        }
        assertEquals(expected, store.analytics.plot(id, "humanPowerW", 0.0, 48.0, 1))
    }

    @Test
    fun detailAndMonitorUseTheSelectedHealthOrigin() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(10.0, 10.0, "2026-01-01T00:00:10Z"))
        val readback = HealthReadback(store, FakeHealthConnectAccess())
        fun heart(parent: String, origin: String, end: Long, value: Long) =
            androidx.health.connect.client.records.HeartRateRecord(
                start,
                java.time.ZoneOffset.UTC,
                start.plusSeconds(end),
                java.time.ZoneOffset.UTC,
                listOf(1L, 2L, 3L).map {
                    androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), value)
                },
                HealthMetadata.received(
                    parent,
                    "generated:heart",
                    1,
                    null,
                    origin,
                    start.plusSeconds(1),
                    androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                ),
            )
        readback.import(id, heart("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "a.generated", 10, 100))
        readback.import(id, heart("99999999-8888-4777-8666-555555555555", "b.generated", 5, 200))
        val distance = RideDistance(store)
        val summary = RideDetail(store, distance).detail(id, "auto")["summary"] as Map<*, *>
        assertEquals(100.0, summary["averageHeartRateBpm"])
        assertEquals(100.0, summary["maximumHeartRateBpm"])
        assertEquals("a.generated", (summary["healthOrigins"] as Map<*, *>)["heartRate"])
        store.withReadPin(id) {
            assertEquals(
                2000000L,
                HealthReadings(store).selections(id).single { it.metric == "heartRateBpm" }.unionCoverageUs,
            )
            val statistics = store.analytics.stats(id, "heartRateBpm", 0.0, 10.0)
            assertEquals(3L, statistics["count"])
            assertEquals(100.0, statistics.num("sampleMean"), 0.0)
            val max = statistics["max"] as Map<*, *>
            assertEquals(100.0, max["value"])
        }
        assertEquals(6, store.page(id).count { it.values.containsKey("heartRateBpm") })
    }

    @Test
    fun unrelatedHealthSeriesDoNotBreakHeartRateSummaryContinuity() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(2.0, 2.0, start.plusSeconds(2).toString()))
        fun metadata(kind: String) =
            HealthMetadata.received(
                UUID.nameUUIDFromBytes("mixed:$kind".toByteArray()).toString(),
                "generated:$kind",
                1,
                null,
                "a.generated",
                start.plusSeconds(2),
                androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
            )
        val readback = HealthReadback(store, FakeHealthConnectAccess())
        readback.import(
            id,
            androidx.health.connect.client.records.HeartRateRecord(
                start,
                java.time.ZoneOffset.UTC,
                start.plusSeconds(2),
                java.time.ZoneOffset.UTC,
                listOf(
                    androidx.health.connect.client.records.HeartRateRecord.Sample(start, 100),
                    androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(1), 120),
                ),
                metadata("heart"),
            ),
        )
        readback.import(
            id,
            androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                start,
                java.time.ZoneOffset.UTC,
                start.plusSeconds(2),
                java.time.ZoneOffset.UTC,
                androidx.health.connect.client.units.Energy.kilocalories(10.0),
                metadata("active"),
            ),
        )
        readback.import(
            id,
            androidx.health.connect.client.records.PowerRecord(
                start,
                java.time.ZoneOffset.UTC,
                start.plusSeconds(2),
                java.time.ZoneOffset.UTC,
                listOf(
                    androidx.health.connect.client.records.PowerRecord.Sample(
                        start,
                        androidx.health.connect.client.units.Power.watts(150.0),
                    ),
                    androidx.health.connect.client.records.PowerRecord.Sample(
                        start.plusSeconds(1),
                        androidx.health.connect.client.units.Power.watts(150.0),
                    ),
                ),
                metadata("power"),
            ),
        )
        val summary = HealthReadings(store).summary(id)
        assertEquals(110.0, summary.num("averageHeartRateBpm"), 0.0)
        assertEquals(1.0, summary.num("heartRateCoveredSeconds"), 0.0)
        assertNull(summary["basalEnergyKcal"])
    }

    private fun inspectHealth(store: RideStore, id: String, metric: String, time: Double): Any? {
        val distance = RideDistance(store)
        return (RideMonitor(store, distance)
            .query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Inspect,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.revision(id).toString(),
                        "metrics" to listOf(metric),
                        "seconds" to time,
                    ),
                ),
            )["points"]
            as Map<*, *>)[metric]
    }

    @Test
    fun healthHeartRatePlotJoinsThirtySecondsWithoutExtendingIntegration() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(70.0, 70.0, start.plusSeconds(70).toString()))
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.HeartRateRecord(
                    start,
                    java.time.ZoneOffset.UTC,
                    start.plusSeconds(70),
                    java.time.ZoneOffset.UTC,
                    listOf(0L, 12L, 27L, 60L).map {
                        androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(it), 100)
                    },
                    HealthMetadata.received(
                        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                        "generated:heart",
                        1,
                        null,
                        "a.generated",
                        start.plusSeconds(70),
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
        val points = store.analytics.plot(id, "heartRateBpm", 0.0, 70.0, 70)
        assertEquals(listOf(true, false, false, true), points.map { it["startsSegment"] })
        assertEquals(0.0, (inspectHealth(store, id, "heartRateBpm", 6.0) as Map<*, *>)["elapsedSeconds"])
        assertEquals(27.0, (inspectHealth(store, id, "heartRateBpm", 20.0) as Map<*, *>)["elapsedSeconds"])
        assertNull(inspectHealth(store, id, "heartRateBpm", 45.0))
        assertEquals(0.0, store.analytics.stats(id, "heartRateBpm", 0.0, 70.0).num("coveredSeconds"), 0.0)
    }

    @Test
    fun healthEnergyDisplayUsesTheIphoneCumulativeGap() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.seal(id, RideTiming(221.0, 221.0, start.plusSeconds(221).toString()))
        val readback = HealthReadback(store, FakeHealthConnectAccess())
        for (second in listOf(0L, 100L, 220L)) readback.import(
            id,
            androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                start.plusSeconds(second),
                java.time.ZoneOffset.UTC,
                start.plusSeconds(second + 1),
                java.time.ZoneOffset.UTC,
                androidx.health.connect.client.units.Energy.kilocalories(10.0),
                HealthMetadata.received(
                    UUID.nameUUIDFromBytes("energy:$second".toByteArray()).toString(),
                    "generated:energy:$second",
                    1,
                    null,
                    "a.generated",
                    start.plusSeconds(221),
                    androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                ),
            ),
        )
        val points = store.analytics.plot(id, "activeEnergyKcal", 0.0, 221.0, 221)
        assertEquals(listOf(true, false, true), points.map { it["startsSegment"] })
        assertEquals(0.0, (inspectHealth(store, id, "activeEnergyKcal", 50.0) as Map<*, *>)["elapsedSeconds"])
        assertNull(inspectHealth(store, id, "activeEnergyKcal", 160.0))
    }

    @Test
    fun healthPagesRetainEqualTimeMembersAndCancellationStopsTheNextPage() = withRide { store, id ->
        val start = java.time.Instant.parse("2026-01-01T00:00:00.000Z")
        store.seal(id, RideTiming(2.0, 2.0, start.plusSeconds(2).toString()))
        HealthReadback(store, FakeHealthConnectAccess())
            .import(
                id,
                androidx.health.connect.client.records.HeartRateRecord(
                    start,
                    java.time.ZoneOffset.UTC,
                    start.plusSeconds(2),
                    java.time.ZoneOffset.UTC,
                    List(601) {
                        androidx.health.connect.client.records.HeartRateRecord.Sample(start.plusSeconds(1), 100)
                    },
                    HealthMetadata.received(
                        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                        "generated:heart",
                        1,
                        null,
                        "a.generated",
                        start.plusSeconds(1),
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    ),
                ),
            )
        val identities = mutableListOf<String>()
        store.healthOriginalRows(id, "heartRate") { identities.add(requireNotNull(it.original).eventId) }
        assertEquals(601, identities.size)
        assertEquals(601, identities.distinct().size)
        var visited = 0
        try {
            assertThrows(IllegalStateException::class.java) {
                store.withReadPin(id) {
                    store.healthOriginalRows(id, "heartRate") {
                        visited++
                        if (visited == 1) Thread.currentThread().interrupt()
                    }
                }
            }
            assertEquals(16, visited)
        } finally {
            Thread.interrupted()
        }
        store.remove(id)
    }

    @Test
    fun overflowStateSurvivesDurableCheckpointRoundTrip() = withRide { store, id ->
        store.transaction {
            store.insert(
                id,
                16.0,
                "2026-01-01T00:00:16.000Z",
                "telemetry",
                true,
                0,
                mapOf("motorInputPowerW" to 1e16),
            )
        }
        store.seal(id, RideTiming(32.0, 32.0, "2026-01-01T00:00:32.000Z"))
        val checkpoint =
            store.select(
                "SELECT payload FROM analytics WHERE ride=? AND metric='motorInputPowerW' AND bucket=1 ORDER BY revision DESC LIMIT 1",
                arrayOf(id),
            ) { c ->
                assertTrue(c.moveToFirst())
                MetricCache.decode(
                    JSONObject(c.getString(0)),
                    "motorInputPowerW",
                    app.powerlog.core.analytics.AnalysisRange(16000000, 32000000),
                )
            }
        assertEquals(
            setOf(app.powerlog.core.analytics.Quantity.sumQ),
            checkpoint.overflowQuantities,
        )
        assertEquals(
            checkpoint,
            MetricCache.decode(
                json(mapOf("checkpoint" to MetricCache.encode(checkpoint))),
                "motorInputPowerW",
                checkpoint.range,
            ),
        )
        val error =
            assertThrows(expo.modules.kotlin.exception.CodedException::class.java) {
                store.analytics.stats(id, "motorInputPowerW", 0.0, 32.0)
            }
        assertEquals("ERR_NUMERIC_OVERFLOW", error.code)
        store.writableDatabase.execSQL(
            "UPDATE analytics SET payload='broken' WHERE ride=?",
            arrayOf(id),
        )
        assertEquals(
            "ERR_NUMERIC_OVERFLOW",
            assertThrows(expo.modules.kotlin.exception.CodedException::class.java) {
                    store.analytics.stats(id, "motorInputPowerW", 0.0, 32.0)
                }
                .code,
        )
    }
}
