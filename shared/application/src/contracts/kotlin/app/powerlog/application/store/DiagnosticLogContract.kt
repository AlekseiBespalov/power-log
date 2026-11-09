package app.powerlog.application.store

import app.powerlog.application.goldens.Fields
import app.powerlog.application.goldens.parseJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

abstract class DiagnosticLogContract {
    protected abstract val harness: DiagnosticLogHarness

    @Test
    fun appendsPastTheRotationSizeKeepTheCurrentAndThePreviousFile() =
        runTest(timeout = 5.minutes) {
            val under = harness.create(this)
            val count = 2 * harness.logRotateBytes / PADDING + 100
            repeat(count) { under.log.append(line(it, PADDING)) }
            val files = under.files()
            assertEquals(setOf(PREVIOUS, CURRENT), files.keys)
            for ((name, bytes) in files) assertTrue(
                bytes.size <= harness.logRotateBytes,
                "$name holds ${bytes.size} bytes",
            )
            val numbers = numbers(files.getValue(PREVIOUS) + files.getValue(CURRENT))
            assertTrue(numbers.first() > 0, "the oldest lines rotate out")
            assertEquals((numbers.first() until count).toList(), numbers)
        }

    @Test
    fun aTornLastLineIsCutBackToItsLastNewlineAtOpen() = runTest {
        val under = harness.create(this)
        under.log.append(line(1))
        under.log.append(line(2))
        val whole = under.files().getValue(CURRENT)
        under.writeFile(CURRENT, whole + "{\"v\":2,\"lev".encodeToByteArray())
        val reopened = under.reopen()
        reopened.log.append(line(3))
        assertEquals(listOf(1, 2, 3), numbers(reopened.files().getValue(CURRENT)))
    }

    @Test
    fun readAnswersWholeLinesOfThePreviousThenTheCurrentFile() = runTest {
        val under = harness.create(this)
        val previous = text(1..3)
        val current = text(4..6)
        under.writeFile(PREVIOUS, previous)
        under.writeFile(CURRENT, current)
        val log = under.reopen().log
        val maxBytes = (previous + current).decodeToString().lines().maxOf { it.length + 1 } * 3 / 2
        val read = mutableListOf<Byte>()
        var after = 0L
        var chunks = 0
        while (true) {
            val chunk = log.read(after, maxBytes).ok()
            assertTrue(chunk.bytes.size <= maxBytes, "a chunk holds ${chunk.bytes.size} bytes for $maxBytes")
            assertTrue(chunk.bytes.isEmpty() || chunk.bytes.last() == '\n'.code.toByte(), "a chunk ends at a newline")
            assertTrue(++chunks <= 100, "chunks keep coming")
            read += chunk.bytes.toList()
            after = chunk.next ?: break
        }
        assertContentEquals(previous + current, read.toByteArray())
    }

    @Test
    fun eachLineIsOneJsonObjectWithItsHeaderThenItsFieldsInOrder() = runTest {
        val under = harness.create(this)
        val platform =
            LogValue.Fields(
                listOf(LogField("domain", LogValue.Text("SQLite")), LogField("code", LogValue.Text("SQLITE_FULL")))
            )
        under.log.append(
            LogLine(
                level = LogLevel.warning,
                utcUs = 1_000,
                monoUs = 2_000,
                event = "storageFailed",
                fields =
                    listOf(
                        LogField("stage", LogValue.Text("cache, \"quoted\"\nnext")),
                        LogField("count", LogValue.Integer(3)),
                        LogField("ratio", LogValue.Decimal(0.5)),
                        LogField("first", LogValue.Flag(true)),
                        LogField("frames", LogValue.Texts(listOf("a", "b"))),
                        LogField("platform", platform),
                    ),
            )
        )
        val text = under.files().getValue(CURRENT).decodeToString()
        assertEquals(1, text.count { it == '\n' })
        assertTrue(text.endsWith("\n"))
        val json = parseJson(text.dropLast(1), CURRENT) as JsonObject
        assertEquals(
            listOf("v", "level", "utcUs", "monoUs", "event", "stage", "count", "ratio", "first", "frames", "platform"),
            json.keys.toList(),
        )
        val fields = Fields(CURRENT, json)
        assertEquals(2L, fields.long("v"))
        assertEquals("warning", fields.text("level"))
        assertEquals(1_000L, fields.long("utcUs"))
        assertEquals(2_000L, fields.long("monoUs"))
        assertEquals("storageFailed", fields.text("event"))
        assertEquals("cache, \"quoted\"\nnext", fields.text("stage"))
        assertEquals(3L, fields.long("count"))
        assertEquals(0.5, fields.double("ratio"))
        assertEquals(true, fields.bool("first"))
        assertEquals(listOf("a", "b"), fields.texts("frames"))
        assertEquals("SQLITE_FULL", fields.obj("platform").text("code"))
    }

    private fun line(n: Int, padding: Int = 0) =
        LogLine(
            level = LogLevel.info,
            utcUs = 1_000_000L + n,
            monoUs = n.toLong(),
            event = "launch",
            fields =
                listOf(
                    LogField("n", LogValue.Integer(n.toLong())),
                    LogField("pad", LogValue.Text("x".repeat(padding))),
                ),
        )

    private fun text(numbers: IntRange): ByteArray =
        numbers
            .joinToString("") { """{"v":2,"level":"info","utcUs":$it,"monoUs":$it,"event":"launch","n":$it}""" + "\n" }
            .encodeToByteArray()

    private fun numbers(bytes: ByteArray): List<Int> =
        bytes
            .decodeToString()
            .split('\n')
            .filter { it.isNotEmpty() }
            .map { Fields(CURRENT, parseJson(it, CURRENT)).int("n") }

    private companion object {
        const val CURRENT = "events.jsonl"
        const val PREVIOUS = "events.previous.jsonl"
        const val PADDING = 1_000
    }
}
