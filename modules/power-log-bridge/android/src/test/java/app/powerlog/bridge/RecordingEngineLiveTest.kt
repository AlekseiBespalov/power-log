package app.powerlog.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineLiveTest : RecordingEngineTestBase() {
    @Test
    fun liveSourceExposesPreviewNumbersAndPlotsAfterConnectingWithoutStartingARide() = command {
        fun query(operation: MonitorOperation, options: Payload = emptyMap()): Payload =
            engine.monitorQuery(
                BridgeInputs.monitor(
                    operation,
                    mapOf("source" to "live", "generation" to 0, "metrics" to listOf("humanPowerW")) + options,
                )
            )
        val before = query(MonitorOperation.Describe)
        engine.connect(BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to 2))) {}
        val connected = query(MonitorOperation.Describe)
        assertNotEquals(before["sourceId"], connected["sourceId"])
        repeat(4) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            telemetry(power = 0.0)
            engine.flush()
        }
        val description = query(MonitorOperation.Describe)
        assertTrue((description["availableMetrics"] as List<*>).contains("humanPowerW"))
        val latest = query(MonitorOperation.Latest)
        val point = (latest["points"] as Map<*, *>)["humanPowerW"] as Map<*, *>
        assertEquals(0.0, point["value"])
        assertNotNull((latest["liveAcquiredAt"] as Map<*, *>)["humanPowerW"])
        val plot =
            query(
                MonitorOperation.Plot,
                mapOf(
                    "expectedRevision" to description["revision"],
                    "startSeconds" to 0.0,
                    "endSeconds" to 10.0,
                    "buckets" to 100,
                ),
            )
        assertEquals("ok", plot["status"])
        assertFalse(((plot["series"] as Map<*, *>)["humanPowerW"] as List<*>).isEmpty())
        assertEquals("idle", engine.state()["phase"])
    }

    @Test
    fun previewPlotsKeepTheRecentWindowWhenOldSamplesAreTrimmed() = command {
        val live = engine.source(MonitorTarget.Live)
        repeat(80) { index ->
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            telemetry(power = if (index < 67) 900.0 else 100.0)
            engine.flush()
        }
        assertEquals((340..400 step 5).map(Int::toDouble), times(live))
        for (buckets in listOf(1, 100)) {
            val plot =
                engine.monitorQuery(
                    BridgeInputs.monitor(
                        MonitorOperation.Plot,
                        mapOf(
                            "source" to "live",
                            "generation" to 0,
                            "expectedRevision" to recordStore(live).revision(live).toString(),
                            "metrics" to listOf("humanPowerW"),
                            "startSeconds" to 0.0,
                            "endSeconds" to 400.0,
                            "buckets" to buckets,
                        ),
                    )
                )
            assertEquals("ok", plot["status"])
            val points = ((plot["series"] as Map<*, *>)["humanPowerW"] as List<*>).map { it as Map<*, *> }
            assertFalse(points.isEmpty())
            assertTrue(points.all { (it["elapsedSeconds"] as Double) >= 340.0 })
            assertTrue(points.all { it["value"] == 100.0 })
            assertEquals(400.0, points.last()["elapsedSeconds"])
        }
        assertFalse(engine.store.exists(live))
    }

    @Test
    fun liveMonitorEvidenceWaitsForCommitAndDuplicateCaptureKeepsTheOriginal() = command {
        val id = start(true)
        val request =
            BridgeInputs.monitor(
                MonitorOperation.Latest,
                mapOf(
                    "source" to "workout",
                    "id" to id,
                    "generation" to 0,
                    "metrics" to listOf("humanPowerW", "speedMps"),
                ),
            )
        val empty = mapOf("humanPowerW" to null, "speedMps" to null)
        advanceTo(1)
        val acquiredAt = SystemClock.elapsedRealtime()
        telemetry()
        location(1.0, speed = 3f)
        assertEquals(empty, monitorQuery(id, request)["points"])
        val db = engine.store.writableDatabase
        db.execSQL(
            "CREATE TEMP TRIGGER fail_live_commit BEFORE UPDATE OF checkpoint_at ON rides BEGIN SELECT RAISE(ABORT,'injected commit failure'); END"
        )
        try {
            assertThrows(SQLiteException::class.java) { engine.flush() }
            assertEquals(0L, recordStore(id).count(id))
            val failed = monitorQuery(id, request)
            assertEquals(empty, failed["points"])
            assertEquals(empty, failed["liveAcquiredAt"])
        } finally {
            db.execSQL("DROP TRIGGER fail_live_commit")
        }
        engine.flush()
        val committed = monitorQuery(id, request)
        assertEquals("interrupted", engine.state()["phase"])
        assertFalse(committed.containsKey("liveAcquiredAt"))
        assertTrue((committed["points"] as Map<*, *>).values.all { it != null })
        advanceTo(4)
        telemetry(acquiredAt = acquiredAt)
        location(1.0, speed = 8f)
        engine.flush()
        val repeated = monitorQuery(id, request)
        assertEquals(committed["points"], repeated["points"])
        assertEquals(committed["liveAcquiredAt"], repeated["liveAcquiredAt"])
    }

    @Test
    fun startingAndStoppingRidesClearsLiveMonitorEvidence() = command {
        fun latest(id: String) =
            monitorQuery(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "metrics" to listOf("humanPowerW"),
                    ),
                ),
            )
        val empty = mapOf("humanPowerW" to null)
        val live = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry()
        engine.flush()
        assertNotEquals(empty, latest(live)["points"])
        val ride = start()
        assertNotEquals(empty, latest(live)["points"])
        assertEquals(empty, latest(live)["liveAcquiredAt"])
        assertEquals(empty, latest(ride)["points"])
        advanceTo(1)
        telemetry()
        engine.flush()
        assertNotEquals(empty, latest(ride)["points"])
        engine.action("stop", ride)
        assertNotEquals(empty, latest(live)["points"])
        assertEquals(empty, latest(live)["liveAcquiredAt"])
        assertNotEquals(empty, latest(ride)["points"])
        assertFalse(latest(ride).containsKey("liveAcquiredAt"))
        val next = start()
        assertEquals(empty, latest(next)["points"])
        assertEquals(empty, latest(next)["liveAcquiredAt"])
    }

    @Test
    fun manualConnectStartsAFreshLiveSessionFromZero() = command {
        val previous = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        telemetry()
        engine.flush()
        assertEquals(1L, recordStore(previous).count(previous))
        engine.connect(BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to 4))) {}
        val live = engine.source(MonitorTarget.Live)
        assertNotEquals(previous, live)
        assertEquals(0L, recordStore(previous).count(previous))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        telemetry()
        engine.flush()
        assertEquals(listOf(2.0), times(live))
    }

    @Test
    fun rejectedConnectKeepsTheLiveSessionAndItsOriginals() = command {
        val input = BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to 4))
        engine.connect(input) {}
        val live = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry()
        engine.flush()
        assertThrows(IllegalStateException::class.java) {
            engine.connect(input) { fail("Unexpected admission") }
        }
        assertEquals(live, engine.source(MonitorTarget.Live))
        assertEquals(1L, recordStore(live).count(live))
        engine.bluetooth.disconnect()
    }

    @Test
    fun previewIsMemoryOnlyAndDoesNotDuplicateRecordedRideSamples() = command {
        val live = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry()
        val ride = start()
        advanceTo(1)
        telemetry()
        engine.flush()
        engine.action("stop", ride)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry()
        engine.flush()
        assertEquals(live, engine.source(MonitorTarget.Live))
        assertEquals(2, times(live).size)
        assertFalse(engine.store.exists(live))
        assertEquals(times(live), times(live).sorted())
        assertEquals(listOf(1.0), times(ride))
    }

    @Test
    fun recordingKeepsBothRawStreamsAndAvailableDistanceProfiles() = command {
        val id = start(true)
        for (second in 1..3) {
            advanceTo(second.toLong())
            telemetry()
            location(second.toDouble(), speed = 3f)
        }
        engine.flush()
        assertEquals(3, engine.store.page(id).count { it.kind == "telemetry" })
        assertEquals(3, engine.store.page(id).count { it.kind == "location" })
        val sources =
            engine.store.select("SELECT DISTINCT source FROM distance_intervals WHERE ride=?", arrayOf(id)) { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        assertEquals(listOf("gps:phone"), sources)
    }

    @Test
    fun controllerDistanceRetainsGpsProjectionRegardlessOfHealth() = command {
        for (health in listOf(false, true)) {
            val id = start(gps = true, saveToHealth = health, distanceSource = "controller")
            for (second in 1..3) {
                advanceTo(second.toLong())
                telemetry(speed = 4.0)
                location(second.toDouble(), speed = 3f)
            }
            engine.flush()
            val sources =
                engine.store.select("SELECT DISTINCT source FROM distance_intervals WHERE ride=?", arrayOf(id)) { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
            assertEquals(setOf("controller", "gps:phone"), sources)
            assertEquals("controller", engine.store.metadata(id)["distanceSource"])
            engine.action("stop", id)
        }
    }

    @Test
    fun liveMonitorUsesEachMetricsOwnGpsOrControllerObservation() = command {
        val id = start(true)
        val origin = SystemClock.elapsedRealtime() / 1000.0
        val request =
            mapOf(
                "metrics" to
                    listOf(
                        "humanPowerW",
                        "cadenceRpm",
                        "horizontalAccuracyM",
                        "speedMps",
                        "distanceMeters",
                    ),
                "distanceSource" to "gps:phone",
            )
        advanceTo(1)
        location(1.0, speed = 2f)
        advanceTo(2)
        location(2.0, speed = 3f)
        engine.flush()
        val gpsOnly =
            monitorQuery(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
                ),
            )
        assertEquals(
            mapOf(
                "humanPowerW" to null,
                "cadenceRpm" to null,
                "horizontalAccuracyM" to origin + 2,
                "speedMps" to origin + 2,
                "distanceMeters" to origin + 2,
            ),
            gpsOnly["liveAcquiredAt"],
        )
        advanceTo(4)
        telemetry(4.0)
        advanceTo(5)
        telemetry(4.0)
        advanceTo(8)
        location(8.0)
        engine.flush()
        val mixed =
            monitorQuery(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
                ),
            )
        assertEquals(
            mapOf(
                "humanPowerW" to origin + 5,
                "cadenceRpm" to null,
                "horizontalAccuracyM" to origin + 8,
                "speedMps" to origin + 2,
                "distanceMeters" to origin + 8,
            ),
            mixed["liveAcquiredAt"],
        )
        val controller =
            monitorQuery(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request + ("distanceSource" to "controller"),
                ),
            )
        assertEquals(origin + 8, (controller["liveAcquiredAt"] as Map<*, *>)["distanceMeters"])
        advanceTo(9)
        location(7.0)
        engine.flush()
        val reread =
            monitorQuery(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
                ),
            )
        assertEquals(mixed["liveAcquiredAt"], reread["liveAcquiredAt"])
        assertEquals(mixed["points"], reread["points"])
        assertEquals(9.0, reread.num("nowSeconds"), 0.0)
    }

    @Test
    fun queuedBluetoothNotificationsKeepReceiptTimeInLiveAndRideObservations() {
        val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val identity = CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val delivered = mutableListOf<Payload>()
        var timestamp = ""
        command {
            engine.listeners.add { event, payload ->
                if (event == "onSample") delivered.add(payload)
            }
        }
        for (recording in listOf(false, true)) {
            try {
                val (id, acquiredAt, elapsed) =
                    command {
                        setBikeStatus("connected")
                        val id = if (recording) start() else engine.source(MonitorTarget.Live)
                        val origin = field(if (recording) "startClock" else "liveStart") as Long
                        val adapter = engine.context.getSystemService(BluetoothManager::class.java).adapter
                        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
                        engine.bluetooth.connect("02:00:00:00:00:01", 4) {}
                        fun bluetoothField(name: String) =
                            ControllerBluetooth::class.java.getDeclaredField(name).apply {
                                isAccessible = true
                            }
                        readyAdapter(engine.bluetooth, identity)
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                        val acquiredAt = SystemClock.elapsedRealtime()
                        setAdapterRequest(
                            engine.bluetooth,
                            ControllerRequest.telemetry,
                            (acquiredAt - 100) / 1000.0,
                        )
                        val gatt = bluetoothField("gatt").get(engine.bluetooth) as BluetoothGatt
                        val notify =
                            BluetoothGattCharacteristic(
                                UUID.fromString(ControllerGatt.NOTIFY),
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ,
                            )
                        delivered.clear()
                        timestamp = iso()
                        val notification = frame.copyOf()
                        shadowOf(gatt).gattCallback.onCharacteristicChanged(gatt, notify, notification)
                        notification.fill(0)
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                        assertTrue(delivered.isEmpty())
                        assertEquals(0L, recordStore(id).count(id))
                        Triple(id, acquiredAt / 1000.0, (acquiredAt - origin) / 1000.0)
                    }
                command {
                    engine.flush()
                    assertEquals(1, delivered.size)
                    val sample = delivered.single()
                    assertEquals(acquiredAt, sample.num("acquiredAtMonotonic"), 0.0)
                    assertEquals(elapsed, sample.num("elapsedSeconds"), 0.0)
                    assertEquals(timestamp, sample["timestamp"])
                    val original = recordStore(id).page(id).single()
                    val evidence = recordStore(id).telemetryOriginal(id, original.original!!.eventId)!!
                    assertEquals(acquiredAt, evidence.acquisitionMonotonic, 0.0)
                    assertEquals(timestamp, evidence.timestamp.original)
                    assertEquals(identity, evidence.telemetry.identity)
                    assertEquals(elapsed, original.time, 0.0)
                    assertEquals(timestamp, original.timestamp)
                    assertEquals(2.0, engine.bluetooth.diagnostics()["lastSampleAgeSeconds"])
                    if (recording) {
                        assertEquals("receiving", stream("controller")["status"])
                    }
                    val request =
                        BridgeInputs.monitor(
                            MonitorOperation.Latest,
                            mapOf(
                                "source" to "workout",
                                "id" to id,
                                "generation" to 0,
                                "metrics" to listOf("humanPowerW"),
                            ),
                        )
                    val latest = monitorQuery(id, request)
                    assertEquals(mapOf("humanPowerW" to acquiredAt), latest["liveAcquiredAt"])
                    assertEquals(elapsed + 2, latest.num("nowSeconds"), 0.0)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                    if (recording) {
                        assertEquals("receiving", stream("controller")["status"])
                    }
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                    if (recording) {
                        assertEquals("stale", stream("controller")["status"])
                    }
                    val reread = monitorQuery(id, request)
                    assertEquals(latest["liveAcquiredAt"], reread["liveAcquiredAt"])
                    assertEquals(6.0, reread.num("monotonicAt") - acquiredAt, 0.0)
                    assertEquals(original, recordStore(id).page(id).single())
                    if (recording) engine.action("stop", id)
                }
            } finally {
                command {
                    engine.bluetooth.disconnect()
                    completeCancellation(engine.bluetooth, engine.handler)
                }
            }
        }
    }

    @Test
    fun flushPublishesTelemetryGpsAndDerivedDistanceWithoutPostCommitObservationReads() = command {
        val id = start(true)
        advanceTo(1)
        telemetry(4.0)
        location(1.0, speed = 2f)
        engine.flush()
        advanceTo(2)
        telemetry(4.0)
        location(2.0, speed = 3f)
        val db = engine.store.writableDatabase
        val factory =
            SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply {
                isAccessible = true
            }
        val previous = factory.get(db)
        val postCommitReads = mutableListOf<String>()
        factory.set(
            db,
            SQLiteDatabase.CursorFactory { _, driver, table, query ->
                if (!db.inTransaction() && query.toString().contains("FROM observations"))
                    postCommitReads.add(query.toString())
                SQLiteCursor(driver, table, query)
            },
        )
        try {
            engine.flush()
        } finally {
            factory.set(db, previous)
        }
        assertTrue(postCommitReads.toString(), postCommitReads.isEmpty())
        for (source in listOf("gps:phone", "controller")) {
            val result =
                monitorQuery(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Latest,
                        mapOf(
                            "source" to "workout",
                            "id" to id,
                            "generation" to 0,
                            "distanceSource" to source,
                            "metrics" to listOf("humanPowerW", "speedMps", "distanceMeters"),
                        ),
                    ),
                )
            val acquiredAt = SystemClock.elapsedRealtime() / 1000.0
            assertEquals(
                mapOf(
                    "humanPowerW" to acquiredAt,
                    "speedMps" to acquiredAt,
                    "distanceMeters" to acquiredAt,
                ),
                result["liveAcquiredAt"],
            )
            assertTrue((result["points"] as Map<*, *>).values.all { it != null })
        }
    }

    @Test
    fun sampleAcquisitionTimeSurvivesDelayedEmissionAndDelivery() = command {
        start()
        val origin = SystemClock.elapsedRealtime() / 1000.0
        val delivered = mutableListOf<Payload>()
        engine.listeners.add { event, payload -> if (event == "onSample") delivered.add(payload) }
        advanceTo(1)
        repeat(7) { telemetry() }
        assertEquals(origin + 1, delivered.single().num("acquiredAtMonotonic"), 0.0)
        assertFalse(delivered.single().containsKey("ageSeconds"))
        val db = engine.store.writableDatabase
        val factory =
            SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply {
                isAccessible = true
            }
        val previous = factory.get(db)
        var delayed = false
        factory.set(
            db,
            SQLiteDatabase.CursorFactory { _, driver, table, query ->
                if (!delayed) {
                    delayed = true
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
                }
                SQLiteCursor(driver, table, query)
            },
        )
        try {
            advanceTo(2)
            withUtc(1767222000000L) { telemetry() }
            assertTrue(delayed)
            assertEquals(2, delivered.size)
            val queued = delivered.last().toMap()
            assertEquals(origin + 2, queued.num("acquiredAtMonotonic"), 0.0)
            assertEquals(
                8.0,
                SystemClock.elapsedRealtime() / 1000.0 - queued.num("acquiredAtMonotonic"),
                0.0,
            )
            assertEquals(2.0, queued.num("elapsedSeconds"), 0.0)
            assertEquals(iso(1767222000000L), queued["timestamp"])
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            assertEquals(
                17.0,
                SystemClock.elapsedRealtime() / 1000.0 - queued.num("acquiredAtMonotonic"),
                0.0,
            )
            assertEquals(queued, delivered.last())
        } finally {
            factory.set(db, previous)
        }
    }

    @Test
    fun monitorClockIsPairedAfterReadsAndAcquisitionSurvivesDelayedDelivery() = command {
        val id = start()
        val origin = SystemClock.elapsedRealtime() / 1000.0
        advanceTo(2)
        telemetry()
        engine.flush()
        val request = mapOf("metrics" to listOf("humanPowerW"))
        val original = recordStore(id).page(id).single()
        val db = engine.store.writableDatabase
        val factory =
            SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply {
                isAccessible = true
            }
        val previous = factory.get(db)
        for (kind in listOf("describe", "latest")) {
            var revisionReads = 0
            factory.set(
                db,
                SQLiteDatabase.CursorFactory { _, driver, table, query ->
                    if (query.toString().contains("SELECT revision FROM rides") && ++revisionReads == 2)
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(8))
                    SQLiteCursor(driver, table, query)
                },
            )
            val queued =
                try {
                    engine.monitor
                        .query(
                            id,
                            BridgeInputs.monitor(
                                MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                                mapOf(
                                    "source" to "workout",
                                    "id" to id,
                                    "generation" to 0,
                                    "sinceRevision" to "0",
                                ) + request,
                            ),
                        )
                        .also { assertEquals(2, revisionReads) }
                } finally {
                    factory.set(db, previous)
                }
            val emittedAt = SystemClock.elapsedRealtime() / 1000.0
            assertEquals(emittedAt, queued.num("monotonicAt"), 0.0)
            assertEquals(emittedAt - origin, queued.num("nowSeconds"), 0.0)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            val reread =
                monitorQuery(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                        mapOf(
                            "source" to "workout",
                            "id" to id,
                            "generation" to 0,
                            "sinceRevision" to "0",
                        ) + request,
                    ),
                )
            assertEquals(queued.num("nowSeconds") + 9, reread.num("nowSeconds"), 0.0)
            assertEquals(queued.num("monotonicAt") + 9, reread.num("monotonicAt"), 0.0)
            if (kind == "latest") {
                assertEquals(mapOf("humanPowerW" to origin + 2), queued["liveAcquiredAt"])
                assertEquals(queued["liveAcquiredAt"], reread["liveAcquiredAt"])
                assertEquals(queued["points"], reread["points"])
            }
        }
        assertEquals(original, recordStore(id).page(id).single())
    }

    @Test
    fun delayedFixesAcrossPauseAndResumeReplayFromOriginalsInInsertionOrder() = command {
        val id = start(true)
        advanceTo(1)
        location(1.0)
        telemetry(4.0)
        engine.flush()
        advanceTo(3)
        engine.action("pause", id)
        advanceTo(4)
        location(2.0)
        engine.flush()
        val delayed = engine.distance.total(id, "gps:phone")
        assertTrue(delayed.first > 1.0)
        assertEquals(1.0, delayed.second, 0.0)
        advanceTo(5)
        engine.action("resume", id)
        location(2.5)
        engine.flush()
        assertEquals(1.5, engine.distance.total(id, "gps:phone").second, 0.0)
        location(4.0)
        advanceTo(6)
        location(6.0)
        telemetry(6.0)
        advanceTo(7)
        location(7.0)
        telemetry(8.0)
        advanceTo(8)
        location(8.0)
        telemetry(8.0, "40000000-0000-4000-8000-000000000002")
        advanceTo(9)
        location(7.5)
        telemetry(10.0, "40000000-0000-4000-8000-000000000002")
        advanceTo(10)
        location(10.0)
        telemetry(10.0, "40000000-0000-4000-8000-000000000002")
        advanceTo(11)
        location(11.0)
        engine.action("stop", id)
        val rows = recordStore(id).page(id)
        assertEquals(
            listOf(1.0, 2.0, 2.5, 6.0, 7.0, 8.0, 10.0, 11.0),
            rows.filter { it.kind == "location" }.map { it.time },
        )
        assertTrue(rows.first { it.time == 2.0 }.active)
        assertFalse(rows.any { it.time == 4.0 || it.kind == "location" && it.time == 7.5 })
        assertDistanceReplay(engine.store, id, setOf("controller", "gps:phone"))
    }

    @Test
    fun checkpointsAndLifecycleKeepUtcIndependentOfElapsedAndActiveTime() = command {
        val base = 1767225600000L
        val id = withUtc(base) { start(true) }
        assertEquals(RideTiming(0.0, 0.0, iso(base)), engine.store.timing(id))
        val tick = field("tick") as Runnable
        advanceTo(2)
        withUtc(base + 3600000) {
            location(2.0)
            tick.run()
            engine.handler.removeCallbacks(tick)
            assertEquals(RideTiming(2.0, 2.0, iso(base + 3600000)), engine.store.timing(id))
        }
        advanceTo(3)
        withUtc(base - 3600000) { engine.action("pause", id) }
        advanceTo(6)
        withUtc(base + 7200000) { engine.action("resume", id) }
        advanceTo(7)
        withUtc(base - 7200000) { engine.action("lap", id) }
        assertEquals(RideTiming(7.0, 4.0, iso(base - 7200000)), engine.store.timing(id))
        advanceTo(10)
        withUtc(base - 10800000) { engine.action("stop", id) }
        assertEquals(RideTiming(10.0, 7.0, iso(base - 10800000)), engine.store.timing(id))
        assertEquals(iso(base - 10800000), engine.store.metadata(id)["endedAt"])
        assertEquals(
            listOf(
                RideEvent(0.0, "start", iso(base)),
                RideEvent(3.0, "pause", iso(base - 3600000)),
                RideEvent(6.0, "resume", iso(base + 7200000)),
                RideEvent(7.0, "lap", iso(base - 7200000)),
                RideEvent(10.0, "stop", iso(base - 10800000)),
            ),
            engine.store.events(id),
        )
        assertEquals(iso(base + 3600000), recordStore(id).page(id).single().timestamp)
    }

    @Test
    fun capabilitiesDescribePhoneOwnershipAndTheAvailableHealthProvider() = command {
        val available = engine.health.available
        assertEquals(
            mapOf(
                "phoneWorkout" to true,
                "watchWorkout" to false,
                "phoneHealth" to available,
                "watchHealth" to false,
                "healthProvider" to if (available) "healthConnect" else null,
                "gps" to true,
                "foregroundOnly" to false,
            ),
            engine.state()["capabilities"],
        )
    }

    @Test
    fun unavailableHealthAndWatchAreDisabledInTheFrozenOptions() = command {
        assertFalse(engine.health.available)
        for (saveToHealth in listOf(true, false, null)) {
            val requested =
                mutableMapOf<String, Any?>(
                    "indoor" to true,
                    "useWatch" to true,
                    "saveToHealth" to saveToHealth,
                    "sampleHz" to 4,
                )
            val state = engine.start(BridgeInputs.ride(mapOf("indoor" to false) + requested), healthAccess.grants)
            val id = state.str("id")
            assertEquals(false, state["useWatch"])
            assertEquals(false, state["saveToHealth"])
            assertEquals(false, state["recordGPS"])
            assertEquals(false, engine.store.metadata(id)["watchEnabled"])
            assertEquals(false, engine.store.metadata(id)["saveToHealth"])
            requested["saveToHealth"] = true
            requested["recordGPS"] = true
            assertEquals(false, engine.state()["saveToHealth"])
            assertEquals(false, engine.state()["recordGPS"])
            engine.action("pause", id)
            engine.action("resume", id)
            engine.action("stop", id)
            assertEquals("notRequested", engine.store.metadata(id)["healthKitState"])
            assertTrue(engine.store.pendingHealthJobs().isEmpty())
        }
    }

    @Test
    fun malformedOptionsCannotStartCaptureOrChangePolling() = command {
        engine.bluetooth.setHz(4)
        for ((field, input) in rejectedRideOptions()) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    engine.start(BridgeInputs.ride(input), healthAccess.grants)
                }
            assertTrue(error.message.orEmpty().contains(field))
            assertNull(engine.state()["id"])
            assertNull(field("rideDevice"))
            assertEquals(4, engine.bluetooth.hz)
            assertFalse(engine.gpsActive)
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
        }
    }

    @Test
    fun malformedConnectCannotBindTheRecordingToABike() = command {
        val id = start()
        assertNull(field("rideDevice"))
        val invalid =
            rejectedRideOptions()
                .filter { it.first == "sampleHz" }
                .map {
                    mapOf("deviceId" to "02:00:00:00:00:01", "hz" to it.second["sampleHz"])
                } + listOf(mapOf("deviceId" to "", "hz" to 4), mapOf("hz" to 4))
        for (input in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                engine.connect(BridgeInputs.connect(input)) { fail("Bluetooth completion ran") }
            }
            assertNull(field("rideDevice"))
            assertNull(engine.bluetooth.deviceId)
            assertEquals(4, engine.bluetooth.hz)
            assertEquals(id, engine.state()["id"])
        }
    }

    @Test
    fun incomingExampleFlagCannotChangeLiveRideProvenance() = command {
        val input =
            BridgeInputs.ride(mapOf("indoor" to true, "saveToHealth" to false, "example" to true, "sampleHz" to 2))
        val state = engine.start(input, healthAccess.grants)
        assertEquals(false, engine.store.metadata(state.str("id"))["example"])
    }

    @Test
    fun typedTargetsResolveTheIntendedSourceAndUnavailableMetricsStayUnavailable() = command {
        val live = engine.source(MonitorTarget.Live)
        val id = start()
        assertNotEquals(id, live)
        assertEquals(id, engine.source(MonitorTarget.Live))
        assertEquals(live, engine.source(MonitorTarget.Workout(live)))
        assertThrows(IllegalArgumentException::class.java) {
            engine.source(
                BridgeInputs.monitor(
                        MonitorOperation.Describe,
                        mapOf("source" to "unknown", "generation" to 0),
                    )
                    .target
            )
        }
        val request =
            BridgeInputs.monitor(
                MonitorOperation.Latest,
                mapOf(
                    "source" to "workout",
                    "id" to id,
                    "generation" to 0,
                    "metrics" to listOf("heartRateBpm", "activeEnergyKcal"),
                ),
            )
        assertEquals(
            mapOf("heartRateBpm" to null, "activeEnergyKcal" to null),
            monitorQuery(id, request)["points"],
        )
    }

    @Test
    fun methodResultsAndEventsUseTheSameSnapshotWithExplicitNulls() = command {
        val emitted = mutableListOf<Payload>()
        val listener: (String, Payload) -> Unit = { name, value ->
            if (name == "onWorkoutState") emitted.add(value)
        }
        engine.listeners.add(listener)
        try {
            val result =
                engine.start(
                    BridgeInputs.ride(mapOf("indoor" to true, "saveToHealth" to false, "sampleHz" to 2)),
                    healthAccess.grants,
                )
            assertEquals(result.keys, emitted.last().keys)
            for (field in result.keys - "timerSeconds") assertEquals(
                field,
                result[field],
                emitted.last()[field],
            )
            assertEquals(
                engine.store.metadata(result.str("id"))["collectionRevision"],
                result["collectionRevision"],
            )
            assertFalse(result.containsKey("finalizationState"))
            assertEquals(mapOf("installed" to false), result["watch"])
            assertEquals(setOf("status"), stream("controller").keys)
            assertEquals(setOf("status", "source", "accuracyMeters"), stream("gps").keys)
        } finally {
            engine.listeners.remove(listener)
        }
    }

    @Test
    fun historyRevisionExhaustionRejectsWritesBeforeStartingStoppingOrDeleting() = command {
        val prior = engine.store.catalogState().revision
        fun revision(value: Long) {
            engine.store.writableDatabase.execSQL(
                "UPDATE catalog_state SET revision=? WHERE singleton=1",
                arrayOf(value),
            )
        }
        try {
            revision(Long.MAX_VALUE)
            assertThrows(ArithmeticException::class.java) { start() }
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
            revision(Long.MAX_VALUE - 1)
            val id = start()
            assertEquals(Long.MAX_VALUE.toString(), engine.state()["historyRevision"])
            assertThrows(ArithmeticException::class.java) { engine.action("stop", id) }
            assertThrows(ArithmeticException::class.java) { engine.action("discard", id) }
            assertEquals(id, engine.state()["id"])
            assertEquals("running", engine.store.metadata(id)["phase"])
            revision(prior)
            engine.action("stop", id)
            revision(Long.MAX_VALUE)
            assertThrows(ArithmeticException::class.java) { engine.delete(id) }
            assertEquals("completed", engine.store.metadata(id)["phase"])
        } finally {
            revision(prior)
        }
    }

    @Test
    fun ticksReadOriginalCountProjectionsAndLifecycleIncrementsTheOriginalCount() = command {
        val id = start(true)
        val queries = mutableListOf<String>()
        val db = engine.store.writableDatabase
        val factory =
            SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply {
                isAccessible = true
            }
        val previous = factory.get(db)
        factory.set(
            db,
            SQLiteDatabase.CursorFactory { _, driver, table, query ->
                queries.add(query.toString())
                SQLiteCursor(driver, table, query)
            },
        )
        val tick = field("tick") as Runnable
        try {
            for (seconds in 1L..3L) {
                advanceTo(seconds)
                location(seconds.toDouble())
                tick.run()
                engine.handler.removeCallbacks(tick)
                assertEquals("running", engine.state()["phase"])
            }
            assertTrue(queries.isNotEmpty())
            fun countQueries() = queries.count { it.contains("count(*) FROM observations") }
            assertEquals(0, countQueries())
            assertEquals("4", engine.store.metadata(id)["eventCount"])
            engine.action("pause", id)
            engine.action("resume", id)
            engine.action("lap", id)
            assertEquals(0, countQueries())
            engine.action("stop", id)
            assertEquals(0, countQueries())
            assertEquals("8", engine.store.metadata(id)["eventCount"])
            assertEquals(3, recordStore(id).page(id).size)
        } finally {
            factory.set(db, previous)
            engine.handler.removeCallbacks(tick)
        }
    }

    @Test
    fun notificationPublishesCoreTimersAndNewControlTokens() = command {
        val id = start()
        val running = requireNotNull(engine.notificationState)
        val tick = field("tick") as Runnable
        advanceTo(5)
        tick.run()
        engine.handler.removeCallbacks(tick)
        assertTrue(requireNotNull(engine.notificationState).publicationSequence > running.publicationSequence)
        engine.action("lap", id)
        assertNotEquals(running.controlToken, requireNotNull(engine.notificationState).controlToken)
        engine.action("pause", id)
        val paused = requireNotNull(engine.notificationState)
        assertEquals("paused", paused.expectedPhase)
        assertEquals("00:05", paused.timer.text)
        advanceTo(10)
        tick.run()
        engine.handler.removeCallbacks(tick)
        assertEquals("00:05", requireNotNull(engine.notificationState).timer.text)
        engine.action("resume", id)
        val resumed = requireNotNull(engine.notificationState)
        assertEquals("running", resumed.timer.mode)
        advanceTo(12)
        assertEquals(7.0, (timeUs(iso()) - requireNotNull(resumed.timer.originUs)) / 1000000.0, 0.0)
        engine.action("stop", id)
        assertNull(engine.notificationState)
        val next = start()
        assertEquals(next, requireNotNull(engine.notificationState).rideId)
        assertNotEquals(running.controlToken, requireNotNull(engine.notificationState).controlToken)
    }
}
