package app.powerlog.platform.files

import app.powerlog.platform.CountingExecutor
import app.powerlog.platform.QueueExecutor
import app.powerlog.platform.Rejection
import app.powerlog.platform.Reply
import app.powerlog.platform.rejection
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSinksTest {
    private lateinit var root: File
    private lateinit var sinks: ExportSinks
    private var now = 1_800_000_000_000L

    @Before
    fun setup() {
        root = File(RuntimeEnvironment.getApplication().filesDir, "exports-${UUID.randomUUID()}")
        sinks = ExportSinks(root, CountingExecutor(), { now }) { "content://test/${it.relativeTo(root).path}" }
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun Reply.answer(): SinkReply = { result ->
        result.fold({ resolve(it) }) { error -> reject((error as SinkException).code, error.message, error) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(block: (Reply) -> Unit): T {
        val reply = Reply()
        block(reply)
        return reply.get() as T
    }

    private fun open(target: ExportSinks = sinks, kind: String = "zip"): String = call {
        target.open(kind, it.answer())
    }

    private fun write(id: String, bytes: ByteArray) = call<Any?> { sinks.write(id, bytes, it.answer()) }

    private fun patch(id: String, offset: Double, bytes: ByteArray) =
        call<Any?> { sinks.writeAt(id, offset, bytes, it.answer()) }

    private fun begin(id: String) = call<Any?> { sinks.beginDeflate(id, it.answer()) }

    private fun end(id: String): Map<String, Any?> = call { sinks.endDeflate(id, it.answer()) }

    private fun commit(id: String, name: String): Map<String, Any?> = call { sinks.commit(id, name, it.answer()) }

    private fun abort(id: String) = call<Any?> { sinks.abort(id, it.answer()) }

    private fun purge(kind: String) = call<Any?> { sinks.purge(kind, it.answer()) }

    private fun staging(id: String) = File(root, ".powerlog-export-$id.part")

    private fun inflate(bytes: ByteArray, offset: Int, size: Int, trailing: Int): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(bytes, offset, size + trailing)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            check(count > 0 || inflater.finished()) { "Inflate stalled" }
            output.write(buffer, 0, count)
        }
        assertEquals(trailing, inflater.remaining)
        inflater.end()
        return output.toByteArray()
    }

    @Test
    fun deflateStagesInflateToTheirInputWithCrcAndSizesBesideRawWritesAndPatches() {
        val id = open()
        val header = ByteArray(30) { it.toByte() }
        write(id, header)
        begin(id)
        val first =
            listOf(
                Random(7).nextBytes(200_000),
                "elapsedSeconds,humanPowerW\n".repeat(20_000).toByteArray(),
                ByteArray(0),
                byteArrayOf(1, 2, 3),
            )
        first.forEach { write(id, it) }
        val stage = end(id)
        val input = first.reduce(ByteArray::plus)
        assertEquals(CRC32().apply { update(input) }.value.toDouble(), stage["crc32"])
        assertEquals(input.size.toDouble(), stage["inputBytes"])
        val compressed = (stage["outputBytes"] as Double).toInt()
        assertTrue(compressed in 1 until input.size)
        write(id, byteArrayOf(9, 9, 9))
        begin(id)
        val second = "phone,1.5\n".repeat(5000).toByteArray()
        write(id, second)
        val next = end(id)
        assertEquals(CRC32().apply { update(second) }.value.toDouble(), next["crc32"])
        val tail = (next["outputBytes"] as Double).toInt()
        patch(id, 4.0, byteArrayOf(0x55, 0x66))
        patch(id, 30.0 + compressed, byteArrayOf(8))
        val bytes = File(root, "$id/PowerLog-ride.zip")
        assertEquals(
            mapOf(
                "uri" to "content://test/$id/PowerLog-ride.zip",
                "name" to "PowerLog-ride.zip",
                "mimeType" to "application/zip",
                "sizeBytes" to (30 + compressed + 3 + tail).toDouble(),
            ),
            commit(id, "PowerLog-ride.zip"),
        )
        val written = bytes.readBytes()
        assertArrayEquals(
            header.copyOf().also {
                it[4] = 0x55
                it[5] = 0x66
            },
            written.copyOfRange(0, 30),
        )
        assertArrayEquals(input, inflate(written, 30, compressed, 3))
        assertArrayEquals(byteArrayOf(8, 9, 9), written.copyOfRange(30 + compressed, 33 + compressed))
        assertArrayEquals(second, inflate(written, 33 + compressed, tail, 0))
    }

    @Test
    fun fileSystemFailuresRejectWithIo() {
        val blocked = File(RuntimeEnvironment.getApplication().filesDir, "exports-${UUID.randomUUID()}")
        blocked.writeText("")
        try {
            val sinks = ExportSinks(blocked, CountingExecutor(), { now }) { "content://test/${it.name}" }
            val rejection = assertThrows(Rejection::class.java) { open(sinks) }
            assertEquals("io", rejection.code)
        } finally {
            blocked.delete()
        }
    }

    @Test
    fun outOfSpaceRejectsWithFull() {
        val full =
            java.io.IOException(
                "write failed",
                android.system.ErrnoException("write", android.system.OsConstants.ENOSPC),
            )
        assertEquals("full", ExportSinks.rejection(full).code)
        assertEquals("io", ExportSinks.rejection(java.io.IOException("EIO")).code)
        assertEquals("failed", ExportSinks.rejection(IllegalStateException()).code)
    }

    @Test
    fun patchesLieInsideWrittenBytesAndBeforeAnOpenStage() {
        val id = open()
        write(id, ByteArray(10))
        for (offset in listOf(8.0, -1.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(
            "$offset",
            "invalid",
            rejection { patch(id, offset, ByteArray(3)) },
        )
        patch(id, 7.0, ByteArray(3))
        begin(id)
        write(id, Random(3).nextBytes(300_000))
        assertTrue(staging(id).length() > 12)
        patch(id, 2.0, byteArrayOf(1, 2))
        assertEquals("invalid", rejection { patch(id, 9.0, ByteArray(2)) })
        assertEquals("invalid", rejection { patch(id, 10.0, ByteArray(2)) })
        assertEquals("invalid", rejection { begin(id) })
        assertEquals("invalid", rejection { commit(id, "PowerLog.zip") })
        end(id)
        assertEquals("invalid", rejection { end(id) })
        patch(id, 10.0, byteArrayOf(0))
        commit(id, "PowerLog.zip")
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), File(root, "$id/PowerLog.zip").readBytes().copyOfRange(0, 4))
    }

    @Test
    fun commitPublishesEachExportInItsOwnDirectory() {
        val first = open(kind = "fit")
        write(first, "first".toByteArray())
        val second = open(kind = "fit")
        write(second, "second".toByteArray())
        assertEquals("content://test/$first/PowerLog.fit", commit(first, "PowerLog.fit")["uri"])
        assertEquals("content://test/$second/PowerLog.fit", commit(second, "PowerLog.fit")["uri"])
        assertNotEquals(first, second)
        assertEquals("first", File(root, "$first/PowerLog.fit").readText())
        assertEquals("second", File(root, "$second/PowerLog.fit").readText())
        assertEquals(setOf(first, second), root.list()!!.toSet())
        assertEquals("invalid", rejection { write(first, byteArrayOf(1)) })
        assertEquals("invalid", rejection { commit(first, "PowerLog.fit") })
        abort(first)
        assertEquals("first", File(root, "$first/PowerLog.fit").readText())
    }

    @Test
    fun eachKindCommitsWithItsMimeType() {
        for ((kind, mimeType) in
            listOf(
                "fit" to "application/octet-stream",
                "csv" to "text/csv",
                "zip" to "application/zip",
                "json" to "application/octet-stream",
            )) {
            val id = open(kind = kind)
            write(id, "x".toByteArray())
            val committed = commit(id, "PowerLog.$kind")
            assertEquals(kind, mimeType, committed["mimeType"])
            assertEquals(1.0, committed["sizeBytes"])
        }
    }

    @Test
    fun purgeDeletesTheCommittedFilesOfOneKindOnly() {
        val staged = open(kind = "json")
        val trace = open(kind = "json")
        write(trace, "{}".toByteArray())
        commit(trace, "PowerLog-Bluetooth.json")
        val ride = open(kind = "fit")
        write(ride, "fit".toByteArray())
        commit(ride, "power-log-ride.fit")
        purge("json")
        assertFalse(File(root, trace).exists())
        assertTrue(File(root, "$ride/power-log-ride.fit").exists())
        assertTrue(staging(staged).exists())
        assertEquals("invalid", rejection { purge("gpx") })
    }

    @Test
    fun abortClosesAndDeletesTheStagingFileAndRepeatsSafely() {
        val id = open()
        write(id, ByteArray(100))
        begin(id)
        write(id, ByteArray(100_000))
        assertTrue(staging(id).exists())
        assertNull(abort(id))
        assertFalse(staging(id).exists())
        assertNull(abort(id))
        assertNull(abort("never-opened"))
        assertEquals("invalid", rejection { write(id, byteArrayOf(1)) })
        assertEquals("invalid", rejection { end(id) })
        assertTrue(root.list()!!.isEmpty())
        val unflushed = open()
        sinks.abortAll()
        assertFalse(staging(unflushed).exists())
        assertEquals("failed", rejection { open() })
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun teardownDiscardsACommitQueuedBeforeIt() {
        val work = Executors.newSingleThreadExecutor()
        try {
            val queued = ExportSinks(root, work, { now }) { it.path }
            val id = open(queued)
            call<Any?> { queued.write(id, "export".toByteArray(), it.answer()) }
            val release = CountDownLatch(1)
            work.execute { release.await() }
            val commit = Reply()
            queued.commit(id, "PowerLog.zip", commit.answer())
            queued.abortAll()
            release.countDown()
            assertEquals("failed", rejection { commit.get() })
            work.submit {}.get(20, TimeUnit.SECONDS)
            assertFalse(File(root, id).exists())
            assertFalse(staging(id).exists())
            assertEquals("failed", rejection { open(queued) })
        } finally {
            work.shutdownNow()
        }
    }

    @Test
    fun openAndStartupCleanupDeleteStagingFilesAndDayOldExportsOnly() {
        root.mkdirs()
        val stale = File(root, ".powerlog-export-${UUID.randomUUID()}.part").apply { writeText("stale") }
        val unrelated = File(root, "notes.part").apply { writeText("keep") }
        fun committed(age: Long) =
            File(root, UUID.randomUUID().toString()).apply {
                mkdirs()
                File(this, "PowerLog.zip").writeText("export")
                assertTrue(setLastModified(now - age))
            }
        val old = committed(ExportSinks.RETENTION + 1000)
        val recent = committed(ExportSinks.RETENTION - 60_000)
        val active = open()
        assertFalse(stale.exists())
        assertFalse(old.exists())
        assertTrue(File(recent, "PowerLog.zip").exists())
        assertTrue(unrelated.exists())
        assertTrue(staging(active).exists())
        val another = open()
        assertTrue(staging(active).exists())
        now += 60_001
        sinks.cleanup()
        assertFalse(recent.exists())
        assertTrue(staging(active).exists())
        val restarted = ExportSinks(root, CountingExecutor(), { now }) { it.path }
        restarted.cleanup()
        assertTrue(staging(active).exists() && staging(another).exists())
        abort(active)
        abort(another)
        File(root, ".powerlog-export-${UUID.randomUUID()}.part").writeText("left by a stopped process")
        restarted.cleanup()
        assertEquals(setOf("notes.part"), root.list()!!.toSet())
    }

    @Test
    fun sinkWorkRunsOnTheExportExecutorOneWriteAtATime() {
        val queue = QueueExecutor()
        val queued = ExportSinks(root, queue, { now }) { it.path }
        val opened = Reply()
        queued.open("fit", opened.answer())
        assertFalse(opened.settled)
        queue.drain()
        val id = opened.get() as String
        val first = Reply()
        val second = Reply()
        queued.write(id, byteArrayOf(1), first.answer())
        queued.write(id, byteArrayOf(2, 3), second.answer())
        assertFalse(first.settled || second.settled)
        assertEquals(0L, staging(id).length())
        queue.runNext()
        assertTrue(first.settled && !second.settled)
        queue.drain()
        assertArrayEquals(byteArrayOf(1, 2, 3), staging(id).readBytes())
        queued.abort(id, Reply().answer())
        queue.drain()
    }

    @Test
    fun anUnknownKindIsInvalidWithoutTouchingTheDirectory() {
        assertEquals("invalid", rejection { open(kind = "gpx") })
        assertFalse(root.exists())
    }

    @Test
    fun committedExportsAreSharedThroughTheFileProviderExportRoot() {
        val application = RuntimeEnvironment.getApplication()
        val authority = registerFileProvider(application)
        val production = ExportSinks.create(application, CountingExecutor())
        try {
            val id = open(production, "fit")
            call<Any?> { production.write(id, "fit".toByteArray(), it.answer()) }
            val uri = call<Map<String, Any?>> { production.commit(id, "PowerLog-ride.fit", it.answer()) }["uri"]
            assertEquals("content://$authority/ride-exports/$id/PowerLog-ride.fit", uri)
            assertEquals("fit", File(application.filesDir, "exports/$id/PowerLog-ride.fit").readText())
        } finally {
            File(application.filesDir, "exports").deleteRecursively()
        }
    }
}
