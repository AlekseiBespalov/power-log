package app.powerlog.bridge

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class StoredModelTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private fun store() = RideStore(context, "stored-${UUID.randomUUID()}.sqlite")

    @Test
    fun healthProjectionNeverEntersStoredRideOrCheckpointRecords() =
        store().use { store ->
            val id =
                store.create(
                    RideOptions(false, saveToHealth = false, recordGPS = false),
                    RecordingClockReading("2026-01-01T00:00:00.000Z", 0),
                )
            store.seal(id, RideTiming(12.0, 8.0, "2026-01-01T00:00:12.000Z"))
            assertEquals("notRequested", store.metadata(id)["healthKitState"])
            for (sql in
                listOf("SELECT metadata FROM rides WHERE id=?", "SELECT record FROM ride_versions WHERE ride=?")) {
                store.select(sql, arrayOf(id)) { c ->
                    assertTrue(c.moveToFirst())
                    val record = JSONObject(c.getString(0))
                    for (field in
                        listOf(
                            "healthKitState",
                            "healthExport",
                            "healthReason",
                            "componentResults",
                            "exportEligible",
                        )) assertFalse(
                        field,
                        record.has(field),
                    )
                    assertTrue(record.has("healthComponents"))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.update(id, "completed", store.timing(id), mapOf("healthKitState" to "saved"))
            }
            val pin = store.pin(id)
            assertThrows(IllegalStateException::class.java) { store.deleteHistorical(id, store.lifecycleState()) }
            store.withPin(pin) { assertEquals("notRequested", store.metadata(id)["healthKitState"]) }
            store.retain(id)
            assertThrows(IllegalStateException::class.java) { store.deleteHistorical(id, store.lifecycleState()) }
            store.release(id)
            store.deleteHistorical(id, store.lifecycleState())
            assertFalse(store.exists(id))
        }

    private fun interleavedTransaction(commit: Boolean) =
        store().use { store ->
            val id = ride(store)
            val pool = Executors.newFixedThreadPool(2)
            val ended = CountDownLatch(1)
            val registered = CountDownLatch(1)
            val cleaned = CountDownLatch(1)
            val pinThread = AtomicReference<Thread>()
            val rolledBack = AtomicBoolean()
            store.afterTransactionEnd = {
                if (Thread.currentThread() == pinThread.get()) {
                    ended.countDown()
                    check(registered.await(15, TimeUnit.SECONDS))
                }
            }
            try {
                val first =
                    pool.submit<RideStore.ReadPin> {
                        pinThread.set(Thread.currentThread())
                        try {
                            store.pin(id)
                        } finally {
                            cleaned.countDown()
                        }
                    }
                val second = pool.submit {
                    check(ended.await(15, TimeUnit.SECONDS))
                    try {
                        store.transaction {
                            store.insert(id, 1.0, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 100.0))
                            store.onRollback { rolledBack.set(true) }
                            registered.countDown()
                            check(cleaned.await(15, TimeUnit.SECONDS))
                            if (!commit) throw IllegalStateException("injected rollback")
                        }
                    } catch (error: IllegalStateException) {
                        if (commit || error.message != "injected rollback") throw error
                    }
                }
                val pin = first.get(20, TimeUnit.SECONDS)
                second.get(20, TimeUnit.SECONDS)
                store.withPin(pin) { assertEquals(emptyList<Observation>(), store.page(id)) }
                if (commit) {
                    assertEquals(1, store.page(id).size)
                    store.select("SELECT count(*) FROM analytics WHERE ride=? AND metric='humanPowerW'", arrayOf(id)) {
                        c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(2, c.getInt(0))
                    }
                    store.select(
                        "SELECT record FROM ride_versions WHERE ride=? AND revision=?",
                        arrayOf(id, store.revision(id)),
                    ) { c ->
                        assertTrue("The committed writer must retain its ride version.", c.moveToFirst())
                        assertEquals(
                            store.revision(id).toString(),
                            JSONObject(c.getString(0)).getString("collectionRevision"),
                        )
                    }
                    assertFalse(rolledBack.get())
                } else {
                    assertEquals(emptyList<Observation>(), store.page(id))
                    assertTrue("The failing writer must keep its rollback callback.", rolledBack.get())
                }
            } finally {
                store.afterTransactionEnd = null
                registered.countDown()
                cleaned.countDown()
                ended.countDown()
                pool.shutdownNow()
                check(pool.awaitTermination(20, TimeUnit.SECONDS))
            }
        }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun transactionCleanupPreservesAnotherWritersCheckpointsAndVersion() = interleavedTransaction(true)

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun transactionCleanupPreservesAnotherWritersRollbackCallbacks() = interleavedTransaction(false)

    private fun stageHealth(
        store: RideStore,
        id: String,
        h: HealthOriginal,
        stagedUnit: String = h.unit,
    ): HealthOriginal = h

    private fun heartRate(store: RideStore, id: String): HealthOriginal {
        val identity = store.nextOriginal(id, "phone")
        return HealthOriginal(
            Origin(
                "healthConnect",
                "app.synthetic.sensor",
                UUID.randomUUID().toString(),
                identity.eventId,
                "phone",
                identity.sequence,
            ),
            "heartRate",
            "bpm",
            "rawSeries",
            "2026-01-01T00:00:01.123456789Z",
            "2026-01-01T00:00:02.123456789Z",
            1000000,
            2000000,
            100.0,
            Association.RideWindow(0, 3000000),
        )
    }

    @Test
    fun healthAnalyticalUnitsMustBeDeclaredAndOriginalEvidenceStaysVerbatim() =
        store().use { store ->
            val id = ride(store)
            val base = heartRate(store, id)
            for ((measure, unit) in
                listOf(
                    "heartRate" to "kcal",
                    "heartRate" to "count/min",
                    "activeEnergy" to "cal",
                    "totalEnergy" to "cal",
                )) {
                assertThrows(IllegalArgumentException::class.java) {
                    base.copy(measure = measure, unit = unit, value = 1000.0).readings()
                }
            }
            for ((measure, unit, metric) in
                listOf(
                    Triple("heartRate", "bpm", "heartRateBpm"),
                    Triple("activeEnergy", "kcal", "activeEnergyKcal"),
                )) {
                val raw = heartRate(store, id).copy(measure = measure, unit = unit, value = 1000.0)
                val h = stageHealth(store, id, raw)
                store.appendHealthPage(id, listOf(h), HealthQueryProgress("healthConnect", measure, "window", null))
                val row = store.page(id).last()
                assertEquals(1000.0, row.values.getValue(metric), 0.0)
                assertEquals(1000.0, (row.evidence["value"] as Number).toDouble(), 0.0)
                assertEquals(unit, row.evidence["unit"])
            }
        }

    private fun ride(store: RideStore, hz: Int = 8) =
        store.create(
            RideOptions(indoor = false, saveToHealth = false, recordGPS = true, sampleHz = hz),
            RecordingClockReading("2026-01-01T00:00:00.123456789Z", 1000),
        )

    @Test
    fun admissionFixtureOpensOnlyTheRequiredVersionAndPreservesRejectedRows() {
        val fixture = JSONObject(contractFixture("storage/store-admission.json").readText())
        val platform =
            fixture.getJSONArray("platforms").let { a ->
                (0 until a.length()).map(a::getJSONObject).single { it.getString("platform") == "android" }
            }
        val required = platform.getInt("requiredMarker")
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (case.has("onlyPlatform") || case.has("buildMarker")) continue
            val name = "admission-${UUID.randomUUID()}.sqlite"
            context.getDatabasePath(name).parentFile!!.mkdirs()
            val marker =
                when (case.getString("storedMarker")) {
                    "zero" -> 0
                    "required" -> required
                    "previous" -> platform.getInt("previousMarker")
                    else -> required + 1
                }
            if (case.getBoolean("hasApplicationTables")) {
                if (marker == required) RideStore(context, name).use { it.writableDatabase }
                SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                    db.execSQL("CREATE TABLE retained(value TEXT)")
                    db.execSQL("INSERT INTO retained VALUES('original')")
                    db.version = marker
                }
            }
            RideStore(context, name).use { store ->
                if (case.getJSONObject("expected").getString("outcome") == "reject") {
                    assertThrows(IllegalStateException::class.java) { store.writableDatabase }
                } else assertEquals(required, store.writableDatabase.version)
            }
            if (case.getBoolean("hasApplicationTables"))
                SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
                    .use { db ->
                        assertEquals(marker, db.version)
                        db.rawQuery("SELECT value FROM retained", null).use { c ->
                            assertTrue(c.moveToFirst())
                            assertEquals("original", c.getString(0))
                        }
                    }
        }
    }

    @Test
    fun unversionedApplicationTablesAreNotANewStore() {
        val name = "unknown-${UUID.randomUUID()}.sqlite"
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE originals(value TEXT)")
        }
        RideStore(context, name).use { assertThrows(IllegalStateException::class.java) { it.writableDatabase } }
    }

    @Test
    fun metadataDoesNotPersistReadProjectionsOrADuplicatePhase() =
        store().use { store ->
            val id = ride(store)
            store.insert(id, 1.0, "2026-01-01T00:00:01Z", "telemetry", true, 0, mapOf("humanPowerW" to 0.0))
            val at = RideTiming(1.0, 1.0, "2026-01-01T00:00:01Z")
            assertThrows(IllegalArgumentException::class.java) { store.update(id, "paused", at, store.metadata(id)) }
            assertEquals("running", store.metadata(id)["phase"])
            store.update(id, "paused", at)
            store.readableDatabase.rawQuery("SELECT metadata FROM rides WHERE id=?", arrayOf(id)).use { c ->
                assertTrue(c.moveToFirst())
                val data = JSONObject(c.getString(0))
                for (key in
                    listOf("phase", "collectionRevision", "elapsedSeconds", "eventCount", "healthReason")) assertFalse(
                    key,
                    data.has(key),
                )
            }
            assertEquals("paused", store.metadata(id)["phase"])
            assertEquals("2", store.metadata(id)["eventCount"])
        }

    @Test
    fun invalidAndConflictingStoredRatesAreRefused() =
        store().use { store ->
            assertThrows(IllegalArgumentException::class.java) { ride(store, 3) }
            val id = ride(store)
            assertThrows(IllegalArgumentException::class.java) {
                store.update(id, "running", store.timing(id), mapOf("sampleHz" to 4))
            }
            store.writableDatabase.execSQL(
                "UPDATE rides SET metadata=json_remove(metadata,'$.sampleHz') WHERE id=?",
                arrayOf(id),
            )
            assertThrows(IllegalArgumentException::class.java) { store.metadata(id) }
            Unit
        }

    @Test
    fun distanceColumnsLeaveOriginalsAndRollbackRestoresThePriorEndpoint() =
        store().use { store ->
            val id = ride(store)
            val distance = RideDistance(store)
            val values = mapOf("controllerSpeedMps" to 3.0)
            fun append(t: Double) = store.transaction {
                val row =
                    store.insert(
                        id,
                        t,
                        iso(1767225600000L + (t * 1000).toLong()),
                        "telemetry",
                        true,
                        0,
                        values,
                        "X6|test|5.3",
                        "epoch",
                    )
                distance.append(id, row, t, values, true, 0, "epoch", "X6|test|5.3", false)
            }
            append(0.0)
            assertThrows(IllegalStateException::class.java) {
                store.transaction {
                    append(1.0)
                    error("rollback")
                }
            }
            append(1.0)
            assertEquals(3.0, distance.total(id, "controller").first, 0.0)
            val columns =
                store.readableDatabase.rawQuery("PRAGMA table_info(observations)", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(1)) }
                }
            assertFalse(columns.contains("gpsDistanceMeters"))
            assertFalse(columns.contains("controllerDistanceMeters"))
            assertEquals(2L, store.count(id, "telemetry"))
        }

    @Test
    fun producerPositionsAreSharedAcrossKindsAndReplaysDoNotAdvanceCounts() =
        store().use { store ->
            val id = ride(store)
            val original = store.nextOriginal(id, "phone")
            val values = mapOf("speedMps" to 0.0)
            val row =
                store.insert(
                    id,
                    1.0,
                    "2026-01-01T00:00:01.123456789Z",
                    "location",
                    true,
                    0,
                    values,
                    original = original,
                )
            val revision = store.revision(id)
            assertEquals(
                row,
                store.insert(
                    id,
                    1.0,
                    "2026-01-01T00:00:01.123456789Z",
                    "location",
                    true,
                    0,
                    values,
                    original = original,
                ),
            )
            assertEquals(revision, store.revision(id))
            assertEquals(2L, store.originalMembershipCount(id))
            assertEquals(original.sequence, store.producerHighWater(id, "phone"))
            assertThrows(IllegalArgumentException::class.java) {
                store.lifecycle(id, store.timing(id), "lap", original = original)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.insert(id, 1.0, "2026-01-01T00:00:01Z", "location", true, 0, values, original = original)
            }
            Unit
        }

    @Test
    fun exactIndexFixtureKeepsIntegerOrderAndOriginalTimestamps() =
        store().use { store ->
            val id = ride(store)
            val fixture = JSONObject(contractFixture("storage/exact-index-keys.json").readText())
            val range = fixture.getJSONObject("range")
            val baseUs = timeUs("2026-01-01T00:00:00Z")
            val inputs = range.getJSONArray("insertionOrderUs")
            for (i in 0 until inputs.length()) {
                val key = inputs.getString(i).toLong()
                val timestamp = java.time.Instant.ofEpochSecond(baseUs / 1000000, key * 1000).toString()
                store.insert(id, key / 1000000.0, timestamp, "health", true, 0, mapOf("heartRateBpm" to 100.0))
            }
            val result =
                store.select(
                    "SELECT query_us,timestamp FROM observations WHERE ride=? AND query_us>=? AND query_us<=? ORDER BY query_us",
                    arrayOf(
                        id,
                        baseUs + range.getString("lowerUsInclusive").toLong(),
                        baseUs + range.getString("upperUsInclusive").toLong(),
                    ),
                ) { c ->
                    buildList {
                        while (c.moveToNext()) {
                            assertEquals(c.getLong(0), timeUs(c.getString(1)))
                            add((c.getLong(0) - baseUs).toString())
                        }
                    }
                }
            assertEquals(listOf("2", "10"), result)
            assertEquals(baseUs + 125000L, store.page(id).first().queryUs)
            val sequenceInputs = fixture.getJSONObject("producerOrder").getJSONArray("insertionOrder")
            for (i in 0 until sequenceInputs.length()) store.insert(
                id,
                1.0,
                "2026-01-01T00:00:01Z",
                "telemetry",
                true,
                0,
                mapOf("humanPowerW" to 0.0),
                original =
                    OriginalIdentity(UUID.randomUUID().toString(), "controller", sequenceInputs.getString(i).toLong()),
            )
            val sequences =
                store.select(
                    "SELECT producer_sequence FROM observations WHERE ride=? AND producer='controller' ORDER BY producer_sequence",
                    arrayOf(id),
                ) { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
            assertEquals(listOf(2L, 10L, 130L), sequences)
        }

    @Test
    fun supportedZeroAvailabilityUsesTheStableBitWord() =
        store().use { store ->
            val id = ride(store)
            val distance = RideDistance(store)
            store.insert(
                id,
                0.0,
                "2026-01-01T00:00:00Z",
                "telemetry",
                true,
                0,
                mapOf("humanPowerW" to 0.0, "cadenceRpm" to 80.0),
            )
            val values = mapOf("latitude" to 0.0, "longitude" to 0.0, "horizontalAccuracyM" to 2.0, "speedMps" to 0.0)
            for (time in 0..1) store.transaction {
                val row = store.insert(id, time.toDouble(), "2026-01-01T00:00:0${time}Z", "location", true, 0, values)
                distance.append(id, row, time.toDouble(), values, true, 0, "gps", "phone", true)
            }
            store.insert(id, 0.0, "2026-01-01T00:00:00Z", "health", true, 0, mapOf("heartRateBpm" to 100.0))
            assertEquals(362020867L, availabilityBits(store.available(id).reversed()))
            assertTrue(store.available(id).contains("gpsDistanceMeters"))
            assertFalse(store.available(id).contains("controllerDistanceMeters"))
            assertEquals(5L, store.originalMembershipCount(id))
        }

    @Test
    fun appendCompletesAnOlderPinWithoutOverwritingItsCheckpoints() =
        store().use { store ->
            val id = ride(store)
            for (time in 0..1) store.insert(
                id,
                time.toDouble(),
                "2026-01-01T00:00:0${time}Z",
                "telemetry",
                true,
                0,
                mapOf("humanPowerW" to 100.0),
            )
            val pin = store.pin(id)
            store.withPin(pin) {
                store.insert(id, 2.0, "2026-01-01T00:00:02Z", "telemetry", true, 0, mapOf("humanPowerW" to 100.0))
                assertEquals(2, store.page(id).size)
                assertEquals(100.0, store.analytics.stats(id, "humanPowerW", 0.0, 2.0).num("integral"), 0.0)
                assertEquals("3", store.metadata(id)["eventCount"])
            }
            assertEquals(200.0, store.analytics.stats(id, "humanPowerW", 0.0, 2.0).num("integral"), 0.0)
            assertEquals("4", store.metadata(id)["eventCount"])
        }

    @Test
    fun healthOriginsCorrectionSelectionAndProgressCommitTogether() =
        store().use { store ->
            val id = ride(store)
            val original = store.nextOriginal(id, "phone")
            val origin =
                Origin(
                    "healthConnect",
                    "app.synthetic.sensor",
                    UUID.randomUUID().toString(),
                    original.eventId,
                    "phone",
                    original.sequence,
                    "1",
                )
            val raw =
                HealthOriginal(
                    origin,
                    "heartRate",
                    "bpm",
                    "rawSeries",
                    "2026-01-01T00:00:01.123457789Z",
                    "2026-01-01T00:00:01.123457789Z",
                    1000001,
                    1000001,
                    100.0,
                    Association.RideWindow(0, 2000000),
                    0,
                )
            val h = stageHealth(store, id, raw)
            val progress = HealthQueryProgress("healthConnect", "heartRate", "window:0:2000000", "page-1", 1)
            store.appendHealthPage(id, listOf(h), progress)
            var revision = store.revision(id)
            store.appendHealthPage(id, listOf(h), progress)
            assertEquals(revision, store.revision(id))
            assertEquals(
                "app.synthetic.sensor",
                (store.page(id).single().evidence["origin"] as Map<*, *>)["dataOrigin"],
            )
            assertEquals("page-1", store.healthProgress(id, progress)?.get("cursor"))
            store.update(id, "running", RideTiming(2.0, 2.0, "2026-01-01T00:00:02.123456789Z"))
            revision = store.revision(id)
            val pin = store.pin(id)
            val tombstoneId = store.nextOriginal(id, "phone")
            val tombstone =
                h.copy(
                    origin = origin.copy(originEventId = tombstoneId.eventId, sequence = tombstoneId.sequence),
                    measure = "tombstone",
                    representation = "healthTombstone",
                    value = null,
                    targetEventId = h.origin.originEventId,
                    logicalKey = null,
                    memberIndex = null,
                )
            val change = HealthChange(h.origin.originEventId, tombstoneId.eventId, deleted = true)
            val db = store.writableDatabase
            db.execSQL(
                "CREATE TEMP TRIGGER fail_progress BEFORE INSERT ON health_query_progress BEGIN SELECT RAISE(ABORT,'progress failure'); END"
            )
            try {
                assertThrows(SQLiteException::class.java) {
                    store.appendHealthPage(id, listOf(tombstone), progress.copy(cursor = "page-2"), listOf(change))
                }
            } finally {
                db.execSQL("DROP TRIGGER fail_progress")
            }
            assertEquals(revision, store.revision(id))
            assertTrue(store.available(id).contains("heartRateBpm"))
            assertEquals("page-1", store.healthProgress(id, progress)?.get("cursor"))
            store.appendHealthPage(id, listOf(tombstone), progress.copy(cursor = "page-2"), listOf(change))
            assertFalse(store.available(id).contains("heartRateBpm"))
            assertEquals(1L, store.selectedCount(id, "health"))
            assertEquals(0.0, store.analytics.stats(id, "heartRateBpm", 0.0, 2.0).num("count"), 0.0)
            store.withPin(pin) {
                assertEquals(1L, store.selectedCount(id, "health"))
                assertTrue(store.available(id).contains("heartRateBpm"))
                assertEquals(1.0, store.analytics.stats(id, "heartRateBpm", 0.0, 2.0).num("count"), 0.0)
            }
            val changedRevision = store.revision(id)
            store.appendHealthPage(id, listOf(tombstone), progress.copy(cursor = "page-2"), listOf(change))
            assertEquals(changedRevision, store.revision(id))
            assertEquals(3L, store.originalMembershipCount(id))
        }

    @Test
    fun timestampFixtureRetainsSpellingAndRejectsBeforeDurableMutation() =
        store().use { store ->
            val id = ride(store)
            val cases = JSONObject(contractFixture("watch-protocol/timestamps.json").readText()).getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val timestamp = case.getString("input")
                val prior = store.originalMembershipCount(id)
                if (case.getBoolean("accepted")) {
                    val row = store.insert(id, 1.0, timestamp, "telemetry", true, 0, mapOf("humanPowerW" to 100.0))
                    val original = store.page(id, row - 1, 1).single()
                    assertEquals(case.getString("retainedOriginal"), original.timestamp)
                    assertEquals(case.getString("queryMicroseconds"), original.queryUs.toString())
                } else {
                    assertThrows(IllegalArgumentException::class.java) {
                        store.insert(id, 1.0, timestamp, "telemetry", true, 0, emptyMap())
                    }
                    assertEquals(prior, store.originalMembershipCount(id))
                }
            }
        }

    @Test
    fun originalAndPageByteLimitsRetainTheUncommittedSuffix() =
        store().use { store ->
            val id = ride(store)
            val prior = store.revision(id)
            assertThrows(IllegalArgumentException::class.java) {
                store.insert(
                    id,
                    1.0,
                    "2026-01-01T00:00:01Z",
                    "telemetry",
                    true,
                    0,
                    emptyMap(),
                    detail = mapOf("evidence" to "x".repeat(32768)),
                )
            }
            assertEquals(prior, store.revision(id))
            store.transaction {
                for (i in 0 until 30) store.insert(
                    id,
                    i.toDouble(),
                    "2026-01-01T00:00:01Z",
                    "telemetry",
                    true,
                    0,
                    emptyMap(),
                    detail = mapOf("evidence" to "x".repeat(20000)),
                )
            }
            val first = store.page(id)
            assertTrue(first.size in 1 until 30)
            assertTrue(first.sumOf { json(it.projection()).toString().toByteArray(Charsets.UTF_8).size } <= 512 * 1024)
            val second = store.page(id, first.last().id)
            assertEquals(30, (first + second).map { it.original!!.eventId }.distinct().size)
        }

    @Test
    fun energyPhysiologicalAndAssociationEvidenceKeepTheirOriginsWithoutSyntheticBasal() =
        store().use { store ->
            val id = ride(store)
            val priorTiming = store.timing(id)
            for ((measure, unit) in
                listOf(
                    "activeEnergy" to "kcal",
                    "respiratoryRate" to "breaths/min",
                    "oxygenSaturation" to "fraction",
                    "hrvRMSSD" to "ms",
                    "association" to "workout",
                )) {
                val identity = store.nextOriginal(id, "phone")
                val h =
                    HealthOriginal(
                        Origin(
                            "healthConnect",
                            "app.synthetic.sensor",
                            UUID.randomUUID().toString(),
                            identity.eventId,
                            "phone",
                            identity.sequence,
                            "1",
                        ),
                        measure,
                        unit,
                        if (measure == "association") "workoutAssociation" else "intervalRecord",
                        "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:10Z",
                        0,
                        10000000,
                        if (measure == "association") null else 100.0,
                        Association.RideWindow(0, 10000000),
                        device = "synthetic device",
                        lastModifiedTimestamp = "2026-01-01T00:00:11Z",
                    )
                store.appendHealthPage(
                    id,
                    listOf(stageHealth(store, id, h)),
                    HealthQueryProgress("healthConnect", measure, "window", null),
                )
            }
            assertEquals(priorTiming, store.timing(id))
            assertEquals(5L, store.selectedCount(id, "health"))
            assertEquals(6L, store.originalMembershipCount(id))
            assertFalse(store.available(id).contains("basalEnergyKcal"))
            assertEquals(
                setOf("activeEnergy", "respiratoryRate", "oxygenSaturation", "hrvRMSSD", "association"),
                store.page(id).map { it.evidence["measure"] }.toSet(),
            )
            assertTrue(
                store.page(id).all { (it.evidence["origin"] as Map<*, *>)["dataOrigin"] == "app.synthetic.sensor" }
            )
        }

    @Test
    fun distanceFixedPointKeepsHalfEvenTiesAndOriginalAnchors() =
        store().use { store ->
            val id = ride(store)
            val distance = RideDistance(store)
            val values = mapOf("controllerSpeedMps" to 0.0078125)
            val originals =
                (0..2).map { t ->
                    store.transaction {
                        val row =
                            store.insert(
                                id,
                                t.toDouble(),
                                "2026-01-01T00:00:0${t}Z",
                                "telemetry",
                                true,
                                0,
                                values,
                                "X6|fixture|5.3",
                                "epoch",
                            )
                        distance.append(id, row, t.toDouble(), values, true, 0, "epoch", "X6|fixture|5.3", false)
                        store.page(id, row - 1, 1).single()
                    }
                }
            store.select(
                "SELECT meters_q,cumulative_q,covered_us,start_anchor,end_anchor FROM distance_intervals WHERE ride=? ORDER BY end_us",
                arrayOf(id),
            ) { c ->
                var index = 0
                while (c.moveToNext()) {
                    assertEquals(7812L, c.getLong(0))
                    assertEquals(7812L * (index + 1), c.getLong(1))
                    assertEquals(1000000L * (index + 1), c.getLong(2))
                    assertEquals(originals[index].original!!.eventId, JSONObject(c.getString(3)).getString("eventId"))
                    assertEquals(
                        originals[index + 1].original!!.eventId,
                        JSONObject(c.getString(4)).getString("eventId"),
                    )
                    index++
                }
                assertEquals(2, index)
            }
            assertEquals(0.015624, distance.total(id, "controller").first, 0.0)
            val fixture = JSONObject(contractFixture("analytics/review-required.json").readText()).getJSONArray("cases")
            val case =
                (0 until fixture.length()).map(fixture::getJSONObject).single {
                    it.getString("testId") == "admission-quantization"
                }
            val inputs = case.getJSONObject("input").getJSONArray("readings")
            val outputs = case.getJSONObject("expectedApproved").getJSONArray("quantization")
            for (i in 0 until inputs.length()) {
                val input = inputs.getJSONObject(i)
                val output = outputs.getJSONObject(i)
                if (
                    output.getString("outcome") == "valid" &&
                        input.getJSONObject("original").getString("representation") == "binary64"
                )
                    assertEquals(
                        output.getLong("valueQ"),
                        app.powerlog.core.analytics.FixedPoint.quantize(
                            input.getJSONObject("original").getDouble("value"),
                            input.getLong("scale"),
                        ),
                    )
            }
        }

    @Test
    fun kindCountsIncludeInvalidOriginalsAndLapCountUsesOnlyInteriorWindows() =
        store().use { store ->
            val id = ride(store)
            for (t in listOf(0.0, 1.0, 1.0, 2.0)) store.lifecycle(id, RideTiming(t, t, "2026-01-01T00:00:02Z"), "lap")
            store.insert(id, 1.0, "2026-01-01T00:00:01Z", "telemetry", true, 0, mapOf("humanPowerW" to -5.0))
            val identity = store.nextOriginal(id, "phone")
            val h =
                HealthOriginal(
                    Origin(
                        "healthConnect",
                        "app.synthetic.sensor",
                        UUID.randomUUID().toString(),
                        identity.eventId,
                        "phone",
                        identity.sequence,
                    ),
                    "association",
                    "workout",
                    "workoutAssociation",
                    "2026-01-01T00:00:00Z",
                    "2026-01-01T00:00:02Z",
                    0,
                    2000000,
                    null,
                    Association.RideWindow(0, 2000000),
                )
            store.appendHealthPage(id, listOf(h), HealthQueryProgress("healthConnect", "association", "workout", null))
            store.seal(id, RideTiming(2.0, 2.0, "2026-01-01T00:00:02Z"))
            val distance = RideDistance(store)
            val summary = RideDetail(store, distance).detail(id, "auto")["summary"] as Map<*, *>
            assertEquals("8", store.metadata(id)["eventCount"])
            assertEquals("8", summary["eventCount"])
            assertEquals("1", summary["telemetryCount"])
            assertEquals("1", summary["healthCount"])
            assertEquals("6", summary["lifecycleCount"])
            assertEquals(2, summary["lapCount"])
        }

    @Test
    fun frozenOptionsIdentityAndProvenanceCannotBeRewritten() =
        store().use { store ->
            val id = ride(store)
            val prior = store.metadata(id)
            val revision = store.revision(id)
            for ((key, value) in
                listOf(
                    "id" to UUID.randomUUID().toString(),
                    "startedAt" to "2026-01-01T00:00:01Z",
                    "indoor" to true,
                    "recordGPS" to false,
                    "saveToHealth" to true,
                    "example" to true,
                    "ownerEndpointId" to UUID.randomUUID().toString(),
                    "ownerDevice" to "appleWatch",
                    "recordingOwner" to "watch",
                )) assertThrows(IllegalArgumentException::class.java) {
                store.update(id, "running", store.timing(id), mapOf(key to value))
            }
            assertEquals(revision, store.revision(id))
            assertEquals(prior, store.metadata(id))
        }

    @Test
    fun healthIntegerCollectionKeysNeverRoundTripThroughBinary64() =
        store().use { store ->
            val id = ride(store)
            val original = store.nextOriginal(id, "phone")
            val origin =
                Origin(
                    "healthConnect",
                    "app.synthetic.sensor",
                    UUID.randomUUID().toString(),
                    original.eventId,
                    "phone",
                    original.sequence,
                )
            val h =
                HealthOriginal(
                    origin,
                    "heartRate",
                    "bpm",
                    "rawSeries",
                    "2026-01-01T00:00:01.123457789Z",
                    "2026-01-01T00:00:01.123457789Z",
                    1000001,
                    1000001,
                    100.0,
                    Association.RideWindow(0, 2000000),
                )
            store.appendHealthPage(
                id,
                listOf(stageHealth(store, id, h)),
                HealthQueryProgress("healthConnect", "heartRate", "window", null),
            )
            assertEquals(1000001L, store.page(id).single().elapsedUs)
            assertEquals("1000001", store.page(id).single().projection()["elapsedUs"])
        }

    @Test
    fun interruptedRideRetainsItsCheckpointWithoutAddingRecoveryEvents() {
        val name = "orphan-${UUID.randomUUID()}.sqlite"
        var id = ""
        val retained = RideTiming(12.0, 10.0, "2026-01-01T00:00:12.123456789Z")
        RideStore(context, name).use { store ->
            id = ride(store, 4)
            store.prepareLocalOwner(id)
            store.insert(
                id,
                9.0,
                "2026-01-01T00:00:09.123456789Z",
                "location",
                true,
                0,
                mapOf("latitude" to 0.0, "longitude" to 0.0, "horizontalAccuracyM" to 2.0),
            )
            store.fixtureCheckpoint(id, retained)
        }
        RideStore(context, name).use { store ->
            store.recoverOrphans()
            assertEquals("interrupted", store.metadata(id)["phase"])
            assertNull(store.metadata(id)["endedAt"])
            assertEquals(retained, store.timing(id))
            assertEquals(listOf("start"), store.events(id).map { it.action })
            assertEquals("2", store.metadata(id)["eventCount"])
            val revision = store.revision(id)
            store.recoverOrphans()
            assertTrue(store.revision(id) > revision)
            assertEquals(listOf("start"), store.events(id).map { it.action })
            assertEquals(retained, store.timing(id))
        }
    }

    @Test
    fun storedOwnerRolesAndEndpointCannotBeSilentlyReplacedOnRead() =
        store().use { store ->
            val id = ride(store)
            val stored =
                store.readableDatabase.rawQuery("SELECT metadata FROM rides WHERE id=?", arrayOf(id)).use {
                    it.moveToFirst()
                    it.getString(0)
                }
            for ((key, value) in
                listOf(
                    "ownerDevice" to "appleWatch",
                    "recordingOwner" to "watch",
                    "healthPublisher" to "watch",
                    "publisherEndpointId" to UUID.randomUUID().toString(),
                    "watchEnabled" to true,
                    "storage" to "javascript",
                    "healthProvider" to "healthKit",
                )) {
                val metadata = JSONObject(stored).put(key, value).toString()
                store.writableDatabase.execSQL("UPDATE rides SET metadata=? WHERE id=?", arrayOf(metadata, id))
                assertThrows(IllegalArgumentException::class.java) { store.metadata(id) }
                store.readableDatabase.rawQuery("SELECT metadata FROM rides WHERE id=?", arrayOf(id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(metadata, it.getString(0))
                }
            }
        }
}

internal fun contractFixture(path: String) =
    File((System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract") + "/" + path)
