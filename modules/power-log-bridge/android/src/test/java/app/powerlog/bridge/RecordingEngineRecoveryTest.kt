package app.powerlog.bridge

import android.Manifest
import android.database.sqlite.SQLiteException
import android.location.LocationListener
import android.location.LocationManager
import androidx.health.connect.client.records.PowerRecord
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineRecoveryTest : RecordingEngineTestBase() {
    @Test
    fun streamStatusesDescribeSelectionFreshnessAccuracyAndPermission() = command {
        assertEquals("off", stream("controller")["status"])
        assertEquals("off", stream("gps")["status"])
        assertEquals("off", stream("heartRate")["status"])
        val id = start(true)
        assertEquals("waiting", stream("controller")["status"])
        assertEquals("waiting", stream("gps")["status"])
        advanceTo(1)
        RecordingEngine::class
            .java
            .getDeclaredMethod(
                "telemetry",
                TelemetryReading::class.java,
                String::class.java,
                RecordingClockReading::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                engine,
                TelemetryReading(
                    mapOf(TelemetryMetric.humanPowerW to 120.0),
                    CycProtocol.restoreIdentity("X6", "20250725", "5.3"),
                ),
                "40000000-0000-4000-8000-000000000001",
                SystemRecordingClock.read(),
            )
        location(1.0, 50f)
        assertEquals("receiving", stream("controller")["status"])
        assertEquals("receiving", stream("gps")["status"])
        assertEquals(50.0, stream("gps")["accuracyMeters"])
        advanceTo(2)
        location(2.0, 50.1f)
        assertEquals("weak", stream("gps")["status"])
        advanceTo(4)
        assertEquals("receiving", stream("controller")["status"])
        advanceTo(7)
        assertEquals("stale", stream("controller")["status"])
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
        assertEquals("off", stream("controller")["status"])
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val next = start(true)
        assertEquals("waiting", stream("gps")["status"])
        engine.action("discard", next)
        start()
        assertEquals("off", stream("gps")["status"])
    }

    @Test
    fun telemetryAndDelayedGpsKeepElapsedThroughClockJumpsAndHealthOmitsOutsideUtc() = command {
        val base = 1767225600000L
        val id = withUtc(base) { start(true, true) }
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
        val originals = recordStore(id).page(id)
        assertEquals(listOf(2.0, 2.0, 3.0, 3.0, 4.0, 3.5), originals.map { it.time })
        assertEquals(
            listOf(
                    base + 3600000,
                    base + 3600000,
                    base - 3600000,
                    base - 3600000,
                    base + 3000,
                    base + 2500,
                )
                .map { iso(it) },
            originals.map { it.timestamp },
        )
        val records = mutableListOf<androidx.health.connect.client.records.Record>()
        healthAccess.available = true
        val result = engine.health.save(id)
        records.addAll(healthAccess.records)
        assertEquals("saved", result.state)
        assertTrue(result.written > 0)
        assertEquals(originals, recordStore(id).page(id))
        assertTrue(
            records.filterIsInstance<androidx.health.connect.client.records.PowerRecord>().all { record ->
                record.samples.size == 1 && record.startTime < java.time.Instant.ofEpochMilli(base + 5000)
            }
        )
    }

    @Test
    fun localFinishReleasesCaptureAndExportsBeforeHealthScheduling() {
        scheduledHealth = { _, _ -> throw IllegalStateException("Health scheduling unavailable") }
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(1)
            telemetry()
            engine.flush()
            assertEquals("completed", engine.action("stop", id)["phase"])
            assertNull(field("wake"))
            assertFalse(engine.gpsActive)
            assertTrue(engine.store.canExport(id, engine.store.revision(id)))
            assertEquals(1, engine.store.events(id).count { it.action == "stop" })
            id
        }
        awaitHealthWork()
        command {
            assertEquals("completed", engine.store.metadata(id)["phase"])
            assertTrue(engine.store.canExport(id, engine.store.revision(id)))
        }
    }

    @Test
    fun delayedLocationsRejectOlderAcquisitionsAfterResume() = command {
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
        val rows = recordStore(id).page(id).associateBy { it.time }
        assertEquals(setOf(12.0), rows.keys)
        assertTrue(rows.getValue(12.0).active)
        assertFalse(rows.containsKey(9.0))
    }

    @Test
    fun endingARideCancelsOnlyAnActiveBluetoothRecovery() = command {
        for (action in listOf("stop", "discard", "interrupted")) {
            for (status in listOf("reconnecting", "connected", "connecting")) {
                setBikeStatus("connected")
                val id = start()
                ControllerBluetooth::class
                    .java
                    .getDeclaredField("desired")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, "02:00:00:00:00:01")
                ControllerBluetooth::class
                    .java
                    .getDeclaredField("state")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, mapOf("status" to status))
                var retried = false
                val retry = Runnable { retried = true }
                engine.handler.postDelayed(retry, 1000)
                ControllerBluetooth::class
                    .java
                    .getDeclaredField("timer")
                    .apply { isAccessible = true }
                    .set(engine.bluetooth, retry)
                if (action == "interrupted") {
                    RecordingEngine::class
                        .java
                        .getDeclaredMethod("captureFailed", Exception::class.java)
                        .apply { isAccessible = true }
                        .invoke(engine, SQLiteException("fixture failure"))
                } else engine.action(action, id)
                assertEquals(
                    if (status == "reconnecting") "idle" else status,
                    engine.bluetooth.state["status"],
                )
                assertEquals(status != "reconnecting", engine.handler.hasCallbacks(retry))
                assertFalse(retried)
                engine.bluetooth.disconnect()
                if (action == "interrupted") engine.action("discard", id)
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
        assertEquals(listOf(1.0, 2.0), recordStore(id).page(id).map { it.time })
        advanceTo(5)
        engine.action("resume", id)
        assertEquals(1, locations.getLocationRequests(LocationManager.GPS_PROVIDER).size)
        location(4.5, accuracy = 100f)
        assertEquals(6.0, stream("gps")["accuracyMeters"])
        location(5.0, accuracy = 7f)
        engine.flush()
        assertEquals(listOf(1.0, 2.0, 5.0), recordStore(id).page(id).map { it.time })
        assertTrue(recordStore(id).page(id).all { it.active })
    }

    @Test
    fun repeatedAndLateControlsRejectWithTheirPhaseErrorsWithoutWritingEvents() = command {
        val controls = listOf("pause", "resume", "lap")
        fun rejected(action: String, id: String?) {
            val error = assertThrows(IllegalStateException::class.java) { engine.action(action, id) }
            if (id == null) assertEquals("No ride is recording.", error.message)
            else
                assertEquals(
                    RideFailure.actionUnavailable,
                    (error as RideFailureException).failure,
                )
        }
        controls.forEach { rejected(it, null) }
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
        controls.forEach { rejected(it, id) }
        val next = start()
        controls.forEach { action ->
            val error = assertThrows(IllegalArgumentException::class.java) { engine.action(action, id) }
            assertEquals(
                "This command belongs to an earlier ride.",
                error.message,
            )
        }
        assertEquals("idle", engine.action("discard", next)["phase"])
    }

    @Test
    fun nativeOwnerSurvivesMissingUIListenersAndRejectsStaleCommands() {
        val first = command {
            engine.start(
                BridgeInputs.ride(
                    mapOf("indoor" to false) +
                        mapOf(
                            "saveToHealth" to false,
                            "recordGPS" to false,
                            "useWatch" to false,
                            "sampleHz" to 2,
                        )
                ),
                healthAccess.grants,
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
            assertEquals("completed", engine.action("stop", first)["phase"])
            assertEquals("completed", engine.store.metadata(first)["phase"])
            assertEquals("notRequested", engine.store.metadata(first)["healthKitState"])
            val second =
                engine
                    .start(
                        BridgeInputs.ride(
                            mapOf("indoor" to false) +
                                mapOf(
                                    "saveToHealth" to false,
                                    "recordGPS" to false,
                                    "useWatch" to false,
                                    "sampleHz" to 2,
                                )
                        ),
                        healthAccess.grants,
                    )
                    .str("id")
            engine.action("discard", second)
            assertEquals(1, engine.store.list(CatalogInput()).size)
            assertEquals(first, engine.store.list(CatalogInput()).first()["id"])
            engine.delete(first)
            assertTrue(engine.store.list(CatalogInput()).isEmpty())
        }
    }

    @Test
    fun failedCaptureRetainsTheIdenticalBatchUntilSuccessfulRetry() = command {
        val id = start()
        advanceTo(1)
        telemetry(3.0)
        val pending = (field("pending") as List<*>).toList()
        val db = engine.store.writableDatabase
        db.execSQL(
            "CREATE TEMP TRIGGER fail_capture BEFORE INSERT ON observations BEGIN SELECT RAISE(ABORT,'injected capture failure'); END"
        )
        try {
            assertThrows(SQLiteException::class.java) { engine.flush() }
            RecordingEngine::class
                .java
                .getDeclaredMethod("captureFailed", Exception::class.java)
                .apply { isAccessible = true }
                .invoke(engine, SQLiteException("injected capture failure"))
            assertEquals(pending, field("pending"))
            assertEquals(0L, recordStore(id).count(id, "telemetry"))
        } finally {
            db.execSQL("DROP TRIGGER fail_capture")
        }
        engine.flush()
        assertTrue((field("pending") as List<*>).isEmpty())
        assertEquals(1L, recordStore(id).count(id, "telemetry"))
        engine.flush()
        assertEquals(1L, recordStore(id).count(id, "telemetry"))
    }

    @Test
    fun stopFromRestoredHealthOffRecoveryUsesTheFrozenCheckpointAndInterruption() {
        val retained = RideTiming(12.0, 10.0, "2026-01-01T00:00:12.000Z")
        val id = command {
            val id = start(gps = true)
            engine.store.fixtureCheckpoint(id, retained)
            id
        }
        shutdown()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        engine =
            RecordingEngine.get(
                RuntimeEnvironment.getApplication(),
                RecordingClock { readClock() },
                healthAccess,
                healthScheduler = { _, _, _ -> },
            )
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals(id, engine.state()["id"])
            assertEquals("interrupted", engine.state()["phase"])
            assertFalse(engine.gpsActive)
            assertNull(field("wake"))
            val db = engine.store.writableDatabase
            db.execSQL(
                "CREATE TEMP TRIGGER fail_stop BEFORE UPDATE ON rides WHEN NEW.phase='completed' BEGIN SELECT RAISE(ABORT,'injected stop failure'); END"
            )
            try {
                assertThrows(SQLiteException::class.java) { engine.action("stop", id) }
                assertEquals(id, engine.state()["id"])
                assertEquals("interrupted", engine.state()["phase"])
                assertEquals(listOf("start", "pause"), engine.store.events(id).map { it.action })
                assertEquals(retained, engine.store.timing(id))
            } finally {
                db.execSQL("DROP TRIGGER fail_stop")
            }
            assertEquals("completed", engine.action("stop", id)["phase"])
            assertEquals(id, engine.state()["id"])
            val metadata = engine.store.metadata(id)
            assertEquals("completed", metadata["phase"])
            assertEquals(retained.timestamp, metadata["endedAt"])
            assertEquals(retained, engine.store.timing(id))
            assertEquals("notRequested", metadata["healthKitState"])
            val events = engine.store.events(id)
            assertEquals(listOf("start", "pause", "stop"), events.map { it.action })
            assertTrue(metadata.flag("interrupted"))
            assertEquals(
                listOf(retained.timestamp, retained.timestamp),
                events.takeLast(2).map { it.timestamp },
            )
            assertEquals(listOf(12.0, 12.0), events.takeLast(2).map { it.time })
            assertTrue(metadata.flag("interrupted"))
            assertFalse(engine.gpsActive)
            assertNull(field("wake"))
            assertTrue(healthAccess.batches.isEmpty())
        }
    }

    @Test
    fun restartedHealthOffRideCanBeDiscardedWithoutRecoveringOrCapturing() {
        val id = command {
            val id = start(gps = true)
            engine.store.fixtureCheckpoint(id, RideTiming(12.0, 10.0, "2026-01-01T00:00:12.000Z"))
            id
        }
        shutdown()
        engine =
            RecordingEngine.get(
                RuntimeEnvironment.getApplication(),
                RecordingClock { readClock() },
                healthAccess,
                healthScheduler = { _, _, _ -> },
            )
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals(id, engine.state()["id"])
            assertEquals("interrupted", engine.state()["phase"])
            val revision = engine.store.revision(id)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            (field("tick") as Runnable).run()
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals(revision, engine.store.revision(id))
            assertEquals(10.0, engine.state().num("timerSeconds"), 0.0)
            assertFalse(engine.gpsActive)
            assertNull(field("wake"))
            assertEquals("idle", engine.action("discard", id)["phase"])
            assertNull(engine.state()["id"])
            assertThrows(IllegalStateException::class.java) { engine.store.metadata(id) }
            assertTrue(healthAccess.batches.isEmpty())
        }
    }

    @Test fun persistedStorageFaultCanFinishAfterRestart() = finishPersistedStorageFault("stop")

    @Test fun persistedStorageFaultCanDiscardAfterRestart() = finishPersistedStorageFault("discard")

    private fun finishPersistedStorageFault(action: String) {
        val id = command {
            val id = start()
            advanceTo(1)
            telemetry()
            engine.flush()
            advanceTo(2)
            telemetry()
            val db = engine.store.writableDatabase
            db.execSQL(
                "CREATE TEMP TRIGGER fail_capture BEFORE INSERT ON observations BEGIN SELECT RAISE(ABORT,'injected capture failure'); END"
            )
            try {
                assertThrows(SQLiteException::class.java) { engine.flush() }
                assertTrue(engine.store.lifecycleState().ride!!.storageInterrupted)
                assertThrows(SQLiteException::class.java) { engine.action(action, id) }
                assertTrue(engine.store.lifecycleState().ride!!.storageInterrupted)
            } finally {
                db.execSQL("DROP TRIGGER fail_capture")
            }
            assertEquals(listOf(1.0), engine.store.page(id).map { it.time })
            id
        }
        shutdown()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        engine =
            RecordingEngine.get(
                RuntimeEnvironment.getApplication(),
                RecordingClock { readClock() },
                healthAccess,
                healthScheduler = { _, _, _ -> },
            )
        engine.awaitReady()
        command {
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertEquals("interrupted", engine.state()["phase"])
            assertFalse(engine.store.lifecycleState().ride!!.storageInterrupted)
            assertTrue((field("pending") as List<*>).isEmpty())
            assertEquals(listOf(1.0), engine.store.page(id).map { it.time })
            engine.action(action, id)
            if (action == "stop") {
                assertEquals("completed", engine.store.metadata(id)["phase"])
                assertEquals(1.0, engine.store.timing(id).elapsed, 0.0)
                assertEquals(listOf(1.0), engine.store.page(id).map { it.time })
                assertTrue(engine.store.canExport(id, engine.store.revision(id)))
            } else assertFalse(engine.store.exists(id))
            setBikeStatus("connected")
            val next = start()
            assertNotEquals(id, next)
            assertEquals("running", engine.state()["phase"])
            engine.action("discard", next)
        }
    }

    @Test
    fun newRideClearsBikeFreshnessAndCompletedSelectionRetainsVerifiedSnapshot() = command {
        telemetry()
        val id = start()
        assertEquals("waiting", stream("controller")["status"])
        assertEquals(
            engine.store.metadata(id)["collectionRevision"],
            engine.state()["collectionRevision"],
        )
        advanceTo(3)
        val stopped = engine.action("stop", id)
        assertEquals(id, stopped["id"])
        assertEquals("completed", stopped["phase"])
        assertEquals(3.0, stopped["timerSeconds"])
        advanceTo(10)
        assertEquals(3.0, engine.state()["timerSeconds"])
        assertEquals("off", stream("controller")["status"])
        val next = start()
        assertNotEquals(id, next)
        assertEquals("waiting", stream("controller")["status"])
    }
}
