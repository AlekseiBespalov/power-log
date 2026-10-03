package app.powerlog.bridge

import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSinkTest {
    private lateinit var root: File
    private lateinit var sink: ExportSink
    private var now = 1_800_000_000_000L
    private val context = mapOf("exportedAt" to "2026-10-02T00:00:00.000Z", "platform" to "android")

    @Before
    fun setup() {
        root = File(RuntimeEnvironment.getApplication().filesDir, "exports-${UUID.randomUUID()}")
        sink = ExportSink(root, CountingExecutor(), { now }) { "content://test/${it.relativeTo(root).path}" }
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(block: (Reply) -> Unit): T {
        val reply = Reply()
        block(reply)
        return reply.get() as T
    }

    private fun open(target: ExportSink = sink): String = call { target.open("zip", context, it) }

    private fun write(id: String, bytes: ByteArray) = call<Any?> { sink.write(id, bytes, it) }

    private fun patch(id: String, offset: Double, bytes: ByteArray) = call<Any?> { sink.writeAt(id, offset, bytes, it) }

    private fun begin(id: String) = call<Any?> { sink.beginDeflate(id, it) }

    private fun end(id: String): Map<String, Any?> = call { sink.endDeflate(id, it) }

    private fun commit(id: String, name: String): Map<String, Any?> = call { sink.commit(id, name, it) }

    private fun abort(id: String) = call<Any?> { sink.abort(id, it) }

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
        assertEquals(CRC32().apply { update(input) }.value, stage["crc32"])
        assertEquals(input.size.toLong(), stage["inputBytes"])
        val compressed = (stage["outputBytes"] as Long).toInt()
        assertTrue(compressed in 1 until input.size)
        write(id, byteArrayOf(9, 9, 9))
        begin(id)
        val second = "phone,1.5\n".repeat(5000).toByteArray()
        write(id, second)
        val next = end(id)
        assertEquals(CRC32().apply { update(second) }.value, next["crc32"])
        val tail = (next["outputBytes"] as Long).toInt()
        patch(id, 4.0, byteArrayOf(0x55, 0x66))
        patch(id, 30.0 + compressed, byteArrayOf(8))
        val uri = commit(id, "PowerLog-ride.zip")["uri"]
        assertEquals("content://test/$id/PowerLog-ride.zip", uri)
        val bytes = File(root, "$id/PowerLog-ride.zip").readBytes()
        assertEquals(30 + compressed + 3 + tail, bytes.size)
        assertArrayEquals(
            header.copyOf().also {
                it[4] = 0x55
                it[5] = 0x66
            },
            bytes.copyOfRange(0, 30),
        )
        assertArrayEquals(input, inflate(bytes, 30, compressed, 3))
        assertArrayEquals(byteArrayOf(8, 9, 9), bytes.copyOfRange(30 + compressed, 33 + compressed))
        assertArrayEquals(second, inflate(bytes, 33 + compressed, tail, 0))
    }

    @Test
    fun patchesLieInsideWrittenBytesAndBeforeAnOpenStage() {
        val id = open()
        write(id, ByteArray(10))
        for (offset in listOf(8.0, -1.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) assertEquals(
            "$offset",
            "sink",
            rejection { patch(id, offset, ByteArray(3)) },
        )
        patch(id, 7.0, ByteArray(3))
        begin(id)
        write(id, Random(3).nextBytes(300_000))
        assertTrue(staging(id).length() > 12)
        patch(id, 2.0, byteArrayOf(1, 2))
        assertEquals("sink", rejection { patch(id, 9.0, ByteArray(2)) })
        assertEquals("sink", rejection { patch(id, 10.0, ByteArray(2)) })
        assertEquals("sink", rejection { begin(id) })
        assertEquals("sink", rejection { commit(id, "PowerLog.zip") })
        end(id)
        assertEquals("sink", rejection { end(id) })
        patch(id, 10.0, byteArrayOf(0))
        commit(id, "PowerLog.zip")
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), File(root, "$id/PowerLog.zip").readBytes().copyOfRange(0, 4))
    }

    @Test
    fun commitPublishesEachExportInItsOwnDirectory() {
        val first = open()
        write(first, "first".toByteArray())
        val second = open()
        write(second, "second".toByteArray())
        assertEquals("content://test/$first/PowerLog.fit", commit(first, "PowerLog.fit")["uri"])
        assertEquals("content://test/$second/PowerLog.fit", commit(second, "PowerLog.fit")["uri"])
        assertNotEquals(first, second)
        assertEquals("first", File(root, "$first/PowerLog.fit").readText())
        assertEquals("second", File(root, "$second/PowerLog.fit").readText())
        assertEquals(setOf(first, second), root.list()!!.toSet())
        assertEquals("sink", rejection { write(first, byteArrayOf(1)) })
        assertEquals("sink", rejection { commit(first, "PowerLog.fit") })
        abort(first)
        assertEquals("first", File(root, "$first/PowerLog.fit").readText())
        val third = open()
        for (name in listOf("", " ", ".hidden", "a/b", "a\\b", "line\nbreak", "x".repeat(256))) assertEquals(
            "'$name'",
            "sink",
            rejection { commit(third, name) },
        )
        assertTrue(staging(third).exists())
        abort(third)
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
        assertEquals("sink", rejection { write(id, byteArrayOf(1)) })
        assertEquals("sink", rejection { end(id) })
        assertTrue(root.list()!!.isEmpty())
        val unflushed = open()
        sink.abortAll()
        assertFalse(staging(unflushed).exists())
        assertEquals("cancelled", rejection { open() })
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun teardownDiscardsACommitQueuedBeforeIt() {
        val work = Executors.newSingleThreadExecutor()
        try {
            val queued = ExportSink(root, work, { now }) { it.path }
            val id = open(queued)
            call<Any?> { queued.write(id, "export".toByteArray(), it) }
            val release = CountDownLatch(1)
            work.execute { release.await() }
            val commit = Reply()
            queued.commit(id, "PowerLog.zip", commit)
            queued.abortAll()
            release.countDown()
            assertEquals("cancelled", rejection { commit.get() })
            work.submit {}.get(20, TimeUnit.SECONDS)
            assertFalse(File(root, id).exists())
            assertFalse(staging(id).exists())
            assertEquals("cancelled", rejection { open(queued) })
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
        val old = committed(ExportSink.RETENTION + 1000)
        val recent = committed(ExportSink.RETENTION - 60_000)
        val active = open()
        assertFalse(stale.exists())
        assertFalse(old.exists())
        assertTrue(File(recent, "PowerLog.zip").exists())
        assertTrue(unrelated.exists())
        assertTrue(staging(active).exists())
        val another = open()
        assertTrue(staging(active).exists())
        now += 60_001
        sink.cleanup()
        assertFalse(recent.exists())
        assertTrue(staging(active).exists())
        val restarted = ExportSink(root, CountingExecutor(), { now }) { it.path }
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
        val queued = ExportSink(root, queue, { now }) { it.path }
        val opened = Reply()
        queued.open("fit", context, opened)
        assertFalse(opened.settled)
        queue.drain()
        val id = opened.get() as String
        val first = Reply()
        val second = Reply()
        queued.write(id, byteArrayOf(1), first)
        queued.write(id, byteArrayOf(2, 3), second)
        assertFalse(first.settled || second.settled)
        assertEquals(0L, staging(id).length())
        queue.runNext()
        assertTrue(first.settled && !second.settled)
        queue.drain()
        assertArrayEquals(byteArrayOf(1, 2, 3), staging(id).readBytes())
        queued.abort(id, Reply())
        queue.drain()
    }

    @Test
    fun aCsvExportIsStagedAndPublishedLikeTheRideExports() {
        val id = call<String> { sink.open("csv", context, it) }
        write(id, "timestamp,elapsedSeconds\n".toByteArray())
        assertEquals("content://test/$id/My-ride.csv", commit(id, "My-ride.csv")["uri"])
        assertEquals("timestamp,elapsedSeconds\n", File(root, "$id/My-ride.csv").readText())
        assertFalse(staging(id).exists())
    }

    @Test
    fun invalidOpenRequestsAreRejectedWithoutTouchingTheDirectory() {
        assertEquals("unsupported", rejection { call<String> { sink.open("gpx", context, it) } })
        assertEquals(
            "unsupported",
            rejection { call<String> { sink.open("zip", context + ("platform" to "ios"), it) } },
        )
        assertEquals("unsupported", rejection { call<String> { sink.open("zip", context - "exportedAt", it) } })
        assertFalse(root.exists())
    }

    @Test
    fun committedExportsAreSharedThroughTheFileProviderExportRoot() {
        forgetFileProviderRoots()
        val application = RuntimeEnvironment.getApplication()
        val authority = "${application.packageName}.powerlog.files"
        shadowOf(application.packageManager)
            .addOrUpdateProvider(
                ProviderInfo().apply {
                    this.authority = authority
                    name = FileProvider::class.java.name
                    packageName = application.packageName
                    metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.power_log_files) }
                }
            )
        val production = ExportSink.create(application, CountingExecutor())
        try {
            val id = open(production)
            call<Any?> { production.write(id, "fit".toByteArray(), it) }
            val uri = call<Map<String, Any?>> { production.commit(id, "PowerLog-ride.fit", it) }["uri"]
            assertEquals("content://$authority/ride-exports/$id/PowerLog-ride.fit", uri)
            assertEquals("fit", File(application.filesDir, "exports/$id/PowerLog-ride.fit").readText())
        } finally {
            File(application.filesDir, "exports").deleteRecursively()
        }
    }
}
