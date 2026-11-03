package app.powerlog.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import app.powerlog.core.lifecycle.CaptureBatch
import app.powerlog.core.lifecycle.CaptureOriginal
import app.powerlog.core.presentation.*
import app.powerlog.core.protocol.Timestamps
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineCaptureTest : RecordingEngineTestBase() {
    @Test
    fun aBusyStoreDuringTheEightSampleFlushIsRetriedWithoutInterruptingTheRide() = command {
        val id = start(sampleHz = 8)
        for (index in 1..7) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(125))
            telemetry()
        }
        var busy = 1
        engine.store.beforeTransactionEnd = {
            if (busy > 0) {
                busy--
                throw SQLiteDatabaseLockedException("database is locked")
            }
        }
        try {
            ShadowSystemClock.advanceBy(Duration.ofMillis(125))
            telemetry()
        } finally {
            engine.store.beforeTransactionEnd = null
        }
        assertEquals(0, busy)
        assertEquals("running", engine.state().str("phase"))
        assertNull(engine.state()["failure"])
        engine.flush()
        assertEquals(8, engine.store.page(id).size)
    }

    @Test
    fun aBusyStoreDuringPauseKeepsTheRideRunning() = command {
        val id = start()
        engine.store.beforeTransactionEnd = { throw SQLiteDatabaseLockedException("database is locked") }
        val failure =
            try {
                engine.action("pause", id)
                null
            } catch (error: Exception) {
                error
            } finally {
                engine.store.beforeTransactionEnd = null
            }
        assertEquals(RideFailure.storageBusy, failure?.let { RideFailures.classify(it) })
        assertEquals("running", engine.state().str("phase"))
        assertNull(engine.state()["failure"])
        assertEquals("paused", engine.action("pause", id).str("phase"))
    }

    @Test
    fun aFullCaptureQueueDropsSamplesAndShowsTheBusyStoreWithoutInterruptingTheRide() {
        val id = command {
            val id = start(sampleHz = 8)
            val waiting =
                (1..CaptureBatch.queuedFrames - 8).map {
                    CaptureOriginal(
                        UUID.randomUUID().toString(),
                        "controller",
                        it.toLong(),
                        Timestamps.parse(iso()),
                        UUID.randomUUID().toString(),
                        0,
                        null,
                        null,
                        null,
                        "{}",
                    )
                }
            RecordingEngine::class
                .java
                .getDeclaredField("captureBatch")
                .apply { isAccessible = true }
                .set(engine, CaptureBatch(waiting))
            engine.store.beforeTransactionEnd = { throw SQLiteDatabaseLockedException("database is locked") }
            repeat(16) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(125))
                telemetry()
            }
            id
        }
        command {
            assertEquals("running", engine.state().str("phase"))
            assertEquals(false, engine.store.metadata(id)["interrupted"])
            assertEquals(RideFailure.storageBusy.text, engine.bluetooth.state["error"])
            engine.store.beforeTransactionEnd = null
            engine.flush()
            assertEquals(8, engine.store.page(id).size)
        }
        command { assertNull(engine.bluetooth.state["error"]) }
    }

    @Test
    fun aFlushReadsEachBlockAndTheTimelineOnceForAllMetrics() = command {
        start(sampleHz = 8)
        for (second in 1L..6L) {
            advanceTo(second)
            telemetry(speed = 5.0)
        }
        engine.flush()
        advanceTo(8)
        telemetry(speed = 5.0)
        val queries = mutableListOf<Pair<String, List<Any>>>()
        engine.store.queryObserver = { sql, args -> queries.add(sql to args.toList()) }
        try {
            engine.flush()
        } finally {
            engine.store.queryObserver = null
        }
        val observationReads = queries.filter { (sql, _) -> "INDEXED BY observations_elapsed" in sql }
        val lifecycleReads = queries.filter { (sql, _) -> "FROM lifecycle" in sql }
        assertTrue(observationReads.isNotEmpty())
        assertEquals(observationReads.size, observationReads.toSet().size)
        assertTrue(lifecycleReads.isNotEmpty())
        assertEquals(lifecycleReads.size, lifecycleReads.toSet().size)
    }

    @Test
    fun malformedGpsOriginalsDoNotAbortTheRemainingBatch() = command {
        val id = start(true)
        advanceTo(1)
        val bad =
            Location(LocationManager.GPS_PROVIDER).apply {
                time = 0
                elapsedRealtimeNanos = 1
            }
        val valid =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0
                longitude = 0.0
                accuracy = 5f
                time = java.time.Instant.parse(iso()).toEpochMilli()
                elapsedRealtimeNanos = (field("startClock") as Long) * 1000000 + 1000000000
            }
        (field("locationListener") as LocationListener).onLocationChanged(listOf(bad, valid))
        engine.flush()
        val row = recordStore(id).page(id).single()
        assertEquals(1.0, row.time, 0.0)
        assertTrue(row.evidence.flag("distanceBarrier"))
    }

    @Test
    fun nativeGpsBatchesAdmitOriginalAcquisitionOrder() = command {
        val id = start(true)
        advanceTo(2)
        val batch =
            listOf(2.0, 1.0).map { seconds ->
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0
                    longitude = seconds * 0.00001
                    accuracy = 5f
                    time =
                        java.time.Instant.parse(readClock().timestamp).toEpochMilli() -
                            (2000 - (seconds * 1000).toLong())
                    elapsedRealtimeNanos = (field("startClock") as Long) * 1000000 + (seconds * 1e9).toLong()
                }
            }
        (field("locationListener") as LocationListener).onLocationChanged(batch)
        engine.flush()
        assertEquals(listOf(1.0, 2.0), recordStore(id).page(id).map { it.time })
    }

    @Test
    fun gpsAcquisitionFixturesRunThroughTheLocationListenerAndStore() = command {
        val root = System.getenv("POWER_LOG_CONTRACT_FIXTURES") ?: "../../../tests/fixtures/contract"
        val fixture = org.json.JSONObject(java.io.File(root, "gps/acquisition.json").readText())
        val base = java.time.Instant.parse(fixture.getString("baseTimestamp")).toEpochMilli()
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.has("stop") || case.has("epochStart") || case.has("timestamp")) continue
            val origin = case.optDouble("origin", 0.0)
            val id = withUtc(base + (origin * 1000).toLong()) { start(true) }
            advanceTo((case.getDouble("uptime") - origin).toLong())
            withUtc(base + case.getString("receiptUs").toLong() / 1000) {
                val previousClock = readClock
                val reading = readClock()
                val nativeRemainder = case.getString("fixUs").toLong() % 1000
                readClock = {
                    reading.copy(
                        timestamp =
                            java.time.Instant.ofEpochMilli(base)
                                .plusNanos((case.getString("receiptUs").toLong() - nativeRemainder) * 1000)
                                .toString()
                    )
                }
                val fix =
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = case.optDouble("latitude", 40.0)
                        longitude = 20.0
                        accuracy = case.optDouble("accuracy", 5.0).toFloat()
                        time = base + case.getString("fixUs").toLong() / 1000
                        elapsedRealtimeNanos =
                            SystemClock.elapsedRealtimeNanos() -
                                (case.getString("receiptUs").toLong() - case.getString("fixUs").toLong()) * 1000
                    }
                (field("locationListener") as LocationListener).onLocationChanged(fix)
                engine.flush()
                readClock = previousClock
            }
            val rows = recordStore(id).page(id)
            assertEquals(case.getString("id"), if (case.getBoolean("mapped")) 1 else 0, rows.size)
            if (rows.isNotEmpty())
                assertEquals(
                    case.getString("id"),
                    case.getDouble("elapsed"),
                    rows.single().time,
                    0.000001,
                )
            engine.action("discard", id)
        }
    }

    @Test
    fun gpsAdmissionRetainsFastOriginalsThroughFifteenSecondsAndRejectsOlderAcquisitions() = command {
        val id = start(true)
        advanceTo(20)
        location(5.0, speed = 41f, courseAccuracy = -1f)
        location(4.999, speed = 3f)
        location(5.0, speed = 41f)
        location(4.0, speed = 3f)
        engine.flush()
        val original = recordStore(id).page(id).single()
        assertEquals(5.0, original.time, 0.0)
        assertEquals(41.0, original.values["speedMps"])
        assertEquals(
            -1.0,
            ((original.evidence["values"] as Map<*, *>)["courseAccuracyDegrees"] as Number).toDouble(),
            0.0,
        )
        assertEquals("stale", stream("gps")["status"])
        advanceTo(21)
        location(11.0)
        location(10.5)
        location(11.0)
        location(11.0000005)
        engine.flush()
        assertEquals(listOf(5.0, 11.0), recordStore(id).page(id).map { it.time })
        assertEquals("stale", stream("gps")["status"])
    }

    @Test
    fun gpsClockUncertaintyIncludesItsFifteenSecondBoundary() = command {
        val base = 1767225600000L
        val id = withUtc(base) { start(true) }
        advanceTo(2)
        withUtc(base + 3602000) { location(2.0) }
        advanceTo(17)
        withUtc(base + 3617000) { location(17.0) }
        advanceTo(18)
        withUtc(base + 3618000) { location(18.0) }
        engine.flush()
        val rows = recordStore(id).page(id)
        assertEquals(listOf(true, true, null), rows.map { it.evidence["distanceBarrier"] })
        assertEquals(0.0, engine.distance.total(id, "gps:phone").second, 0.0)
    }

    @Test
    fun deletionRejectsRecoverableTargetsAndGlobalHealthWorkWithoutRemovingOriginals() = command {
        val id = start()
        advanceTo(1)
        engine.action("stop", id)
        RideStore.withHealthExecution {
            assertThrows(IllegalStateException::class.java) { engine.delete(id) }
        }
        assertEquals("completed", engine.store.metadata(id)["phase"])
        val orphan = start()
        failCapture()
        assertEquals("interrupted", engine.store.metadata(orphan)["phase"])
        assertThrows(IllegalStateException::class.java) { engine.delete(orphan) }
        RideStore.withHealthExecution { assertThrows(IllegalStateException::class.java) { engine.delete(id) } }
        engine.delete(id)
        assertEquals(orphan, engine.state()["id"])
    }

    @Test
    fun deletingOrDiscardingARideGivesItsSpaceBack() {
        val store = engine.store
        fun fill(id: String) {
            for (page in 0 until 2048 step 256) store.transaction {
                for (row in page until page + 256) store.insert(
                    id,
                    row / 8.0,
                    iso(1767225600000 + row * 125L),
                    "telemetry",
                    true,
                    0,
                    mapOf("humanPowerW" to 100.0 + row % 211, "cadenceRpm" to 80.0),
                    "X6|test|5.3",
                    "one",
                )
            }
        }
        fun settledBytes(): Long {
            store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            return store.fileBytes
        }
        val deleted = command { start() }
        fill(deleted)
        command {
            advanceTo(1)
            engine.action("stop", deleted)
        }
        var before = settledBytes()
        command { engine.delete(deleted) }
        engine.storageTasks.submit {}.get(20, TimeUnit.SECONDS)
        assertFalse(store.exists(deleted))
        assertTrue("$before to ${store.fileBytes}", store.fileBytes < before - 1_000_000)
        val discarded = command { start() }
        fill(discarded)
        before = settledBytes()
        command { engine.action("discard", discarded) }
        engine.storageTasks.submit {}.get(20, TimeUnit.SECONDS)
        assertFalse(store.exists(discarded))
        assertTrue("$before to ${store.fileBytes}", store.fileBytes < before - 1_000_000)
    }

    @Test
    fun gpsCollectionTimeUsesOriginalUtcAgeAndRejectsAnOverTenSecondEdge() = command {
        val id = start(gps = true)
        advanceTo(2)
        location(1.125, speed = 3f)
        advanceTo(12)
        location(11.25, speed = 3f)
        engine.action("stop", id)
        val rows = recordStore(id).page(id).filter { it.kind == "location" }
        assertEquals(listOf(1.125, 11.25), rows.map { it.time })
        assertEquals(listOf(1125000L, 11250000L), rows.map { it.elapsedUs })
        assertEquals(0.0, engine.distance.total(id, "gps:phone").first, 0.0)
    }

    @Test
    fun moduleDestroyHookCancelsItsSummaryAndMonitorJobsAndPreservesAnotherOwner() {
        val module = PowerLogBridgeModule { engine }
        val owner =
            PowerLogBridgeModule::class.java.getDeclaredField("summaryOwner").apply { isAccessible = true }.get(module)
                as String
        val started = java.util.concurrent.CountDownLatch(3)
        val release = java.util.concurrent.CountDownLatch(1)
        val results = List(3) { java.util.concurrent.CompletableFuture<String>() }
        fun promise(index: Int) =
            object : expo.modules.kotlin.Promise {
                override fun resolve(value: Any?) {
                    results[index].complete("resolved")
                }

                override fun reject(code: String?, message: String?, cause: Throwable?) {
                    results[index].complete(code)
                }
            }
        fun work(): Any? {
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {}
            return 7
        }
        try {
            engine.summaries.submit(owner, "destroy-summary", promise(0), ::work)
            engine.monitorReads.submit(
                owner,
                "destroy-monitor",
                MonitorInput(MonitorOperation.Plot, MonitorTarget.Live, 0),
                promise(1),
                ::work,
            )
            engine.monitorReads.submit(
                "retained",
                "retain-monitor",
                MonitorInput(MonitorOperation.Plot, MonitorTarget.Live, 0),
                promise(2),
                ::work,
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val hook =
                module.definition().eventListeners.getValue(expo.modules.kotlin.events.EventName.MODULE_DESTROY)
                    as expo.modules.kotlin.events.BasicEventListener
            hook.call()
            assertEquals("ERR_READ_CANCELLED", results[0].get(1, TimeUnit.SECONDS))
            assertEquals("ERR_READ_CANCELLED", results[1].get(1, TimeUnit.SECONDS))
            assertFalse(results[2].isDone)
            release.countDown()
            assertEquals("resolved", results[2].get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            engine.monitorReads.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun moduleMonitorEntrypointsDispatchInteractiveReadsAndCancelTheOriginalNativeJob() {
        val id = command {
            val id =
                engine.store.create(
                    RideOptions(true, saveToHealth = false, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            engine.store.seal(id, RideTiming(10.0, 10.0, iso()))
            id
        }
        val module = PowerLogBridgeModule { engine }
        val definition = module.definition()
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        engine.store.queryObserver = { sql, _ ->
            if (sql.contains("FROM distance_intervals") && first.compareAndSet(true, false)) {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
            }
        }
        fun promise(result: java.util.concurrent.CompletableFuture<String>) =
            object : expo.modules.kotlin.Promise {
                override fun resolve(value: Any?) {
                    result.complete("resolved")
                }

                override fun reject(code: String?, message: String?, cause: Throwable?) {
                    result.complete(code)
                }
            }
        val input =
            mapOf(
                "source" to "workout",
                "id" to id,
                "generation" to 0,
                "metrics" to emptyList<String>(),
                "expectedRevision" to engine.store.revision(id).toString(),
            )
        fun invoke(
            name: String,
            cancellationId: String,
            result: java.util.concurrent.CompletableFuture<String>,
        ) {
            val function = definition.asyncFunctions.getValue(name)
            val body =
                function.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(function)
                    as (Array<out Any?>, expo.modules.kotlin.Promise) -> Unit
            body(
                arrayOf(input + mapOf("cancellationId" to cancellationId, "seconds" to 5.0)),
                promise(result),
            )
        }
        val plot = java.util.concurrent.CompletableFuture<String>()
        try {
            invoke("readMonitorPlot", "module-plot", plot)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            for (name in listOf("readMonitorLatest", "inspectMonitorAt")) {
                val result = java.util.concurrent.CompletableFuture<String>()
                invoke(name, name, result)
                assertEquals("resolved", result.get(1, TimeUnit.SECONDS))
            }
            val cancelled = java.util.concurrent.CompletableFuture<String>()
            val cancel = definition.asyncFunctions.getValue("cancelMonitorRead")
            val body =
                cancel.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(cancel)
                    as (Array<out Any?>, expo.modules.kotlin.Promise) -> Unit
            body(arrayOf("module-plot"), promise(cancelled))
            assertEquals("resolved", cancelled.get(1, TimeUnit.SECONDS))
            assertEquals("ERR_READ_CANCELLED", plot.get(1, TimeUnit.SECONDS))
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            engine.store.queryObserver = null
            engine.monitorReads.cancelOwner(
                PowerLogBridgeModule::class
                    .java
                    .getDeclaredField("summaryOwner")
                    .apply { isAccessible = true }
                    .get(module) as String
            )
        }
    }

    @Test
    fun contractCaptureOriginalsRoundTripThroughTheProductionStoreAndAdmission() = command {
        val fixture = telemetryFixture("admission")
        val original = fixture.getJSONObject("retainedOriginal")
        val profile = original.getJSONObject("identity")
        val controller =
            CycProtocol.restoreIdentity(
                profile.getString("controllerModel"),
                profile.getString("firmwareLabel"),
                profile.getString("controllerProtocol"),
            )
        val values =
            original.getJSONObject("metrics").let { obj ->
                obj.keys().asSequence().associateWith(obj::getDouble)
            }
        val reading = CycProtocol.restoreReading(values, controller)
        val rideA = start()
        val rideB = engine.store.create(RideOptions(false, false, false), SystemRecordingClock.read())
        val capture = TelemetryCapture(engine.store)
        fun persist(evidence: TelemetryCaptureEvidence) {
            engine.store.transaction {
                engine.store.insert(
                    evidence.rideId,
                    evidence.sourceElapsedSeconds,
                    evidence.timestamp.original,
                    "telemetry",
                    true,
                    1,
                    evidence.telemetry.values(),
                    "${controller.controllerModel}|${controller.firmwareLabel}|${controller.controllerProtocol}",
                    evidence.connectionEpoch,
                    OriginalIdentity(evidence.observationId, "controller", evidence.sequence),
                    OriginalEvidence(
                        evidence.sourceElapsedSeconds,
                        evidence.acquisitionMonotonic,
                        evidence.clockEpoch,
                    ),
                    detail = evidence.detail(),
                )
            }
            capture.committed(evidence)
        }
        val cases = fixture.getJSONArray("cases")
        var target = rideA
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            if (row.has("adopt")) target = rideB
            val evidence =
                TelemetryCaptureEvidence(
                    target,
                    "50000000-0000-4000-8000-%012d".format(row.getInt("id")),
                    fixture.getString("session"),
                    fixture.getString("epoch"),
                    fixture.getString("connection"),
                    row.getString("sequence").toLong(),
                    app.powerlog.core.protocol.Timestamps.parse(original.getString("timestamp")),
                    row.getDouble("acquiredAt"),
                    10.0,
                    reading,
                )
            val decision = capture.prepare(evidence)
            assertEquals(row.getString("expected"), decision.outcome.name)
            if (decision.commitOriginal) persist(evidence)
            val state = if (decision.commitOriginal) TelemetryCaptureState(target, evidence) else decision.next
            assertEquals(row.getDouble("age"), state.age(row.getDouble("deliveredAt"))!!, 0.0)
        }
        val retained =
            TelemetryCaptureEvidence(
                rideB,
                original.getString("observationId"),
                original.getString("captureSessionId"),
                original.getString("clockEpoch"),
                original.getString("connectionEpoch"),
                original.getString("sequence").toLong(),
                app.powerlog.core.protocol.Timestamps.parse(original.getString("timestamp")),
                original.getDouble("acquisitionMonotonic"),
                original.getDouble("sourceElapsedSeconds"),
                reading,
            )
        assertEquals(TelemetryCaptureOutcome.older, capture.prepare(retained).outcome)
        val fresh = TelemetryCapture(engine.store)
        assertTrue(fresh.prepare(retained).commitOriginal)
        assertThrows(SQLiteException::class.java) {
            engine.store.transaction {
                engine.store.insert(
                    rideB,
                    10.0,
                    retained.timestamp.original,
                    "telemetry",
                    true,
                    1,
                    reading.values(),
                    "X6|20250725|5.3",
                    retained.connectionEpoch,
                    OriginalIdentity(retained.observationId, "controller", retained.sequence),
                    OriginalEvidence(10.0, retained.acquisitionMonotonic, retained.clockEpoch),
                    detail = retained.detail(),
                )
                throw SQLiteException("fixture rollback")
            }
        }
        assertTrue(fresh.prepare(retained).commitOriginal)
        persist(retained)
        val reopened = recordStore(rideB).telemetryOriginal(rideB, retained.observationId)!!
        assertEquals(retained, reopened)
        assertEquals(TelemetryCaptureOutcome.replay, fresh.prepare(reopened).outcome)
        assertNull(fresh.prepare(reopened).next.latest)
        assertEquals(
            TelemetryCaptureOutcome.conflict,
            fresh.prepare(reopened.copy(acquisitionMonotonic = 101.0)).outcome,
        )
        val observation = recordStore(rideB).page(rideB).single { it.original!!.eventId == retained.observationId }
        assertThrows(IllegalArgumentException::class.java) {
            observation.copy(values = observation.values + ("motorInputPowerW" to 1.0)).telemetryEvidence(rideB)
        }
        val malformedEvidence =
            listOf(
                observation.evidence - "sourceElapsedSeconds",
                observation.evidence + ("sourceElapsedSeconds" to "10"),
                observation.evidence + ("clockDiscontinuitySeconds" to "0.5"),
                observation.evidence + ("observationSequence" to "0${retained.sequence}"),
            )
        for (evidence in malformedEvidence) assertThrows(IllegalArgumentException::class.java) {
            observation.copy(evidence = evidence).telemetryEvidence(rideB)
        }
        assertEquals("6", engine.store.writableDatabase.version.toString())
    }

    @Test
    fun rawClockFixturesUseRecordingClockAndNeverRoundedTimestamps() {
        val cases = telemetryFixture("admission").getJSONArray("clockCases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val previous =
                RecordingClockReading(
                    "2026-01-01T00:00:00.000Z",
                    (row.getDouble("previousMonotonic") * 1000).toLong(),
                    row.getDouble("firstWall"),
                )
            val current =
                RecordingClockReading(
                    "2026-01-01T00:00:01.250Z",
                    (row.getDouble("monotonic") * 1000).toLong(),
                    row.getDouble("lastWall"),
                )
            val result = current.discontinuity(previous)
            if (row.isNull("expected")) assertNull(result) else assertEquals(row.getDouble("expected"), result!!, 0.0)
        }
    }

    @Test
    fun captureAdmissionWaitsForOriginalCommitAndReconnectRetainsCaptureIdentity() = command {
        val fixture = telemetryFixture("admission").getJSONObject("retainedOriginal")
        val identity = CycProtocol.restoreIdentity("X6", "20250725", "5.3")
        val metrics =
            fixture.getJSONObject("metrics").let { row ->
                row.keys().asSequence().associateWith(row::getDouble)
            }
        val reading = CycProtocol.restoreReading(metrics, identity)
        val method =
            RecordingEngine::class
                .java
                .getDeclaredMethod(
                    "telemetry",
                    TelemetryReading::class.java,
                    String::class.java,
                    RecordingClockReading::class.java,
                )
                .apply { isAccessible = true }
        val delivered = mutableListOf<Payload>()
        engine.listeners.add { event, value -> if (event == "onSample") delivered.add(value) }
        val ride = start()
        advanceTo(1)
        method.invoke(
            engine,
            reading,
            fixture.getString("connectionEpoch"),
            SystemRecordingClock.read(),
        )
        assertEquals(1, delivered.size)
        assertEquals(0L, recordStore(ride).count(ride))
        val original = (field("pending") as List<*>).last()!!
        val evidence =
            original.javaClass.getDeclaredField("telemetry").apply { isAccessible = true }.get(original)
                as TelemetryCaptureEvidence
        val probe =
            evidence.copy(
                observationId = UUID.randomUUID().toString(),
                acquisitionMonotonic = evidence.acquisitionMonotonic - 0.1,
                sourceElapsedSeconds = 0.0,
            )
        val capture = field("telemetryCapture") as TelemetryCapture
        assertEquals(TelemetryCaptureOutcome.admitted, capture.prepare(probe).outcome)
        engine.flush()
        assertEquals(TelemetryCaptureOutcome.older, capture.prepare(probe).outcome)
        assertEquals(1, delivered.size)
        val first = recordStore(ride).page(ride).single().telemetryEvidence(ride)
        advanceTo(2)
        method.invoke(
            engine,
            reading,
            "40000000-0000-4000-8000-000000000002",
            SystemRecordingClock.read(),
        )
        assertEquals(2, delivered.size)
        engine.flush()
        val second = recordStore(ride).page(ride).last().telemetryEvidence(ride)
        assertEquals(first.captureSessionId, second.captureSessionId)
        assertEquals(first.clockEpoch, second.clockEpoch)
        assertEquals(first.sequence + 1, second.sequence)
        assertNotEquals(first.connectionEpoch, second.connectionEpoch)
        engine.action("stop", ride)
        val next = start()
        val replay = capture.prepare(second)
        assertEquals(TelemetryCaptureOutcome.replay, replay.outcome)
        assertNull(replay.next.latest)
        assertEquals("waiting", stream("controller")["status"])
        advanceTo(1)
        method.invoke(
            engine,
            reading,
            fixture.getString("connectionEpoch"),
            SystemRecordingClock.read(),
        )
        engine.flush()
        val restarted = recordStore(next).page(next).single().telemetryEvidence(next)
        assertNull(restarted.clockDiscontinuitySeconds)
        assertEquals("receiving", stream("controller")["status"])
    }

    @Test
    fun explicitConnectionResetsLiveCaptureClockAndCounters() = command {
        val fixture = telemetryFixture("admission").getJSONObject("retainedOriginal")
        val metrics =
            fixture.getJSONObject("metrics").let { row ->
                row.keys().asSequence().associateWith(row::getDouble)
            }
        val reading =
            CycProtocol.restoreReading(
                metrics,
                CycProtocol.restoreIdentity("X6", "20250725", "5.3"),
            )
        val method =
            RecordingEngine::class
                .java
                .getDeclaredMethod(
                    "telemetry",
                    TelemetryReading::class.java,
                    String::class.java,
                    RecordingClockReading::class.java,
                )
                .apply { isAccessible = true }
        val old = engine.source(MonitorTarget.Live)
        advanceTo(1)
        repeat(2) {
            method.invoke(
                engine,
                reading,
                fixture.getString("connectionEpoch"),
                SystemRecordingClock.read(),
            )
        }
        engine.flush()
        val prior = recordStore(old).page(old).last().telemetryEvidence(old)
        val samples = mutableListOf<Payload>()
        engine.listeners.add { event, value -> if (event == "onSample") samples.add(value) }
        shadowOf(engine.context.getSystemService(BluetoothManager::class.java).adapter)
            .setState(BluetoothAdapter.STATE_ON)
        engine.connect(BridgeInputs.connect(mapOf("deviceId" to "02:00:00:00:00:01", "hz" to 4))) {
            assertNull(it)
        }
        val fresh = engine.source(MonitorTarget.Live)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        method.invoke(
            engine,
            reading,
            fixture.getString("connectionEpoch"),
            RecordingClockReading(
                iso(System.currentTimeMillis() + 3600000),
                SystemClock.elapsedRealtime(),
                (System.currentTimeMillis() + 3600000) / 1000.0,
            ),
        )
        engine.flush()
        val next = recordStore(fresh).page(fresh).single().telemetryEvidence(fresh)
        assertNotEquals(prior.captureSessionId, next.captureSessionId)
        assertEquals(1L, next.sequence)
        assertNull(next.clockDiscontinuitySeconds)
        assertEquals(1L, (samples.single()["sequence"] as Number).toLong())
        engine.bluetooth.disconnect()
        completeCancellation(engine.bluetooth, engine.handler)
    }

    @Test
    fun captureDiscontinuityRetainsTheRawReceiptClockThroughTheEngine() = command {
        val fixture = telemetryFixture("admission")
        val row = fixture.getJSONArray("clockCases").getJSONObject(0)
        val metrics =
            fixture.getJSONObject("retainedOriginal").getJSONObject("metrics").let { obj ->
                obj.keys().asSequence().associateWith(obj::getDouble)
            }
        val reading =
            CycProtocol.restoreReading(
                metrics,
                CycProtocol.restoreIdentity("X6", "20250725", "5.3"),
            )
        val method =
            RecordingEngine::class
                .java
                .getDeclaredMethod(
                    "telemetry",
                    TelemetryReading::class.java,
                    String::class.java,
                    RecordingClockReading::class.java,
                )
                .apply { isAccessible = true }
        val ride = start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(100000 - SystemClock.elapsedRealtime()))
        method.invoke(
            engine,
            reading,
            fixture.getString("connection"),
            RecordingClockReading("2026-01-01T00:00:00.000Z", 100000, row.getDouble("firstWall")),
        )
        engine.flush()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        method.invoke(
            engine,
            reading,
            fixture.getString("connection"),
            RecordingClockReading("2026-01-01T00:00:01.250Z", 101000, row.getDouble("lastWall")),
        )
        engine.flush()
        val original = recordStore(ride).page(ride).last().telemetryEvidence(ride)
        assertEquals(row.getDouble("expected"), original.clockDiscontinuitySeconds!!, 0.0)
    }

    @Test
    fun gpsBatchUsesProviderNanosecondsAndRetainsOriginalUtc() = command {
        val id = start(gps = true)
        advanceTo(2)
        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 0.0
                longitude = 0.0
                accuracy = 5f
                time = java.time.Instant.parse(engine.store.metadata(id).str("startedAt")).toEpochMilli() + 2000
                elapsedRealtimeNanos = (field("startClock") as Long) * 1000000 + 1000000900
            }
        (field("locationListener") as LocationListener).onLocationChanged(mutableListOf(fix))
        engine.flush()
        val original = recordStore(id).page(id).single()
        assertEquals(1.0000009, original.time, 0.000000001)
        assertEquals(1000000L, original.elapsedUs)
        assertEquals(iso(fix.time), original.timestamp)
    }

    @Test
    fun aSampleTheEngineCannotRecordIsReportedWithoutEndingTheRide() = command {
        val id = start()
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
                TelemetryReading(mapOf(TelemetryMetric.humanPowerW to 120.0)),
                "40000000-0000-4000-8000-000000000001",
                RecordingClockReading(iso(), SystemClock.elapsedRealtime()),
            )
        assertEquals("running", engine.state().str("phase"))
        assertNotNull(engine.state()["failure"])
        assertEquals(false, engine.store.metadata(id)["interrupted"])
    }

    @Test
    fun aRepeatedCaptureFailureIsLoggedOnce() {
        command {
            start()
            repeat(2) { failCapture() }
        }
        command {}
        val failures =
            engine.bluetooth.diagnosticLog
                .read()
                .lines()
                .filter { it.isNotEmpty() }
                .map(::JSONObject)
                .filter { it.getString("event") == "recording_error" }
        assertEquals(listOf(true), failures.map { it.getBoolean("recordingFailed") })
    }

    @Test
    fun theStoreCheckpointsItsLogEveryThousandPages() {
        val db = engine.store.writableDatabase
        db.beginTransactionNonExclusive()
        val pages =
            try {
                db.rawQuery("PRAGMA wal_autocheckpoint", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            } finally {
                db.endTransaction()
            }
        assertEquals(1000, pages)
    }

    @Test
    fun aStorageHaltReleasesTheWakeLockAndTheRideRate() = command {
        val id = start()
        assertNotNull(field("wake"))
        failCapture()
        assertEquals("interrupted", engine.state().str("phase"))
        assertEquals(true, engine.store.metadata(id)["interrupted"])
        assertNull(field("wake"))
        @Suppress("UNCHECKED_CAST")
        val recording =
            ControllerBluetooth::class
                .java
                .getDeclaredField("recording")
                .apply { isAccessible = true }
                .get(engine.bluetooth) as () -> Boolean
        assertFalse(recording())
    }

    @Test
    fun telemetryCommitsAtEightSamplesOrOnTheNextTick() = command {
        val id = start(sampleHz = 8)
        fun sample() {
            ShadowSystemClock.advanceBy(Duration.ofMillis(125))
            telemetry()
        }
        repeat(7) { sample() }
        assertEquals(0, engine.store.page(id).size)
        sample()
        assertEquals(8, engine.store.page(id).size)
        sample()
        tick()
        assertEquals(9, engine.store.page(id).size)
        var transactions = 0
        engine.store.beforeTransactionEnd = { transactions++ }
        try {
            tick()
        } finally {
            engine.store.beforeTransactionEnd = null
        }
        assertEquals(0, transactions)
    }
}
