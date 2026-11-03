package app.powerlog.bridge

import app.powerlog.core.lifecycle.StorageReclaim
import app.powerlog.core.lifecycle.StorageReclaimAction
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideStoreReclaimTest {
    private lateinit var store: RideStore

    @Before
    fun setup() {
        store = RideStore(RuntimeEnvironment.getApplication(), "reclaim-${UUID.randomUUID()}.sqlite")
    }

    @After
    fun close() {
        store.close()
    }

    private fun pragma(name: String): Long = store.transaction {
        store.writableDatabase.rawQuery("PRAGMA $name", null).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun integrity(): String =
        store.readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }

    private fun truncateWal() =
        store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()) }

    private fun append(id: String, from: Int, count: Int) {
        for (page in from until from + count step 256) store.transaction {
            for (row in page until minOf(from + count, page + 256)) {
                val time = row / 8.0
                store.insert(
                    id,
                    time,
                    iso(1767225600000 + row * 125L),
                    "telemetry",
                    true,
                    0,
                    mapOf(
                        "humanPowerW" to 100.0 + row % 211,
                        "cadenceRpm" to 80.0 + row % 13,
                        "batteryCurrentA" to (row % 97) / 4.0,
                    ),
                    "X6|test|5.3",
                    "one",
                )
            }
        }
    }

    private fun ride(rows: Int, phase: String = "completed"): String {
        val id =
            store.create(
                RideOptions(indoor = true, saveToHealth = false, recordGPS = false),
                RecordingClockReading("2026-01-01T00:00:00Z", 0),
            )
        append(id, 0, rows)
        if (phase != "running") store.update(id, phase, RideTiming(rows / 8.0, rows / 8.0, iso(1767225600000)))
        return id
    }

    private fun rides(count: Int, rows: Int): List<Pair<String, Long>> {
        truncateWal()
        return List(count) {
            val before = store.fileBytes
            val id = ride(rows)
            truncateWal()
            id to store.fileBytes - before
        }
    }

    private fun walBytes() = File(store.writableDatabase.path + "-wal").length()

    private fun observations(id: String): Long =
        store.select("SELECT count(*) FROM observations WHERE ride=?", arrayOf(id)) {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun withoutAutoVacuum() {
        store.writableDatabase.execSQL("PRAGMA auto_vacuum=NONE")
        store.writableDatabase.execSQL("VACUUM")
        assertEquals(0L, pragma("auto_vacuum"))
    }

    @Test
    fun deletingARideGivesItsPagesBackInBoundedSteps() {
        assertEquals(2L, pragma("auto_vacuum"))
        assertTrue(pragma("journal_size_limit") in 0L..(8L shl 20))
        val created = rides(3, 2048)
        val (deleted, rideBytes) = created[1]
        val kept = created.filter { it.first != deleted }.associate { it.first to observations(it.first) }
        val before = store.fileBytes
        store.remove(deleted)
        val steps = mutableListOf<StorageReclaimAction>()
        store.reclaimStepObserver = { steps += it }
        val started = System.nanoTime()
        val outcome = store.reclaimSpace()
        val milliseconds = (System.nanoTime() - started) / 1_000_000
        val after = store.fileBytes
        println(
            "Reclaim incremental: deleted ride $rideBytes bytes; database and WAL $before to $after bytes; " +
                "${outcome.steps} steps in $milliseconds ms"
        )
        assertNull(outcome.failure)
        assertNull(outcome.deferred)
        assertFalse(outcome.converted)
        assertTrue(outcome.steps >= rideBytes / StorageReclaim.stepBytes)
        assertEquals(StorageReclaimAction.done, steps.last())
        assertTrue("$before to $after for $rideBytes", before - after >= rideBytes * 85 / 100)
        assertTrue("$before to $after for $rideBytes", before - after <= rideBytes * 110 / 100)
        assertEquals(0L, walBytes())
        assertEquals(0L, pragma("freelist_count"))
        assertEquals("ok", integrity())
        for ((id, count) in kept) assertEquals(count, observations(id))
        assertEquals(0, store.reclaimSpace().steps)
    }

    @Test
    fun aFullAutoVacuumStoreSwitchesToIncrementalWithoutVacuum() {
        store.writableDatabase.execSQL("PRAGMA auto_vacuum=FULL")
        assertEquals(1L, pragma("auto_vacuum"))
        val (first, second) = rides(2, 1024).map { it.first }
        store.remove(first)
        store.beforeVacuum = { fail("switching from full to incremental needs no VACUUM") }
        val outcome = store.reclaimSpace()
        assertTrue(outcome.converted)
        assertNull(outcome.failure)
        assertEquals(2L, pragma("auto_vacuum"))
        store.remove(second)
        assertTrue(store.reclaimSpace().steps > 0)
        assertEquals("ok", integrity())
    }

    @Test
    fun aStoreWithoutAutoVacuumConvertsOnlyWhenIdleWithRoomForTheCopy() {
        withoutAutoVacuum()
        val created = rides(3, 2048)
        val recording = ride(80, phase = "running")
        truncateWal()
        val before = store.fileBytes
        store.remove(created[0].first)
        store.availableBytes = { Long.MAX_VALUE / 4 }
        val whileRecording = store.reclaimSpace()
        assertEquals(StorageReclaimAction.waitForIdle, whileRecording.deferred)
        assertFalse(whileRecording.converted)
        assertEquals(0L, pragma("auto_vacuum"))
        store.update(recording, "completed", RideTiming(10.0, 10.0, iso(1767225600000)))
        store.availableBytes = { 0 }
        assertEquals(StorageReclaimAction.waitForSpace, store.reclaimSpace().deferred)
        assertEquals(0L, pragma("auto_vacuum"))
        store.availableBytes = { Long.MAX_VALUE / 4 }
        val started = System.nanoTime()
        val conversion = store.reclaimSpace()
        val milliseconds = (System.nanoTime() - started) / 1_000_000
        val after = store.fileBytes
        println(
            "Reclaim conversion: deleted ride ${created[0].second} bytes; database and WAL $before to $after bytes " +
                "in $milliseconds ms"
        )
        assertTrue(conversion.converted)
        assertNull(conversion.failure)
        assertEquals(2L, pragma("auto_vacuum"))
        assertTrue("$before to $after", before - after >= created[0].second * 85 / 100)
        assertEquals(0L, walBytes())
        assertEquals("ok", integrity())
        for ((id, _) in created.drop(1)) assertEquals(2048L, observations(id))
        store.remove(created[1].first)
        val next = store.reclaimSpace()
        assertFalse(next.converted)
        assertTrue(next.steps > 0)
    }

    @Test
    fun aWriteDuringAConversionCancelsItAndCommits() {
        withoutAutoVacuum()
        val (deleted, kept) = rides(2, 1024).map { it.first }
        store.remove(deleted)
        store.availableBytes = { Long.MAX_VALUE / 4 }
        val write = CompletableFuture<Unit>()
        store.beforeVacuum = { signal ->
            Thread {
                try {
                    append(kept, 1024, 8)
                    write.complete(Unit)
                } catch (error: Throwable) {
                    write.completeExceptionally(error)
                }
            }
                .start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!signal.isCanceled && System.nanoTime() < deadline) Thread.sleep(1)
        }
        val outcome = store.reclaimSpace()
        write.get(5, TimeUnit.SECONDS)
        assertEquals(StorageReclaimAction.waitForIdle, outcome.deferred)
        assertFalse(outcome.converted)
        assertNull(outcome.failure)
        assertEquals(0L, pragma("auto_vacuum"))
        assertEquals(1032L, observations(kept))
        assertEquals("ok", integrity())
        store.beforeVacuum = null
        assertTrue(store.reclaimSpace().converted)
    }

    @Test
    fun aCaptureBatchCommitsWhileReclaimRuns() {
        val (deleted, _) = rides(1, 2048).first()
        val recording = ride(0, phase = "running")
        store.remove(deleted)
        val finished = AtomicBoolean()
        val commits = AtomicInteger()
        val capture = CompletableFuture<Unit>()
        Thread {
            try {
                while (!finished.get()) {
                    append(recording, commits.get() * 8, 8)
                    commits.incrementAndGet()
                }
                capture.complete(Unit)
            } catch (error: Throwable) {
                capture.completeExceptionally(error)
            }
        }
            .start()
        var committedBetweenSteps: Boolean? = null
        store.reclaimStepObserver = { action ->
            if (action == StorageReclaimAction.reclaim && committedBetweenSteps == null) {
                val before = commits.get()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (commits.get() == before && System.nanoTime() < deadline) Thread.sleep(1)
                committedBetweenSteps = commits.get() > before
            }
        }
        val outcome = store.reclaimSpace()
        finished.set(true)
        capture.get(10, TimeUnit.SECONDS)
        assertNull(outcome.failure)
        assertTrue(outcome.steps > 1)
        assertEquals(true, committedBetweenSteps)
        assertEquals(commits.get() * 8L, observations(recording))
        assertEquals("ok", integrity())
    }
}
