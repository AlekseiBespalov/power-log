package app.powerlog.bridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPausedSystemClock
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, instrumentedPackages = ["app.powerlog.bridge"])
class RecordingEngineTest {
    private lateinit var engine: RecordingEngine

    private fun field(name: String): Any? =
        RecordingEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    private fun <T> command(body: () -> T): T {
        val result = CompletableFuture<T>()
        engine.handler.post {
            try {
                result.complete(body())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result.get(20, TimeUnit.SECONDS)
    }

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context)
            .grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val locations = context.getSystemService(LocationManager::class.java)
        shadowOf(locations).setLocationEnabled(true)
        shadowOf(locations).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        engine = RecordingEngine.get(context)
        engine.awaitReady()
        command { engine.handler.removeCallbacks(field("tick") as Runnable) }
    }

    private fun shutdown() {
        (field("healthWork") as ExecutorService).apply {
            shutdown()
            awaitTermination(20, TimeUnit.SECONDS)
        }
        command { engine.handler.removeCallbacksAndMessages(null) }
        (field("thread") as HandlerThread).apply {
            quitSafely()
            join(20000)
        }
        engine.reads.shutdownNow()
        engine.store.close()
        RecordingEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @After
    fun close() {
        command {
            val id = engine.state().str("id")
            if (id.isNotEmpty()) engine.action("discard", id)
        }
        shutdown()
    }

    private fun start(gps: Boolean = false): String =
        engine
            .start(
                BridgeInputs.ride(
                    mapOf("indoor" to false) + mapOf("saveToHealth" to false, "recordGPS" to gps, "sampleHz" to 4)
                )
            )
            .str("id")

    private fun advanceTo(seconds: Long) {
        val target = (field("startClock") as Long) + seconds * 1000
        ShadowSystemClock.advanceBy(Duration.ofMillis(target - SystemClock.elapsedRealtime()))
    }

    private fun location(seconds: Double, accuracy: Float = 5f, speed: Float? = null) {
        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0
                longitude = seconds * 0.00001
                this.accuracy = accuracy
                if (speed != null) this.speed = speed
                time = java.time.Instant.parse(iso()).toEpochMilli()
                elapsedRealtimeNanos = ((field("startClock") as Long) * 1_000_000) + (seconds * 1e9).toLong()
            }
        (field("locationListener") as LocationListener).onLocationChanged(fix)
    }

    private fun <T> withUtc(millis: Long, body: () -> T): T {
        // Robolectric's UTC follows uptime; elapsedRealtime has a separate backing clock.
        val clock =
            ShadowPausedSystemClock::class.java.getDeclaredField("currentUptimeNs").apply { isAccessible = true }
        val previous = clock.getLong(null)
        clock.setLong(null, millis * 1_000_000)
        try {
            assertEquals(iso(millis), iso())
            return body()
        } finally {
            clock.setLong(null, previous)
        }
    }

    private fun telemetry(
        speed: Double? = null,
        epoch: String = "fixture",
        acquiredAt: Long = SystemClock.elapsedRealtime(),
    ) {
        RecordingEngine::class
            .java
            .getDeclaredMethod(
                "telemetry",
                Map::class.java,
                CycProtocol.Identity::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                String::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                engine,
                mapOf("humanPowerW" to 120.0) + (speed?.let { mapOf("controllerSpeedMps" to it) } ?: emptyMap()),
                CycProtocol.Identity("X6", "20250725", "5.3"),
                epoch,
                acquiredAt,
                iso(),
            )
    }

    @Test
    fun telemetryAcquiredBeforeStartOrResumeKeepsItsOwnPhase() = command {
        val beforeStart = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val id = start()
        telemetry(acquiredAt = beforeStart)
        engine.flush()
        assertEquals(0L, engine.store.count(id))
        advanceTo(3)
        engine.action("pause", id)
        advanceTo(4)
        val paused = SystemClock.elapsedRealtime()
        advanceTo(5)
        engine.action("resume", id)
        telemetry(acquiredAt = paused)
        advanceTo(6)
        telemetry()
        engine.flush()
        val rows = engine.store.page(id).filter { it.kind == "telemetry" }
        engine.action("stop", id)
        assertEquals(listOf(false, true), rows.map { it.active })
    }

    private fun stream(name: String): Map<*, *> = (engine.state()["streams"] as Map<*, *>)[name] as Map<*, *>

    @Test
    fun liveMonitorKeepsAcquisitionTimesAcrossClockJumpsAndRereads() = command {
        val base = 1767225600000L
        val request = mapOf("source" to "live", "metrics" to listOf("humanPowerW"), "generation" to 1)
        val live = engine.source(BridgeInputs.monitor(MonitorOperation.Describe, request).target)
        val empty =
            engine.monitor.query(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to live, "generation" to 0, "sinceRevision" to "0") + request,
                ),
            )
        assertEquals(mapOf("humanPowerW" to null), empty["liveAcquiredAt"])
        val description =
            engine.monitor.query(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Describe,
                    mapOf("source" to "workout", "id" to live, "generation" to 0, "sinceRevision" to "0") + request,
                ),
            )
        assertTrue(description.containsKey("nowSeconds"))
        assertTrue(description.containsKey("monotonicAt"))
        assertFalse(description.containsKey("liveAcquiredAt"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val acquired = SystemClock.elapsedRealtime() / 1000.0
        withUtc(base + 3600000) {
            telemetry()
            engine.flush()
        }
        val first =
            engine.monitor.query(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to live, "generation" to 0, "sinceRevision" to "0") + request,
                ),
            )
        assertEquals(mapOf("humanPowerW" to acquired), first["liveAcquiredAt"])
        assertEquals(acquired, first.num("monotonicAt"), 0.0)
        val original = engine.store.page(live).single()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        withUtc(base - 3600000) {
            for (kind in listOf("describe", "latest")) {
                val response =
                    engine.monitor.query(
                        live,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf("source" to "workout", "id" to live, "generation" to 0, "sinceRevision" to "0") +
                                request,
                        ),
                    )
                assertEquals(first.num("nowSeconds") + 6, response.num("nowSeconds"), 0.0)
                assertEquals(acquired + 6, response.num("monotonicAt"), 0.0)
                assertFalse(response.containsKey("liveAgeSeconds"))
                if (kind == "latest") assertEquals(first["liveAcquiredAt"], response["liveAcquiredAt"])
            }
        }
        assertEquals(original, engine.store.page(live).single())
        val id = withUtc(base) { start(true) }
        val origin = SystemClock.elapsedRealtime() / 1000.0
        assertEquals(
            mapOf("humanPowerW" to null),
            engine.monitor
                .query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Latest,
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
                    ),
                )["liveAcquiredAt"],
        )
        advanceTo(2)
        withUtc(base + 7200000) {
            telemetry()
            location(2.0)
            engine.flush()
        }
        advanceTo(7)
        withUtc(base - 7200000) {
            location(1.0)
            engine.flush()
            for (kind in listOf("describe", "latest")) {
                val response =
                    engine.monitor.query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                request,
                        ),
                    )
                assertEquals(7.0, response.num("nowSeconds"), 0.0)
                assertEquals(origin + 7, response.num("monotonicAt"), 0.0)
                if (kind == "latest") assertEquals(mapOf("humanPowerW" to origin + 2), response["liveAcquiredAt"])
            }
            engine.action("pause", id)
        }
        advanceTo(8)
        val paused =
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
                ),
            )
        assertEquals(8.0, paused.num("nowSeconds"), 0.0)
        assertEquals(mapOf("humanPowerW" to origin + 2), paused["liveAcquiredAt"])
        withUtc(base - 10800000) { engine.action("stop", id) }
        repeat(2) {
            for (kind in listOf("describe", "latest")) {
                val historical =
                    engine.monitor.query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                request +
                                ("source" to "workout"),
                        ),
                    )
                assertFalse(historical.containsKey("liveAcquiredAt"))
                assertFalse(historical.containsKey("liveAgeSeconds"))
                assertFalse(historical.containsKey("nowSeconds"))
                assertFalse(historical.containsKey("monotonicAt"))
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        }
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
        assertEquals(empty, engine.monitor.query(id, request)["points"])
        val db = engine.store.writableDatabase
        db.execSQL(
            "CREATE TEMP TRIGGER fail_live_commit BEFORE UPDATE OF checkpoint_at ON rides BEGIN SELECT RAISE(ABORT,'injected commit failure'); END"
        )
        try {
            assertThrows(SQLiteException::class.java) { engine.flush() }
            assertEquals(0L, engine.store.count(id))
            val failed = engine.monitor.query(id, request)
            assertEquals(empty, failed["points"])
            assertEquals(empty, failed["liveAcquiredAt"])
        } finally {
            db.execSQL("DROP TRIGGER fail_live_commit")
        }
        engine.flush()
        val committed = engine.monitor.query(id, request)
        assertEquals(
            mapOf("humanPowerW" to acquiredAt / 1000.0, "speedMps" to acquiredAt / 1000.0),
            committed["liveAcquiredAt"],
        )
        assertTrue((committed["points"] as Map<*, *>).values.all { it != null })
        advanceTo(4)
        telemetry(acquiredAt = acquiredAt)
        location(1.0, speed = 8f)
        engine.flush()
        val repeated = engine.monitor.query(id, request)
        assertEquals(committed["points"], repeated["points"])
        assertEquals(committed["liveAcquiredAt"], repeated["liveAcquiredAt"])
    }

    @Test
    fun startingAndStoppingRidesClearsLiveMonitorEvidence() = command {
        fun latest(id: String) =
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "metrics" to listOf("humanPowerW")),
                ),
            )
        val empty = mapOf("humanPowerW" to null)
        val live = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry()
        engine.flush()
        assertNotEquals(empty, latest(live)["points"])
        val ride = start()
        assertEquals(empty, latest(live)["points"])
        assertEquals(empty, latest(ride)["points"])
        advanceTo(1)
        telemetry()
        engine.flush()
        assertNotEquals(empty, latest(ride)["points"])
        engine.action("stop", ride)
        assertEquals(empty, latest(live)["points"])
        assertEquals(empty, latest(live)["liveAcquiredAt"])
        assertNotEquals(empty, latest(ride)["points"])
        assertFalse(latest(ride).containsKey("liveAcquiredAt"))
        val next = start()
        assertEquals(empty, latest(next)["points"])
        assertEquals(empty, latest(next)["liveAcquiredAt"])
    }

    private fun times(id: String) =
        engine.store.readableDatabase
            .rawQuery("SELECT time FROM observations WHERE ride=? ORDER BY id", arrayOf(id))
            .use {
                buildList { while (it.moveToNext()) add(it.getDouble(0)) }
            }

    @Test
    fun manualConnectStartsAFreshLiveSessionFromZero() = command {
        val previous = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        telemetry()
        engine.flush()
        assertEquals(1L, engine.store.count(previous))
        engine.connect(BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to 4))) {}
        val live = engine.source(MonitorTarget.Live)
        assertNotEquals(previous, live)
        assertEquals(0L, engine.store.count(previous))
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
        assertThrows(IllegalStateException::class.java) { engine.connect(input) { fail("Unexpected admission") } }
        assertEquals(live, engine.source(MonitorTarget.Live))
        assertEquals(1L, engine.store.count(live))
        engine.bluetooth.disconnect()
    }

    @Test
    fun liveSessionKeepsRecordingThroughARideAndContinuesAfterIt() = command {
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
        assertEquals(3, times(live).size)
        assertEquals(times(live), times(live).sorted())
        assertEquals(listOf(1.0), times(ride))
    }

    @Test
    fun liveMonitorUsesEachMetricsOwnGpsOrControllerObservation() = command {
        val id = start(true)
        val origin = SystemClock.elapsedRealtime() / 1000.0
        val request =
            mapOf(
                "metrics" to listOf("humanPowerW", "cadenceRpm", "horizontalAccuracyM", "speedMps", "distanceMeters"),
                "distanceSource" to "gps:phone",
            )
        advanceTo(1)
        location(1.0, speed = 2f)
        advanceTo(2)
        location(2.0, speed = 3f)
        engine.flush()
        val gpsOnly =
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
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
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
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
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                        request +
                        ("distanceSource" to "controller"),
                ),
            )
        assertEquals(origin + 5, (controller["liveAcquiredAt"] as Map<*, *>)["distanceMeters"])
        advanceTo(9)
        location(7.0)
        engine.flush()
        val reread =
            engine.monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
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
        command { engine.listeners.add { event, payload -> if (event == "onSample") delivered.add(payload) } }
        for (recording in listOf(false, true)) {
            try {
                val (id, acquiredAt, elapsed) =
                    command {
                        val id = if (recording) start() else engine.source(MonitorTarget.Live)
                        val origin = field(if (recording) "startClock" else "liveStart") as Long
                        val adapter = engine.context.getSystemService(BluetoothManager::class.java).adapter
                        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
                        engine.bluetooth.connect("02:00:00:00:00:01", 4) {}
                        fun bluetoothField(name: String) =
                            CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }
                        bluetoothField("identity").set(engine.bluetooth, identity)
                        bluetoothField("pending").set(engine.bluetooth, CycProtocol.Read.TELEMETRY)
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                        val acquiredAt = SystemClock.elapsedRealtime()
                        bluetoothField("pendingSince").set(engine.bluetooth, acquiredAt - 100)
                        val gatt = bluetoothField("gatt").get(engine.bluetooth) as BluetoothGatt
                        val notify =
                            BluetoothGattCharacteristic(
                                UUID.fromString(CycProtocol.NOTIFY),
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ,
                            )
                        delivered.clear()
                        timestamp = iso()
                        shadowOf(gatt).gattCallback.onCharacteristicChanged(gatt, notify, frame)
                        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                        assertTrue(delivered.isEmpty())
                        assertEquals(0L, engine.store.count(id))
                        Triple(id, acquiredAt / 1000.0, (acquiredAt - origin) / 1000.0)
                    }
                command {
                    engine.flush()
                    val sample = delivered.single()
                    assertEquals(acquiredAt, sample.num("acquiredAtMonotonic"), 0.0)
                    assertEquals(elapsed, sample.num("elapsedSeconds"), 0.0)
                    assertEquals(timestamp, sample["timestamp"])
                    val original = engine.store.page(id).single()
                    assertEquals(elapsed, original.time, 0.0)
                    assertEquals(timestamp, original.timestamp)
                    assertEquals(2.0, engine.bluetooth.diagnostics()["lastSampleAgeSeconds"])
                    if (recording) {
                        assertEquals("receiving", stream("cyc")["status"])
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
                    val latest = engine.monitor.query(id, request)
                    assertEquals(mapOf("humanPowerW" to acquiredAt), latest["liveAcquiredAt"])
                    assertEquals(elapsed + 2, latest.num("nowSeconds"), 0.0)
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                    if (recording) {
                        assertEquals("stale", stream("cyc")["status"])
                    }
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                    val reread = engine.monitor.query(id, request)
                    assertEquals(latest["liveAcquiredAt"], reread["liveAcquiredAt"])
                    assertEquals(6.0, reread.num("monotonicAt") - acquiredAt, 0.0)
                    assertEquals(original, engine.store.page(id).single())
                    if (recording) engine.action("stop", id)
                }
            } finally {
                command { engine.bluetooth.disconnect() }
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
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
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
                engine.monitor.query(
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
                mapOf("humanPowerW" to acquiredAt, "speedMps" to acquiredAt, "distanceMeters" to acquiredAt),
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
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
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
            assertEquals(8.0, SystemClock.elapsedRealtime() / 1000.0 - queued.num("acquiredAtMonotonic"), 0.0)
            assertEquals(2.0, queued.num("elapsedSeconds"), 0.0)
            assertEquals(iso(1767222000000L), queued["timestamp"])
            ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
            assertEquals(17.0, SystemClock.elapsedRealtime() / 1000.0 - queued.num("acquiredAtMonotonic"), 0.0)
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
        val original = engine.store.page(id).single()
        val db = engine.store.writableDatabase
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
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
                                mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                    request,
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
                engine.monitor.query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                        mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") + request,
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
        assertEquals(original, engine.store.page(id).single())
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
        telemetry(8.0, "reconnected")
        advanceTo(9)
        location(7.5)
        telemetry(10.0, "reconnected")
        advanceTo(10)
        location(10.0)
        telemetry(10.0, "reconnected")
        advanceTo(11)
        location(11.0)
        engine.action("stop", id)
        val rows = engine.store.page(id)
        assertEquals(
            listOf(1.0, 2.0, 2.5, 6.0, 7.0, 8.0, 7.5, 10.0, 11.0),
            rows.filter { it.kind == "location" }.map { it.time },
        )
        assertTrue(rows.first { it.time == 2.0 }.active)
        assertFalse(rows.any { it.time == 4.0 })
        assertDistanceReplay(engine.store, id)
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
        assertEquals(iso(base + 3600000), engine.store.page(id).single().timestamp)
    }

    @Test
    fun captureCommitSurvivesRestartBeforeTheNextPeriodicCheckpoint() {
        val base = 1767225600000L
        val retained = RideTiming(6.0, 4.0, iso(base - 3600000))
        val id = command {
            val id = withUtc(base) { start(true) }
            advanceTo(3)
            withUtc(base + 3600000) { engine.action("pause", id) }
            advanceTo(5)
            withUtc(base + 7200000) { engine.action("resume", id) }
            advanceTo(6)
            val delivered = mutableListOf<Payload>()
            engine.listeners.add { event, payload -> if (event == "onSample") delivered.add(payload) }
            withUtc(base - 3600000) {
                location(6.0)
                repeat(7) { telemetry() }
            }
            assertEquals(1, delivered.size)
            assertEquals(0, delivered.single()["interruptionIndex"])
            assertEquals(8L, engine.store.count(id))
            assertEquals(retained, engine.store.timing(id))
            id
        }
        val originals = command { engine.store.page(id) }
        shutdown()
        ShadowSystemClock.advanceBy(Duration.ofHours(2))
        engine = RecordingEngine.get(RuntimeEnvironment.getApplication())
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertNull(engine.state()["id"])
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertTrue(engine.store.metadata(id).flag("interrupted"))
            assertEquals(retained.timestamp, engine.store.metadata(id)["endedAt"])
            assertEquals(retained, engine.store.timing(id))
            assertEquals(originals, engine.store.page(id))
            assertEquals(RideEvent(6.0, "stop", retained.timestamp), engine.store.events(id).last())
            for (kind in listOf("describe", "latest")) {
                val historical =
                    engine.monitor.query(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf("source" to "workout", "id" to id, "generation" to 0, "sinceRevision" to "0") +
                                mapOf("metrics" to listOf("humanPowerW")),
                        ),
                    )
                assertFalse(historical.containsKey("liveAcquiredAt"))
                assertFalse(historical.containsKey("nowSeconds"))
                assertFalse(historical.containsKey("monotonicAt"))
            }
            engine.recover(id)
            assertNull(engine.state()["id"])
            val error = assertThrows(IllegalArgumentException::class.java) { engine.action("resume", id) }
            assertEquals("The selected workout changed. Refresh before trying again.", error.message)
            assertThrows(IllegalStateException::class.java) { engine.action("stop", id) }
            assertEquals(retained, engine.store.timing(id))
        }
    }

    @Test
    fun storageFailureSealsThePairedCommittedCutoffAndRollsBackTheCaptureTail() = command {
        val base = 1767225600000L
        val id = withUtc(base) { start(true) }
        advanceTo(2)
        withUtc(base + 3600000) { engine.action("pause", id) }
        advanceTo(4)
        withUtc(base - 3600000) { engine.action("resume", id) }
        advanceTo(5)
        withUtc(base - 7200000) {
            location(5.0)
            telemetry()
            engine.flush()
        }
        val retained = RideTiming(5.0, 3.0, iso(base - 7200000))
        val originals = engine.store.page(id)
        assertEquals(retained, engine.store.timing(id))
        val db = engine.store.writableDatabase
        db.execSQL(
            "CREATE TEMP TRIGGER fail_checkpoint BEFORE UPDATE OF checkpoint_at ON rides WHEN NEW.phase='running' BEGIN SELECT RAISE(ABORT,'injected checkpoint failure'); END"
        )
        try {
            advanceTo(10)
            withUtc(base + 7200000) {
                location(10.0)
                telemetry()
                val tick = field("tick") as Runnable
                tick.run()
                engine.handler.removeCallbacks(tick)
            }
            assertNull(engine.state()["id"])
            assertTrue(engine.state().str("error").contains("storage could not save"))
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertTrue(engine.store.metadata(id).flag("interrupted"))
            assertEquals(retained, engine.store.timing(id))
            assertEquals(retained.timestamp, engine.store.metadata(id)["endedAt"])
            assertEquals(originals, engine.store.page(id))
            assertEquals(RideEvent(5.0, "stop", retained.timestamp), engine.store.events(id).last())
            assertThrows(IllegalStateException::class.java) { engine.action("stop", id) }
            engine.store.recoverOrphans()
            assertEquals(retained, engine.store.timing(id))
        } finally {
            db.execSQL("DROP TRIGGER fail_checkpoint")
        }
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
            val state = engine.start(BridgeInputs.ride(mapOf("indoor" to false) + requested))
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
                    engine.start(BridgeInputs.ride(input))
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
        val input = BridgeInputs.ride(mapOf("indoor" to true, "saveToHealth" to false, "example" to true))
        val state = engine.start(input)
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
                BridgeInputs.monitor(MonitorOperation.Describe, mapOf("source" to "unknown", "generation" to 0)).target
            )
        }
        val request =
            BridgeInputs.monitor(
                MonitorOperation.Latest,
                mapOf(
                    "source" to "workout",
                    "id" to id,
                    "generation" to 0,
                    "metrics" to listOf("heartRateBpm", "healthSpeedMps", "activeEnergyKcal"),
                ),
            )
        assertEquals(
            mapOf("heartRateBpm" to null, "healthSpeedMps" to null, "activeEnergyKcal" to null),
            engine.monitor.query(id, request)["points"],
        )
    }

    @Test
    fun methodResultsAndEventsUseTheSameSnapshotWithExplicitNulls() = command {
        val emitted = mutableListOf<Payload>()
        val listener: (String, Payload) -> Unit = { name, value -> if (name == "onWorkoutState") emitted.add(value) }
        engine.listeners.add(listener)
        try {
            val result = engine.start(BridgeInputs.ride(mapOf("indoor" to true, "saveToHealth" to false)))
            assertEquals(result.keys, emitted.last().keys)
            for (field in result.keys - "timerSeconds") assertEquals(field, result[field], emitted.last()[field])
            for (field in
                listOf(
                    "collectionRevision",
                    "sealRevision",
                    "verifiedSealRevision",
                    "finalizationState",
                    "recoveryMessage",
                )) {
                assertTrue(result.containsKey(field))
                assertNull(result[field])
            }
            assertEquals(mapOf("installed" to false), result["watch"])
            assertEquals(setOf("status"), stream("cyc").keys)
            assertEquals(setOf("status", "source", "accuracyMeters"), stream("gps").keys)
        } finally {
            engine.listeners.remove(listener)
        }
    }

    @Test
    fun historyRevisionExhaustionRejectsWritesBeforeStartingStoppingOrDeleting() = command {
        val revision = RecordingEngine::class.java.getDeclaredField("historyRevision").apply { isAccessible = true }
        val prior = revision.getLong(engine)
        try {
            revision.setLong(engine, RideStore.MAX_SAFE_REVISION)
            assertThrows(SQLiteException::class.java) { start() }
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
            revision.setLong(engine, RideStore.MAX_SAFE_REVISION - 1)
            val id = start()
            assertEquals("9007199254740991", engine.state()["historyRevision"])
            assertThrows(SQLiteException::class.java) { engine.action("stop", id) }
            assertThrows(SQLiteException::class.java) { engine.action("discard", id) }
            assertEquals(id, engine.state()["id"])
            assertEquals("running", engine.store.metadata(id)["phase"])
            revision.setLong(engine, prior)
            engine.action("stop", id)
            revision.setLong(engine, RideStore.MAX_SAFE_REVISION)
            assertThrows(SQLiteException::class.java) { engine.delete(id) }
            assertEquals("completed", engine.store.metadata(id)["phase"])
        } finally {
            revision.setLong(engine, prior)
        }
    }

    @Test
    fun ticksDoNotCountObservationsAndStopSealsTheOriginalCount() = command {
        val id = start(true)
        val queries = mutableListOf<String>()
        val db = engine.store.writableDatabase
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
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
            assertEquals(0.0, engine.store.metadata(id).num("eventCount"), 0.0)
            engine.action("pause", id)
            engine.action("resume", id)
            engine.action("lap", id)
            assertEquals(0, countQueries())
            engine.action("stop", id)
            assertEquals(1, countQueries())
            assertEquals(3.0, engine.store.metadata(id).num("eventCount"), 0.0)
            assertEquals(3, engine.store.page(id).size)
        } finally {
            factory.set(db, previous)
            engine.handler.removeCallbacks(tick)
        }
    }

    @Test
    fun notificationStateChangesOnlyWithRidePhaseIdentityOrTimerAnchors() = command {
        val id = start()
        val running = engine.notificationState
        val tick = field("tick") as Runnable
        advanceTo(5)
        tick.run()
        engine.handler.removeCallbacks(tick)
        assertSame(running, engine.notificationState)
        engine.action("lap", id)
        assertSame(running, engine.notificationState)
        engine.action("pause", id)
        val paused = engine.notificationState
        assertNotEquals(running, paused)
        assertEquals(5000L, paused.timerMillis(SystemClock.elapsedRealtime()))
        advanceTo(10)
        tick.run()
        engine.handler.removeCallbacks(tick)
        assertSame(paused, engine.notificationState)
        assertEquals(5000L, paused.timerMillis(SystemClock.elapsedRealtime()))
        engine.action("resume", id)
        val resumed = engine.notificationState
        assertNotEquals(paused, resumed)
        advanceTo(12)
        assertEquals(7000L, resumed.timerMillis(SystemClock.elapsedRealtime()))
        engine.action("stop", id)
        assertNull(engine.notificationState.id)
        assertEquals("idle", engine.notificationState.phase)
        val next = start()
        assertEquals(next, engine.notificationState.id)
        assertNotEquals(running, engine.notificationState)
    }

    @Test
    fun streamStatusesDescribeSelectionFreshnessAccuracyAndPermission() = command {
        assertEquals("off", stream("cyc")["status"])
        assertEquals("off", stream("gps")["status"])
        assertEquals("off", stream("heartRate")["status"])
        val id = start(true)
        assertEquals("waiting", stream("cyc")["status"])
        assertEquals("waiting", stream("gps")["status"])
        advanceTo(1)
        RecordingEngine::class
            .java
            .getDeclaredMethod(
                "telemetry",
                Map::class.java,
                CycProtocol.Identity::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                String::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                engine,
                mapOf("humanPowerW" to 120.0),
                CycProtocol.Identity("X6", "20250725", "5.3"),
                "fixture",
                SystemClock.elapsedRealtime(),
                iso(),
            )
        location(1.0, 50f)
        assertEquals("receiving", stream("cyc")["status"])
        assertEquals("receiving", stream("gps")["status"])
        assertEquals(50.0, stream("gps")["accuracyMeters"])
        advanceTo(2)
        location(2.0, 50.1f)
        assertEquals("weak", stream("gps")["status"])
        advanceTo(4)
        assertEquals("stale", stream("cyc")["status"])
        engine.action("pause", id)
        assertEquals("paused", stream("gps")["status"])
        engine.action("resume", id)
        advanceTo(13)
        assertEquals("stale", stream("gps")["status"])
        (field("locationListener") as LocationListener).onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertEquals("unavailable", stream("gps")["status"])
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals("denied", stream("gps")["status"])
        engine.action("stop", id)
        assertEquals("off", stream("gps")["status"])
        assertEquals("off", stream("cyc")["status"])
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val next = start(true)
        assertEquals("waiting", stream("gps")["status"])
        engine.action("discard", next)
        start()
        assertEquals("off", stream("gps")["status"])
    }

    @Test
    fun failedPauseAndResumeLeaveMemoryEventsAndStoredTimingAtTheCommittedPhase() = command {
        val id = start()
        val db = engine.store.writableDatabase
        for ((action, seconds) in listOf("pause" to 10L, "resume" to 12L)) {
            val before = if (action == "pause") "running" else "paused"
            val after = if (action == "pause") "paused" else "running"
            advanceTo(seconds)
            val events = engine.store.events(id)
            val timing = engine.store.timing(id)
            val segment = field("segment")
            db.execSQL(
                "CREATE TEMP TRIGGER fail_phase BEFORE UPDATE ON rides WHEN NEW.phase='$after' BEGIN SELECT RAISE(ABORT,'injected phase failure'); END"
            )
            try {
                assertThrows(SQLiteException::class.java) { engine.action(action, id) }
                assertEquals(before, engine.state()["phase"])
                assertEquals(before, engine.store.metadata(id)["phase"])
                assertEquals(timing, engine.store.timing(id))
                assertEquals(events, engine.store.events(id))
                assertEquals(segment, field("segment"))
            } finally {
                db.execSQL("DROP TRIGGER fail_phase")
            }
            assertEquals(after, engine.action(action, id)["phase"])
            assertEquals(after, engine.store.metadata(id)["phase"])
            assertEquals(10.0, engine.store.timing(id).timer, 0.0)
        }
        advanceTo(13)
        assertEquals(11.0, engine.state().num("timerSeconds"), 0.0)
    }

    @Test
    fun terminalHealthClockFailureEndsOwnershipSurvivesRestartAndCannotBeRepaired() {
        val base = 1767225600000L
        val id = command {
            val id = withUtc(base) { start(true) }
            val options = engine.store.metadata(id) + mapOf("saveToHealth" to true)
            engine.store.update(id, "running", RideTiming(0.0, 0.0, iso(base)), options)
            RecordingEngine::class
                .java
                .getDeclaredField("options")
                .apply { isAccessible = true }
                .set(engine, (field("options") as RideOptions).copy(saveToHealth = true))
            advanceTo(2)
            withUtc(base + 2000) {
                telemetry()
                location(2.0)
            }
            advanceTo(3)
            withUtc(base - 3600000) {
                telemetry()
                location(3.0)
            }
            advanceTo(4)
            withUtc(base - 3599000) { engine.action("stop", id) }
            assertNull(engine.state()["id"])
            assertEquals("idle", engine.state()["phase"])
            id
        }
        (field("healthWork") as ExecutorService).submit {}.get(20, TimeUnit.SECONDS)
        val original = command {
            val metadata = engine.store.metadata(id)
            assertEquals("unavailable", metadata["healthKitState"])
            assertTrue((metadata["warnings"] as List<*>).single().toString().contains("clock cutoff"))
            assertTrue(engine.store.pendingHealthJobs().isEmpty())
            val next = start()
            assertNotEquals(id, next)
            engine.recover(id)
            assertEquals(next, engine.state()["id"])
            assertEquals(metadata, engine.store.metadata(id))
            engine.action("stop", next)
            metadata
        }
        shutdown()
        engine = RecordingEngine.get(RuntimeEnvironment.getApplication())
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals(original, engine.store.metadata(id))
            assertEquals(4L, engine.store.count(id))
            assertEquals(RideTiming(4.0, 4.0, iso(base - 3599000)), engine.store.timing(id))
            engine.recover(id)
            assertEquals(original, engine.store.metadata(id))
            assertTrue(engine.store.pendingHealthJobs().isEmpty())
            assertTrue((field("healthPending") as Set<*>).isEmpty())
            val exporter = RideExport(engine.context, engine.store, engine.distance, engine.monitor)
            assertTrue(java.io.File(java.net.URI(exporter.fit(id, "auto"))).length() > 0)
            assertTrue(java.io.File(java.net.URI(exporter.archive(id))).length() > 0)
        }
    }

    @Test
    fun retryQueuesOnlyNotSavedAndLateCompletionCannotReplaceTerminalOutcome() {
        val terminal = HealthExportResult("unavailable", reason = "Terminal clock cutoff.")
        val id = command {
            val id = engine.store.create(RideOptions(indoor = false, saveToHealth = true, recordGPS = false))
            val start = java.time.Instant.parse(engine.store.metadata(id).str("startedAt"))
            engine.store.seal(id, RideTiming(10.0, 10.0, start.plusSeconds(10).toString()))
            engine.store.healthStatus(id, HealthExportResult("notSaved", reason = "Retryable write failure."))
            engine.recover(id)
            assertEquals("pending", engine.store.metadata(id)["healthKitState"])
            (field("healthWork") as ExecutorService).submit {}.get(20, TimeUnit.SECONDS)
            engine.store.healthStatus(id, terminal)
            id
        }
        command {
            assertEquals("unavailable", engine.store.metadata(id)["healthKitState"])
            assertEquals(listOf(terminal.reason), engine.store.metadata(id)["warnings"])
            engine.recover(id)
            assertEquals("unavailable", engine.store.metadata(id)["healthKitState"])
            assertTrue((field("healthPending") as Set<*>).isEmpty())
        }
    }

    @Test
    fun telemetryAndDelayedGpsKeepElapsedThroughClockJumpsAndHealthOmitsOutsideUtc() = command {
        val base = 1767225600000L
        val id = withUtc(base) { start(true) }
        advanceTo(2)
        withUtc(base + 3600000) {
            telemetry()
            location(2.0)
        }
        advanceTo(3)
        withUtc(base - 3600000) {
            telemetry()
            location(3.0)
        }
        advanceTo(4)
        withUtc(base + 3000) {
            telemetry()
            location(3.5)
            location(3.5)
        }
        advanceTo(5)
        withUtc(base + 5000) { engine.action("stop", id) }
        val originals = engine.store.page(id)
        assertEquals(listOf(2.0, 2.0, 3.0, 3.0, 4.0, 3.5, 3.5), originals.map { it.time })
        assertEquals(
            listOf(
                    base + 3600000,
                    base + 3600000,
                    base - 3600000,
                    base - 3600000,
                    base + 3000,
                    base + 3000,
                    base + 3000,
                )
                .map { iso(it) },
            originals.map { it.timestamp },
        )
        engine.store.update(id, "completed", engine.store.timing(id), mapOf("saveToHealth" to true))
        val records = mutableListOf<androidx.health.connect.client.records.Record>()
        val result = kotlinx.coroutines.runBlocking { engine.health.writeRide(id) { records.addAll(it) } }
        assertEquals("saved", result.state)
        assertEquals(7, result.omitted)
        assertEquals(4, result.written)
        assertEquals(originals, engine.store.page(id))
        val sample =
            records.filterIsInstance<androidx.health.connect.client.records.PowerRecord>().single().samples.single()
        assertEquals(java.time.Instant.ofEpochMilli(base + 3000), sample.time)
    }

    @Test
    fun failedInitialHealthJobRollsBackStopAndCommittedJobSurvivesRestart() {
        val id = command {
            val id = start()
            val options = engine.store.metadata(id) + mapOf("saveToHealth" to true)
            engine.store.update(id, "running", RideTiming(0.0, 0.0, iso()), options)
            RecordingEngine::class
                .java
                .getDeclaredField("options")
                .apply { isAccessible = true }
                .set(engine, (field("options") as RideOptions).copy(saveToHealth = true))
            advanceTo(10)
            val db = engine.store.writableDatabase
            db.execSQL(
                "CREATE TEMP TRIGGER fail_health BEFORE UPDATE OF metadata ON rides WHEN NEW.metadata LIKE '%\"healthKitState\":\"pending\"%' BEGIN SELECT RAISE(ABORT,'injected health failure'); END"
            )
            try {
                assertThrows(SQLiteException::class.java) { engine.action("stop", id) }
                assertEquals("running", engine.state()["phase"])
                assertEquals(id, engine.state()["id"])
                assertEquals("running", engine.store.metadata(id)["phase"])
                assertEquals("notRequested", engine.store.metadata(id)["healthKitState"])
                assertFalse(engine.store.events(id).any { it.action == "stop" })
            } finally {
                db.execSQL("DROP TRIGGER fail_health")
            }
            (field("healthWork") as ExecutorService).shutdown()
            assertEquals("idle", engine.action("stop", id)["phase"])
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertEquals("pending", engine.store.metadata(id)["healthKitState"])
            (field("tick") as Runnable).run()
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertEquals(listOf(id), engine.store.pendingHealthJobs())
            id
        }
        shutdown()
        engine = RecordingEngine.get(RuntimeEnvironment.getApplication())
        engine.awaitReady()
        (field("healthWork") as ExecutorService).apply {
            shutdown()
            assertTrue(awaitTermination(20, TimeUnit.SECONDS))
        }
        command {
            assertEquals("idle", engine.state()["phase"])
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertEquals("notSaved", engine.store.metadata(id)["healthKitState"])
            assertTrue(engine.store.pendingHealthJobs().isEmpty())
        }
    }

    @Test
    fun delayedLocationsKeepTheirAcquisitionPhaseAndSegment() = command {
        val id = start(true)
        advanceTo(10)
        engine.action("pause", id)
        advanceTo(12)
        engine.action("resume", id)
        advanceTo(13)
        location(11.0)
        location(12.0)
        location(9.0)
        location(10.0)
        engine.flush()
        val rows = engine.store.page(id).associateBy { it.time }
        assertEquals(setOf(9.0, 12.0), rows.keys)
        assertTrue(rows.getValue(12.0).active)
        assertTrue(rows.getValue(9.0).active)
        assertNotEquals(rows.getValue(9.0).segment, rows.getValue(12.0).segment)
    }

    @Test
    fun endingARideCancelsOnlyAnActiveBluetoothRecovery() = command {
        for (action in listOf("stop", "discard", "interrupted")) {
            for (status in listOf("reconnecting", "connected", "connecting")) {
                val id = start()
                CycBluetooth::class
                    .java
                    .getDeclaredField("desired")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, "02:00:00:00:00:01")
                CycBluetooth::class
                    .java
                    .getDeclaredField("state")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, mapOf("status" to status))
                var retried = false
                val retry = Runnable { retried = true }
                engine.handler.postDelayed(retry, 1000)
                CycBluetooth::class
                    .java
                    .getDeclaredField("retry")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, retry)
                if (action == "interrupted") {
                    RecordingEngine::class
                        .java
                        .getDeclaredMethod("captureFailed", Exception::class.java)
                        .apply { isAccessible = true }
                        .invoke(engine, SQLiteException("fixture failure"))
                } else engine.action(action, id)
                assertEquals(if (status == "reconnecting") "idle" else status, engine.bluetooth.state["status"])
                assertEquals(status != "reconnecting", engine.handler.hasCallbacks(retry))
                assertFalse(retried)
                engine.bluetooth.disconnect()
            }
        }
    }

    @Test
    fun pauseStopsGpsAndOnlyFixesAcquiredInActiveIntervalsAreAdmitted() = command {
        val locations = shadowOf(engine.context.getSystemService(LocationManager::class.java))
        val id = start(true)
        assertEquals(1, locations.getLocationRequests(LocationManager.GPS_PROVIDER).size)
        advanceTo(1)
        location(1.0, accuracy = 5f)
        advanceTo(3)
        engine.action("pause", id)
        assertTrue(locations.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        advanceTo(4)
        location(2.0, accuracy = 6f)
        location(3.0, accuracy = 80f)
        location(4.0, accuracy = 90f)
        engine.flush()
        assertEquals(6.0, stream("gps")["accuracyMeters"])
        assertEquals(listOf(1.0, 2.0), engine.store.page(id).map { it.time })
        advanceTo(5)
        engine.action("resume", id)
        assertEquals(1, locations.getLocationRequests(LocationManager.GPS_PROVIDER).size)
        location(4.5, accuracy = 100f)
        assertEquals(6.0, stream("gps")["accuracyMeters"])
        location(5.0, accuracy = 7f)
        engine.flush()
        assertEquals(listOf(1.0, 2.0, 5.0), engine.store.page(id).map { it.time })
        assertTrue(engine.store.page(id).all { it.active })
    }

    @Test
    fun repeatedAndLateControlsRejectWithTheirPhaseErrorsWithoutWritingEvents() = command {
        val errors =
            mapOf(
                "pause" to "The workout is not ready to pause.",
                "resume" to "The owner must be paused before resuming.",
                "lap" to "Start or resume the workout before marking a lap.",
            )
        fun rejected(action: String, id: String?) {
            val error = assertThrows(IllegalStateException::class.java) { engine.action(action, id) }
            assertEquals(errors[action], error.message)
        }
        errors.keys.forEach { rejected(it, null) }
        val id = start()
        rejected("resume", id)
        advanceTo(1)
        engine.action("lap", id)
        engine.action("lap", id)
        assertEquals(2, engine.store.events(id).count { it.action == "lap" })
        advanceTo(2)
        engine.action("pause", id)
        val revision = engine.store.revision(id)
        rejected("pause", id)
        rejected("lap", id)
        assertEquals(revision, engine.store.revision(id))
        advanceTo(3)
        engine.action("resume", id)
        rejected("resume", id)
        engine.action("stop", id)
        errors.keys.forEach { rejected(it, id) }
        val next = start()
        errors.keys.forEach { action ->
            val error = assertThrows(IllegalArgumentException::class.java) { engine.action(action, id) }
            assertEquals("The selected workout changed. Refresh before trying again.", error.message)
        }
        assertEquals("idle", engine.action("discard", next)["phase"])
    }

    @Test
    fun nativeOwnerSurvivesMissingUIListenersAndRejectsStaleCommands() {
        val first = command {
            engine.start(
                BridgeInputs.ride(
                    mapOf("indoor" to false) + mapOf("saveToHealth" to false, "recordGPS" to false, "useWatch" to false)
                )
            )
        }
            .str("id")
        command {
            engine.listeners.clear()
            engine.listeners.add { _, _ -> error("UI detached") }
            assertEquals("paused", engine.action("pause", first)["phase"])
            assertEquals("running", engine.action("resume", first)["phase"])
            engine.action("lap", first)
            assertThrows(IllegalArgumentException::class.java) { engine.action("stop", "old-ride") }
            assertThrows(IllegalStateException::class.java) { engine.delete(first) }
            engine.checkBike("synthetic-bike")
            assertThrows(IllegalStateException::class.java) { engine.checkBike("different-bike") }
            assertEquals("idle", engine.action("stop", first)["phase"])
            assertEquals("completed", engine.store.metadata(first)["phase"])
            assertEquals("notRequested", engine.store.metadata(first)["healthKitState"])
            val second =
                engine
                    .start(
                        BridgeInputs.ride(
                            mapOf("indoor" to false) +
                                mapOf("saveToHealth" to false, "recordGPS" to false, "useWatch" to false)
                        )
                    )
                    .str("id")
            engine.action("discard", second)
            assertEquals(1, engine.store.list(CatalogInput()).size)
            assertEquals(first, engine.store.list(CatalogInput()).first()["id"])
            engine.delete(first)
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
        }
    }
}
