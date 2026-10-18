package app.powerlog.bridge

import android.Manifest
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineStartStopTest : RecordingEngineTestBase() {
    @Test
    fun disconnectedBikeRejectsStartWithoutDurableWorkEvenWhenGpsIsEnabled() = command {
        for (status in listOf("idle", "scanning", "connecting", "reconnecting")) {
            setBikeStatus(status)
            for (gps in listOf(false, true)) {
                val failure = assertThrows(IllegalStateException::class.java) { start(gps = gps) }
                assertEquals("$status GPS=$gps", RideFailure.bikeRequired.text, failure.message)
                assertNoFailedStart()
                assertFalse(engine.awaitingForeground)
            }
        }
        setBikeStatus("connected")
        val id = start()
        assertEquals("running", engine.state()["phase"])
        assertEquals(id, engine.store.lifecycleState().ride!!.key.rideId)
    }

    @Test
    fun connectionLossAfterAdmissionDoesNotCancelStartOrBlockFinish() = command {
        RecordingContextImpl.beforeForegroundStart = { setBikeStatus("reconnecting") }
        val id =
            try {
                start()
            } finally {
                RecordingContextImpl.beforeForegroundStart = null
            }
        assertEquals("reconnecting", engine.bluetooth.state["status"])
        assertEquals("running", engine.state()["phase"])
        engine.action("stop", id)
        assertEquals("completed", engine.store.metadata(id)["phase"])
    }

    @Test
    fun failedStartLeavesNoRideOrPendingWorkAndAdmitsTheNextStart() =
        command<Unit> {
            RecordingContextImpl.beforeForegroundStart = {
                throw IllegalStateException("foreground startup unavailable")
            }
            try {
                assertThrows(IllegalStateException::class.java) { start(gps = true) }
            } finally {
                RecordingContextImpl.beforeForegroundStart = null
            }
            assertNoFailedStart()
            assertNull(engine.state()["failure"])
            val id = start()
            assertEquals("running", engine.state()["phase"])
            assertEquals(id, engine.store.lifecycleState().ride!!.key.rideId)
        }

    @Test
    fun startInterruptedBeforeItsReceiptOffersDiscardOnTheNextLaunch() {
        class ProcessDeath : Error()
        RecordingContextImpl.beforeForegroundStart = { throw ProcessDeath() }
        try {
            val failure = runCatching { command { start() } }.exceptionOrNull()
            assertTrue(failure?.cause is ProcessDeath)
        } finally {
            RecordingContextImpl.beforeForegroundStart = null
        }
        command { assertEquals("preparing", engine.store.lifecycleState().ride!!.phase.name) }
        shutdown()
        setup()
        command {
            assertEquals("interrupted", engine.state()["phase"])
            engine.action("discard", engine.state().str("id"))
            assertNoFailedStart()
            val id = start()
            assertEquals("running", engine.store.metadata(id)["phase"])
        }
    }

    @Test
    fun startGuardsRejectWithSharedFailuresBeforeWritingARide() = command {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val location = assertThrows(IllegalStateException::class.java) { start(gps = true) }
        assertEquals(RideFailure.locationDenied.text, location.message)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        healthAccess.grants = emptySet()
        val health = assertThrows(IllegalStateException::class.java) { start(saveToHealth = true) }
        assertEquals(RideFailure.healthDenied.text, health.message)
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        try {
            val bike = assertThrows(IllegalStateException::class.java) { start() }
            assertEquals(RideFailure.bluetoothDenied.text, bike.message)
        } finally {
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        }
        assertNoFailedStart()
    }

    @Test
    fun backgroundHealthWorkNeverBlocksStartOrHoldsASession() {
        val expected = failureSnapshotCase("background Health Connect work")["wire"] as Map<*, *>
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            RideStore.withHealthExecution {
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            command {
                val idle = engine.state()
                for ((key, value) in expected) assertEquals("$key", value, idle[key])
                val id = start()
                val running = engine.state()
                assertEquals("running", running["phase"])
                assertEquals(id, running["id"])
                assertEquals(true, running["healthBusy"])
                assertEquals(false, running["sessionBusy"])
            }
        } finally {
            release.countDown()
            worker.join(20000)
        }
    }

    @Test
    fun storageFullWhileRecordingReachesTheWireAsTheSharedFailure() = command {
        val case = failureSnapshotCase("storage full while recording")
        start()
        RecordingEngine::class
            .java
            .getDeclaredMethod("captureFailed", Exception::class.java)
            .apply { isAccessible = true }
            .invoke(
                engine,
                android.database.sqlite.SQLiteFullException((case["nativeDetail"] as String).substringAfter(": ")),
            )
        val state = engine.state()
        for ((key, value) in case["wire"] as Map<*, *>) assertEquals("$key", value, state[key])
        assertFailure(RideFailure.storageFull, state)
        assertEquals("interrupted", state["phase"])
    }

    @Test
    fun lifecycleActionFixtureRunsThroughTheOwnerAndStoredJournal() = command {
        val fixture = JSONObject(contractFixture("lifecycle/F10.json").readText())
        val id = start()
        var lapCommand: String? = null
        for (row in fixture.getJSONArray("actions").let { a -> (0 until a.length()).map(a::getJSONObject) }) {
            advanceTo(row.getLong("at"))
            val action = row.getString("action")
            val commandId = if (row.optBoolean("duplicate")) checkNotNull(lapCommand) else UUID.randomUUID().toString()
            if (action == "lap") lapCommand = commandId
            if (row.getBoolean("admitted")) engine.action(action, id, commandId)
            else assertThrows(IllegalStateException::class.java) { engine.action(action, id, commandId) }
            assertEquals(row.getString("phase"), engine.store.metadata(id)["phase"])
            assertEquals(row.getInt("events"), engine.store.events(id).size)
            assertEquals(row.getDouble("timer"), engine.state().num("timerSeconds"), 0.0)
        }
        assertEquals(fixture.getDouble("timer"), engine.store.timing(id).timer, 0.0)
        assertEquals(fixture.getDouble("elapsed"), engine.store.timing(id).elapsed, 0.0)
    }

    @Test
    fun liveHealthStatusReflectsPersistedProviderOutcome() = command {
        val id = start(saveToHealth = true)
        engine.store.healthStatus(
            id,
            HealthExportResult(
                "failed",
                components =
                    listOf(
                        app.powerlog.core.health.ComponentResult(
                            app.powerlog.core.health.Component.workout,
                            1,
                            true,
                            app.powerlog.core.health.ComponentState.unavailable,
                            app.powerlog.core.health.HealthReason.permission,
                            emptyList(),
                        )
                    ),
            ),
        )
        assertEquals("failed", engine.state()["healthKitState"])
    }

    @Test
    fun workerCompletionNotifiesIdleForegroundListeners() {
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(2)
            engine.action("stop", id)
            id
        }
        val event = CompletableFuture<Payload>()
        command {
            engine.listeners.add { name, value ->
                if (name == "onWorkoutState" && value["healthBusy"] == false && value["healthKitState"] == "failed")
                    event.complete(value)
            }
        }
        val context = RuntimeEnvironment.getApplication()
        RefreshHealthWorker.access =
            FakeHealthConnectAccess().apply {
                grants -=
                    HealthPermission.getWritePermission(
                        androidx.health.connect.client.records.ExerciseSessionRecord::class
                    )
            }
        RefreshHealthWorker.clock = timeUs(command { engine.store.metadata(id).str("endedAt") }) + 1000000
        val worker =
            androidx.work.testing.TestWorkerBuilder.from(
                    context,
                    RefreshHealthWorker::class.java,
                    java.util.concurrent.Executor { it.run() },
                )
                .setInputData(androidx.work.workDataOf("ride" to id))
                .build()
        worker.doWork()
        val updated = event.get(5, TimeUnit.SECONDS)
        assertEquals("completed", updated["phase"])
        assertEquals(id, updated["id"])
        assertEquals(command { engine.state()["historyRevision"] }, updated["historyRevision"])
        assertEquals("failed", command { engine.store.metadata(id)["healthKitState"] })
    }

    @Test
    fun healthStartNeedsOnlySessionAccessEvenWithPowerDenied() = command {
        healthAccess.available = true
        healthAccess.grants -= HealthPermission.getWritePermission(PowerRecord::class)
        val state =
            engine.start(RideOptions(indoor = false, saveToHealth = true, recordGPS = true), healthAccess.grants)
        assertEquals("running", state["phase"])
        assertTrue(engine.store.metadata(state.str("id")).flag("saveToHealth"))
    }

    @Test
    fun deniedSessionRejectsHealthStartButAllowsLocalRecording() = command {
        healthAccess.available = true
        healthAccess.grants -= HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val error =
            assertThrows(IllegalStateException::class.java) {
                engine.start(RideOptions(indoor = false, saveToHealth = true, recordGPS = false), healthAccess.grants)
            }
        assertEquals(RideFailure.healthDenied.text, error.message)
        assertNull(field("ride"))
        val id = start()
        assertEquals("running", engine.store.metadata(id)["phase"])
        assertFalse(engine.store.metadata(id).flag("saveToHealth"))
    }

    @Test
    fun sessionAccessAloneAllowsHealthStartWithoutOptionalGrants() = command {
        healthAccess.available = true
        healthAccess.grants = engine.health.essentialPermissions()
        val state =
            engine.start(RideOptions(indoor = false, saveToHealth = true, recordGPS = true), healthAccess.grants)
        assertEquals("running", state["phase"])
        assertTrue(engine.store.metadata(state.str("id")).flag("saveToHealth"))
    }

    @Test
    fun systemStartClockKeepsUtcMillisecondsAndElapsedRealtime() = command {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
        val monotonic = SystemClock.elapsedRealtime()
        val reading = withUtc(1767225600123L) { SystemRecordingClock.read() }
        assertEquals("2026-01-01T00:00:00.123Z", reading.timestamp)
        assertEquals(monotonic, reading.monotonicMillis)
    }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun delayedCreateKeepsTheCapturedStartForObservationsAndActiveTime() = command {
        val reading = RecordingClockReading("2026-01-01T00:00:00.123Z", SystemClock.elapsedRealtime())
        var reads = 0
        readClock = {
            reads++
            val at = SystemClock.elapsedRealtime()
            val elapsed = at - reading.monotonicMillis
            RecordingClockReading(iso(1767225600123L + elapsed), at)
        }
        val live = engine.source(MonitorTarget.Live)
        val notification = engine.notificationState
        val id =
            withStartDelay(
                beforeOwnerRecord = true,
                duringCreate = {
                    assertEquals("idle", engine.state()["phase"])
                    assertNull(engine.state()["pendingAction"])
                    assertNull(engine.store.lifecycleState().ride)
                    assertEquals(live, engine.source(MonitorTarget.Live))
                    assertEquals(notification, engine.notificationState)
                },
            ) {
                start()
            }
        assertEquals(reading.monotonicMillis, field("startClock"))
        assertEquals(2.0, engine.state().num("timerSeconds"), 0.0)
        assertEquals(reading.timestamp, engine.store.metadata(id)["startedAt"])
        assertEquals(RideTiming(0.0, 0.0, reading.timestamp), engine.store.timing(id))
        assertEquals(listOf(RideEvent(0.0, "start", reading.timestamp)), engine.store.events(id))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        telemetry(acquiredAt = reading.monotonicMillis + 3000)
        engine.flush()
        assertEquals(3.0, recordStore(id).page(id).single().time, 0.0)
        assertEquals(3.0, engine.state().num("timerSeconds"), 0.0)
    }

    @Test
    fun failedCreateKeepsEngineIdleAndRollsBackTheRideAndStartEvent() = command {
        val db = engine.store.writableDatabase
        val live = engine.source(MonitorTarget.Live)
        val state = engine.state()
        val notification = engine.notificationState
        val events = mutableListOf<Payload>()
        engine.listeners.add { event, payload ->
            if (event == "onWorkoutState") events.add(payload)
        }
        db.execSQL(
            "CREATE TEMP TRIGGER fail_start BEFORE INSERT ON rides WHEN NEW.id NOT LIKE 'live-%' BEGIN SELECT RAISE(ABORT,'injected start failure'); END"
        )
        try {
            assertThrows(SQLiteException::class.java) { start(true) }
            assertNull(field("ride"))
            assertNull(field("selectedRide"))
            assertEquals(state - "error" - "failure", engine.state() - "error" - "failure")
            assertFailure(RideFailure.storageFailed, engine.state())
            assertSame(notification, engine.notificationState)
            assertFalse(engine.gpsActive)
            assertEquals(live, engine.source(MonitorTarget.Live))
            assertEquals(
                live,
                RideMonitor::class
                    .java
                    .getDeclaredField("liveRide")
                    .apply { isAccessible = true }
                    .get(field("previewMonitor")),
            )
            assertFailure(RideFailure.storageFailed, events.last())
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
            for (table in listOf("rides", "lifecycle")) {
                db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            }
        } finally {
            db.execSQL("DROP TRIGGER fail_start")
        }
        assertEquals("running", engine.store.metadata(start())["phase"])
    }

    @Test
    fun telemetryAcquiredBeforeStartOrResumeKeepsItsOwnPhase() = command {
        val beforeStart = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val id = start()
        telemetry(acquiredAt = beforeStart)
        engine.flush()
        assertEquals(0L, recordStore(id).count(id))
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
        val rows = recordStore(id).page(id).filter { it.kind == "telemetry" }
        engine.action("stop", id)
        assertEquals(listOf(false, true), rows.map { it.active })
    }

    @Test
    fun liveMonitorKeepsAcquisitionTimesAcrossClockJumpsAndRereads() = command {
        val base = 1767225600000L
        val request = mapOf("source" to "live", "metrics" to listOf("humanPowerW"), "generation" to 1)
        val live = engine.source(BridgeInputs.monitor(MonitorOperation.Describe, request).target)
        val empty =
            monitorQuery(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to live,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
                ),
            )
        assertEquals(mapOf("humanPowerW" to null), empty["liveAcquiredAt"])
        val description =
            monitorQuery(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Describe,
                    mapOf(
                        "source" to "workout",
                        "id" to live,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
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
            monitorQuery(
                live,
                BridgeInputs.monitor(
                    MonitorOperation.Latest,
                    mapOf(
                        "source" to "workout",
                        "id" to live,
                        "generation" to 0,
                        "sinceRevision" to "0",
                    ) + request,
                ),
            )
        assertEquals(mapOf("humanPowerW" to acquired), first["liveAcquiredAt"])
        assertEquals(acquired, first.num("monotonicAt"), 0.0)
        val original = recordStore(live).page(live).single()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        withUtc(base - 3600000) {
            for (kind in listOf("describe", "latest")) {
                val response =
                    monitorQuery(
                        live,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf(
                                "source" to "workout",
                                "id" to live,
                                "generation" to 0,
                                "sinceRevision" to "0",
                            ) + request,
                        ),
                    )
                assertEquals(first.num("nowSeconds") + 6, response.num("nowSeconds"), 0.0)
                assertEquals(acquired + 6, response.num("monotonicAt"), 0.0)
                assertFalse(response.containsKey("liveAgeSeconds"))
                if (kind == "latest") assertEquals(first["liveAcquiredAt"], response["liveAcquiredAt"])
            }
        }
        assertEquals(original, recordStore(live).page(live).single())
        val id = withUtc(base) { start(true) }
        val origin = SystemClock.elapsedRealtime() / 1000.0
        assertEquals(
            mapOf("humanPowerW" to null),
            engine.monitor
                .query(
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
                assertEquals(7.0, response.num("nowSeconds"), 0.0)
                assertEquals(origin + 7, response.num("monotonicAt"), 0.0)
                if (kind == "latest") assertEquals(mapOf("humanPowerW" to origin + 2), response["liveAcquiredAt"])
            }
            engine.action("pause", id)
        }
        advanceTo(8)
        val paused =
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
        assertEquals(8.0, paused.num("nowSeconds"), 0.0)
        assertEquals(mapOf("humanPowerW" to origin + 2), paused["liveAcquiredAt"])
        withUtc(base - 10800000) { engine.action("stop", id) }
        repeat(2) {
            for (kind in listOf("describe", "latest")) {
                val historical =
                    monitorQuery(
                        id,
                        BridgeInputs.monitor(
                            MonitorOperation.valueOf(kind.replaceFirstChar { it.uppercase() }),
                            mapOf(
                                "source" to "workout",
                                "id" to id,
                                "generation" to 0,
                                "sinceRevision" to "0",
                            ) + request + ("source" to "workout"),
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
}
