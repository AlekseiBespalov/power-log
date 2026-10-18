package app.powerlog.bridge

import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import app.powerlog.core.presentation.*
import app.powerlog.core.telemetry.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock

class RecordingEngineHealthTest : RecordingEngineTestBase() {
    @Suppress("UNCHECKED_CAST")
    private fun invokeHealthAction(name: String, id: String) {
        val function = PowerLogBridgeModule { engine }.definition().asyncFunctions.getValue(name)
        val body =
            expo.modules.kotlin.functions.AsyncFunctionWithPromiseComponent::class
                .java
                .getDeclaredField("body")
                .apply { isAccessible = true }
                .get(function) as (Array<out Any?>, expo.modules.kotlin.Promise) -> Unit
        val reply = Reply()
        body(arrayOf(id), reply)
        reply.get()
        awaitHealthWork()
    }

    @Test
    fun bridgeRetryPublishesAKnownPermissionFailureButRefreshDoesNot() {
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(2)
            engine.action("stop", id)
            id
        }
        awaitHealthWork()
        val granted = healthAccess.grants
        healthAccess.grants = granted - engine.health.essentialPermissions()
        assertEquals("failed", engine.health.save(id).state)
        assertEquals("permission", engine.store.healthJob(id)?.reason)
        healthAccess.grants = granted
        scheduledHealth = { ride, _ -> engine.health.save(ride) }
        invokeHealthAction("refreshWorkoutHealth", id)
        assertEquals("failed", engine.store.healthJob(id)?.status)
        assertTrue(healthAccess.batches.isEmpty())
        assertTrue(engine.store.metadata(id).flag("healthRetryAvailable"))
        invokeHealthAction("retryWorkoutHealthSave", id)
        assertEquals("saved", engine.store.healthJob(id)?.status)
        assertFalse(engine.store.metadata(id).flag("healthRetryAvailable"))
        assertEquals(
            1,
            healthAccess.records.filterIsInstance<androidx.health.connect.client.records.ExerciseSessionRecord>().size,
        )
        val writes = healthAccess.batches.size
        invokeHealthAction("retryWorkoutHealthSave", id)
        assertEquals(writes, healthAccess.batches.size)
    }

    @Test
    fun bridgeRetryNeverEnablesSavingOrReplaysUncertainOrIneligibleWrites() {
        for ((save, status, reason) in
            listOf(
                Triple(false, "failed", "permission"),
                Triple(true, "submitted", null),
                Triple(true, "uncertain", "ambiguous"),
                Triple(true, "failed", "invalid"),
            )) {
            scheduledHealth = { _, _ -> }
            val id = command {
                val id = start(saveToHealth = save)
                advanceTo(2)
                engine.action("stop", id)
                engine.store.putHealthJob(engine.store.ensureHealthJob(id).copy(status = status, reason = reason))
                id
            }
            awaitHealthWork()
            healthAccess.available = true
            scheduledHealth = { ride, _ -> engine.health.save(ride) }
            invokeHealthAction("retryWorkoutHealthSave", id)
            assertEquals(if (status == "submitted") "uncertain" else status, engine.store.healthJob(id)?.status)
            assertFalse(engine.store.metadata(id).flag("healthRetryAvailable"))
            assertTrue(healthAccess.batches.isEmpty())
        }
    }

    @Test
    fun publicationIsScheduledOnlyAfterLocalFinish() {
        val scheduled = java.util.concurrent.CopyOnWriteArrayList<String>()
        scheduledHealth = { id, _ -> scheduled.add(id) }
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(35)
            telemetry()
            (field("tick") as Runnable).run()
            engine.handler.removeCallbacks(field("tick") as Runnable)
            assertNull(engine.store.healthJob(id))
            assertTrue(scheduled.isEmpty())
            assertEquals("completed", engine.action("stop", id)["phase"])
            assertNotNull(engine.store.healthJob(id))
            id
        }
        awaitHealthWork()
        assertEquals(listOf(id), scheduled.toList())
    }

    @Test
    fun refreshHealthOffRideUsesReadOnlyWorkerAndStopsAfterBoundedReads() {
        val scheduled = java.util.concurrent.CopyOnWriteArrayList<String>()
        scheduledHealth = { id, _ -> scheduled.add(id) }
        val id = command {
            val id = start()
            advanceTo(2)
            engine.action("stop", id)
            engine.refreshHealth(id)
            id
        }
        awaitHealthWork()
        assertEquals(listOf(id), scheduled.toList())
        assertNull(engine.store.healthJob(id))
        healthAccess.available = true
        healthAccess.grants =
            HealthReadback.types
                .map {
                    androidx.health.connect.client.permission.HealthPermission.getReadPermission(it)
                }
                .toSet()
        RefreshHealthWorker.access = healthAccess
        RefreshHealthWorker.clock = timeUs(engine.store.metadata(id).str("endedAt")) + 3_601_000_000L
        RefreshHealthWorker.mode = app.powerlog.core.health.ExecutionMode.foreground
        RefreshHealthWorker.continuations.clear()
        repeat(4) {
            val worker =
                androidx.work.testing.TestWorkerBuilder.from(
                        RuntimeEnvironment.getApplication(),
                        RefreshHealthWorker::class.java,
                        java.util.concurrent.Executor { it.run() },
                    )
                    .setInputData(androidx.work.workDataOf("ride" to id))
                    .build()
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        }
        assertNull(engine.store.healthJob(id))
        assertTrue(healthAccess.batches.isEmpty())
        // A bounded first wake may schedule the remaining read types, but no work remains afterward.
        val exporter =
            HealthExport(
                RuntimeEnvironment.getApplication(),
                engine.store,
                healthAccess,
                { RefreshHealthWorker.clock },
                { app.powerlog.core.health.ExecutionMode.foreground },
            )
        assertFalse(exporter.writesPending(id))
        assertNull(exporter.nextRefreshUs(id))
        val continuations = RefreshHealthWorker.continuations.size
        val worker =
            androidx.work.testing.TestWorkerBuilder.from(
                    RuntimeEnvironment.getApplication(),
                    RefreshHealthWorker::class.java,
                    java.util.concurrent.Executor { it.run() },
                )
                .setInputData(androidx.work.workDataOf("ride" to id))
                .build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(continuations, RefreshHealthWorker.continuations.size)
    }

    @Test
    fun foregroundRetainsDeferredReadsUntilEnqueueAcknowledgement() {
        val id = command { start(saveToHealth = true) }
        engine.store.deferHealthReads(id, true)
        scheduledHealth = { _, _ -> throw IllegalStateException("scheduler unavailable") }
        engine.foreground()
        awaitHealthWork()
        assertTrue(engine.store.healthReadsDeferred(id))
        assertNotNull(command { engine.state()["failure"] })
        var scheduled = 0
        scheduledHealth = { ride, _ ->
            assertEquals(id, ride)
            assertTrue(engine.store.healthReadsDeferred(id))
            scheduled++
        }
        engine.foreground()
        awaitHealthWork()
        assertEquals(1, scheduled)
        assertFalse(engine.store.healthReadsDeferred(id))
    }

    @Test
    fun foregroundAcknowledgementDoesNotClearANewerDeferral() {
        val id = command { start(saveToHealth = true) }
        engine.store.deferHealthReads(id, true)
        scheduledHealth = { _, _ -> engine.store.deferHealthReads(id, true) }
        engine.foreground()
        awaitHealthWork()
        assertTrue(engine.store.healthReadsDeferred(id))
        scheduledHealth = { _, _ -> }
        engine.foreground()
        awaitHealthWork()
        assertFalse(engine.store.healthReadsDeferred(id))
    }

    @Test
    fun gpsBatchKeepsFixTimesWhenProcessingAdvancesMonotonicTime() = command {
        val id = start(gps = true)
        advanceTo(3)
        val fixes =
            listOf(1L, 2L).map { second ->
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0
                    longitude = second * 0.00001
                    accuracy = 5f
                    time = java.time.Instant.parse(readClock().timestamp).minusSeconds(3603 - second).toEpochMilli()
                    elapsedRealtimeNanos = (field("startClock") as Long) * 1_000_000 + second * 1_000_000_000
                }
            }
        val advancing =
            object : AbstractMutableList<Location>() {
                override val size = fixes.size

                override fun get(index: Int): Location {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(100))
                    return fixes[index]
                }

                override fun add(index: Int, element: Location) = error("immutable")

                override fun removeAt(index: Int): Location = error("immutable")

                override fun set(index: Int, element: Location): Location = error("immutable")
            }
        (field("locationListener") as LocationListener).onLocationChanged(advancing)
        engine.flush()
        assertEquals(listOf(1.0, 2.0), recordStore(id).page(id).map { it.time })
        assertEquals(fixes.map { iso(it.time) }, recordStore(id).page(id).map { it.timestamp })
    }

    @Test
    fun readerSetChangesPublishOnceAndNestedReadersDoNotReadStorage() {
        val id = command { start() }
        var publications = 0
        val listener: (String, Payload) -> Unit = { event, _ -> if (event == "onWorkoutState") publications++ }
        engine.listeners.add(listener)
        var queries = 0
        engine.store.queryObserver = { sql, _ ->
            if (sql.startsWith("SELECT metadata") || "publication_" in sql) queries++
        }
        try {
            command {
                repeat(100) { engine.store.retain(id) }
                repeat(99) { engine.store.release(id) }
            }
            command {}
            assertEquals(1, publications)
            command {
                repeat(100) {
                    engine.store.retain(id)
                    engine.store.release(id)
                }
            }
            command {}
            assertEquals(1, publications)
            assertEquals(0, queries)
            command { engine.store.release(id) }
            command {}
            assertEquals(2, publications)
        } finally {
            engine.store.queryObserver = null
            engine.listeners.remove(listener)
        }
    }

    @Test
    fun telemetryNotificationDoesNotReadStoredSnapshots() = command {
        start()
        var metadataQueries = 0
        engine.store.queryObserver = { sql, _ ->
            if (sql.contains("FROM rides") || sql.contains("publication_")) metadataQueries++
        }
        try {
            repeat(3) {
                advanceTo((it + 1).toLong())
                telemetry()
            }
        } finally {
            engine.store.queryObserver = null
        }
        assertEquals(0, metadataQueries)
    }

    @Test
    fun gpsBatchUsesEachMonotonicAgeWithSkewedPhoneUtc() = command {
        val id = start(true)
        advanceTo(3)
        val gnss = java.time.Instant.parse(readClock().timestamp).minusSeconds(3600)
        val batch =
            listOf(2L, 1L).map { second ->
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0
                    longitude = second * 0.00001
                    accuracy = 5f
                    time = gnss.minusSeconds(3 - second).toEpochMilli()
                    elapsedRealtimeNanos = (field("startClock") as Long) * 1_000_000 + second * 1_000_000_000
                }
            }
        (field("locationListener") as LocationListener).onLocationChanged(batch)
        engine.flush()
        val rows = recordStore(id).page(id)
        assertEquals(listOf(1.0, 2.0), rows.map { it.time })
        assertEquals(batch.sortedBy { it.elapsedRealtimeNanos }.map { iso(it.time) }, rows.map { it.timestamp })
    }

    @Test
    fun discardFinishesLocallyWhileHealthHoldsExecutionLock() {
        val id = command { start(saveToHealth = true) }
        openNotificationService()
        val other = command {
            engine.store.create(RideOptions(true, saveToHealth = false, recordGPS = false), SystemRecordingClock.read())
        }
        val held = CountDownLatch(1)
        val write = CountDownLatch(1)
        val worker = CompletableFuture<Unit>()
        val discarded = CompletableFuture<Payload>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val store = RideStore.shared(RuntimeEnvironment.getApplication())
                RideStore.withHealthExecution {
                    held.countDown()
                    check(write.await(5, TimeUnit.SECONDS))
                    store.update(other, "running", RideTiming(1.0, 1.0, iso()))
                }
                worker.complete(Unit)
            } catch (error: Throwable) {
                worker.completeExceptionally(error)
            }
        }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            engine.handler.post {
                try {
                    discarded.complete(engine.action("discard", id))
                } catch (error: Throwable) {
                    discarded.completeExceptionally(error)
                }
            }
            assertEquals("idle", discarded.get(5, TimeUnit.SECONDS)["phase"])
            assertFalse(engine.store.exists(id))
            assertNull(engine.store.healthJob(id))
            assertTrue(healthAccess.batches.isEmpty())
            val responsive = CompletableFuture<Boolean>()
            engine.handler.post { responsive.complete(true) }
            assertTrue(responsive.get(1, TimeUnit.SECONDS))
            write.countDown()
            worker.get(5, TimeUnit.SECONDS)
            awaitHealthWork()
            command {
                assertEquals("idle", engine.state()["phase"])
                assertFalse(engine.store.exists(id))
                assertEquals(1.0, engine.store.timing(other).timer, 0.0)
            }
        } finally {
            write.countDown()
            runCatching { worker.get(35, TimeUnit.SECONDS) }
            runCatching { discarded.get(35, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }
    }

    @Test
    fun publicationReadFailureCannotEscapeTheTickOrLoseItsRetainedRetry() {
        val id = command {
            val id = start(saveToHealth = true)
            telemetry()
            id
        }
        openNotificationService()
        var attempts = 0
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Payload>()
        val listener: (String, Payload) -> Unit = { event, value ->
            if (event == "onWorkoutState") delivered.add(value)
        }
        engine.listeners.add(listener)
        engine.store.queryObserver = { sql, _ ->
            if (sql.startsWith("SELECT payload FROM publication_jobs")) {
                attempts++
                throw SQLiteException("publication projection unavailable")
            }
        }
        try {
            command {
                val original = (field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals
                repeat(3) {
                    (field("tick") as Runnable).run()
                    assertTrue(engine.handler.hasCallbacks(field("tick") as Runnable))
                    assertEquals(
                        original,
                        (field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals,
                    )
                    assertFailure(RideFailure.storageFailed, delivered.last())
                    assertEquals("interrupted", delivered.last()["phase"])
                }
                assertTrue(attempts >= 3)
            }
        } finally {
            engine.store.queryObserver = null
            engine.listeners.remove(listener)
        }
        command {
            engine.flush()
            assertTrue((field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals.isEmpty())
            assertEquals(1, recordStore(id).page(id).count { it.kind == "telemetry" })
            assertEquals("interrupted", engine.state()["phase"])
        }
    }

    @Test
    fun idleHistoricalHealthWorkPublishesBusyThroughTheBridgeSubscription() {
        val id = command {
            healthAccess.available = true
            val id =
                engine.store.create(
                    RideOptions(true, saveToHealth = true, recordGPS = false),
                    SystemRecordingClock.read(),
                )
            engine.store.fixtureOriginalEnd(id, RideTiming(2.0, 2.0, iso(1767225602000L)))
            id
        }
        val busy = CompletableFuture<Payload>()
        val idle = CompletableFuture<Payload>()
        val module = PowerLogBridgeModule { engine }
        val emitter =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(expo.modules.kotlin.events.EventEmitter::class.java),
            ) { _, method, arguments ->
                if (method.name == "emit" && arguments?.get(0) == "onWorkoutState" && arguments[1] is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST") val state = arguments[1] as Payload
                    if (state["healthBusy"] == true) busy.complete(state) else if (busy.isDone) idle.complete(state)
                }
                null
            }
        expo.modules.kotlin.modules.Module::class
            .java
            .getDeclaredField("moduleEventEmitter\$delegate")
            .apply { isAccessible = true }
            .set(module, lazy { emitter })
        val appContext = org.robolectric.shadow.api.Shadow.newInstanceOf(expo.modules.kotlin.AppContext::class.java)
        expo.modules.kotlin.modules.Module::class
            .java
            .getDeclaredField("_appContextHolder")
            .apply { isAccessible = true }
            .set(module, java.lang.ref.WeakReference(appContext))
        val definition = module.definition()
        (definition.eventListeners.getValue(expo.modules.kotlin.events.EventName.MODULE_CREATE)
                as expo.modules.kotlin.events.BasicEventListener)
            .call()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        healthAccess.beforeWrite = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val worker = CompletableFuture<Unit>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                repeat(15) { engine.health.save(id) }
                worker.complete(Unit)
            } catch (error: Throwable) {
                worker.completeExceptionally(error)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val delivered = busy.get(5, TimeUnit.SECONDS)
            assertEquals("idle", delivered["phase"])
            assertNull(delivered["id"])
            command {
                assertWireFixture("idleHealthBusy", delivered)
                assertThrows(IllegalStateException::class.java) { engine.delete(id) }
            }
        } finally {
            release.countDown()
            worker.get(10, TimeUnit.SECONDS)
            healthAccess.beforeWrite = {}
            assertEquals(false, idle.get(5, TimeUnit.SECONDS)["healthBusy"])
            (definition.eventListeners.getValue(expo.modules.kotlin.events.EventName.MODULE_DESTROY)
                    as expo.modules.kotlin.events.BasicEventListener)
                .call()
            executor.shutdownNow()
        }
        assertEquals(false, idle.get(5, TimeUnit.SECONDS)["healthBusy"])
    }

    @Test
    fun exhaustedPreSubmissionWorkerFailureIsVisibleAndCanBeExplicitlyRetried() {
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(2)
            engine.action("stop", id)
            engine.store.ensureHealthJob(id)
            id
        }
        awaitHealthWork()
        RefreshHealthWorker.access = healthAccess
        healthAccess.beforeGranted = { error("provider temporarily unavailable") }
        try {
            val worker =
                androidx.work.testing.TestWorkerBuilder.from(
                        RuntimeEnvironment.getApplication(),
                        RefreshHealthWorker::class.java,
                        java.util.concurrent.Executor { it.run() },
                    )
                    .setInputData(androidx.work.workDataOf("ride" to id))
                    .setRunAttemptCount(3)
                    .build()
            assertEquals(androidx.work.ListenableWorker.Result.failure(), worker.doWork())
            assertEquals("failed", engine.store.healthJob(id)?.status)
            assertEquals("transientFailure", engine.store.healthJob(id)?.reason)
            assertTrue(healthAccess.batches.isEmpty())
        } finally {
            healthAccess.beforeGranted = {}
        }
        invokeHealthAction("retryWorkoutHealthSave", id)
        assertEquals("ready", engine.store.healthJob(id)?.status)
    }

    @Test
    fun healthWorkerPublishesThroughTheEngineStore() {
        val id = command { start(saveToHealth = true).also { engine.store.ensureHealthJob(it) } }
        EngineStoreWorker.stores.clear()
        assertEquals(androidx.work.ListenableWorker.Result.success(), healthWorker(id).doWork())
        assertSame(engine.store, EngineStoreWorker.stores.single())
    }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun workerWriteTransactionDelaysTheCaptureFlushWithoutFailingIt() {
        val id = command { start(saveToHealth = true).also { engine.store.ensureHealthJob(it) } }
        val held = CountDownLatch(1)
        EngineStoreWorker.hold = { store ->
            store.transaction {
                held.countDown()
                Thread.sleep(3000)
            }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit<androidx.work.ListenableWorker.Result> { healthWorker(id).doWork() }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            command {
                telemetry()
                engine.flush()
                assertFalse(engine.store.lifecycleState().ride!!.storageInterrupted)
                assertEquals(1, recordStore(id).page(id).count { it.kind == "telemetry" })
            }
            assertEquals(androidx.work.ListenableWorker.Result.success(), work.get(10, TimeUnit.SECONDS))
        } finally {
            EngineStoreWorker.hold = {}
            executor.shutdownNow()
        }
    }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun busyCaptureFlushKeepsItsBatchForTheNextTick() {
        val id = command { start() }
        val other = RideStore(RuntimeEnvironment.getApplication())
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val lock = executor.submit {
                other.transaction {
                    held.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            command {
                telemetry()
                val buffered = (field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals
                assertEquals(1, buffered.size)
                (field("tick") as Runnable).run()
                engine.handler.removeCallbacks(field("tick") as Runnable)
                assertEquals(buffered, (field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals)
                assertFalse(engine.store.lifecycleState().ride!!.storageInterrupted)
                assertEquals("running", engine.state()["phase"])
            }
            release.countDown()
            lock.get(10, TimeUnit.SECONDS)
            command {
                (field("tick") as Runnable).run()
                engine.handler.removeCallbacks(field("tick") as Runnable)
                assertTrue((field("captureBatch") as app.powerlog.core.lifecycle.CaptureBatch).originals.isEmpty())
                assertEquals(1, recordStore(id).page(id).count { it.kind == "telemetry" })
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            other.close()
        }
    }

    @Test
    fun projectedHealthReasonAndCurrentWorkFactsReachTheLiveWireSnapshot() {
        val id = command {
            val id = start(saveToHealth = true)
            advanceTo(2)
            engine.action("stop", id)
            id
        }
        awaitHealthWork()
        command {
            val component =
                app.powerlog.core.health.ComponentResult(
                    app.powerlog.core.health.Component.workout,
                    engine.store.revision(id),
                    true,
                    app.powerlog.core.health.ComponentState.failed,
                    app.powerlog.core.health.HealthReason.permission,
                    emptyList(),
                )
            engine.store.healthStatus(
                id,
                HealthExportResult("unavailable", reason = "permission", components = listOf(component)),
            )
            val wire = engine.state()
            assertEquals("failed", wire["healthKitState"])
            assertEquals("permission", wire["healthReason"])
            assertEquals(false, wire["healthBusy"])
            assertEquals(false, wire["startBusy"])
            assertEquals(false, wire["finalizationBusy"])
            assertWireFixture("unavailable", wire)
            RideStore.withHealthExecution {
                assertEquals(true, engine.state()["healthBusy"])
                assertWireFixture("healthBusy", engine.state())
                assertThrows(IllegalStateException::class.java) { engine.delete(id) }
            }
        }
    }

    @Test
    fun failedStartPublishesItsCurrentStartWork() =
        command<Unit> {
            var starting: Payload? = null
            RecordingContextImpl.beforeForegroundStart = {
                starting = engine.state()
                assertEquals("start", starting!!["pendingAction"])
                assertEquals(true, starting!!["startBusy"])
                assertEquals(false, starting!!["healthBusy"])
                assertEquals(false, starting!!["finalizationBusy"])
                assertWireFixture("startBusy", starting!!)
                throw IllegalStateException("foreground startup unavailable")
            }
            try {
                assertThrows(IllegalStateException::class.java) { start() }
                assertNotNull(starting)
                assertEquals("idle", engine.state()["phase"])
                assertEquals(false, engine.state()["startBusy"])
            } finally {
                RecordingContextImpl.beforeForegroundStart = null
            }
        }
}
