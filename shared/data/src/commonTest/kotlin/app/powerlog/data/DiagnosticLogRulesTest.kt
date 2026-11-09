package app.powerlog.data

import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DiagnosticLogRulesTest {
    private class MemoryFiles : LogFiles {
        val files = mutableMapOf<String, ByteArray>()
        var tearNextAppend = false

        override fun size(name: String): Long = files[name]?.size?.toLong() ?: 0L

        override fun append(name: String, bytes: ByteArray) {
            if (tearNextAppend) {
                tearNextAppend = false
                files[name] = (files[name] ?: ByteArray(0)) + bytes.copyOf(bytes.size / 2)
                throw IllegalStateException("torn")
            }
            files[name] = (files[name] ?: ByteArray(0)) + bytes
        }

        override fun read(name: String, offset: Long, length: Int): ByteArray =
            files[name]?.copyOfRange(offset.toInt(), offset.toInt() + length) ?: ByteArray(0)

        override fun truncate(name: String, length: Long) {
            files[name] = files.getValue(name).copyOf(length.toInt())
        }

        override fun replace(source: String, target: String) {
            files[target] = files.remove(source) ?: ByteArray(0)
        }
    }

    private fun line(vararg fields: LogField) = LogLine(LogLevel.info, 1, 2, "launch", fields.toList())

    private fun padded(padding: Int) = line(LogField("pad", LogValue.Text("x".repeat(padding))))

    @Test
    fun aLineOverFourKibibytesWithItsNewlineIsNotWrittenAndIsCounted() = runTest {
        val files = MemoryFiles()
        val log = DiagnosticLogFiles(files, StandardTestDispatcher(testScheduler))
        val header = LogLines.encode(padded(0)).encodeToByteArray().size + 1
        log.append(padded(StorePolicy.logLineBytes - header))
        log.append(padded(StorePolicy.logLineBytes - header + 1))
        assertEquals(StorePolicy.logLineBytes, files.files.getValue(DiagnosticLogFiles.CURRENT).size)
        assertEquals(1, log.failures)
    }

    @Test
    fun aNonFiniteDecimalIsOmittedWithItsField() {
        val text =
            LogLines.encode(
                line(
                    LogField("nan", LogValue.Decimal(Double.NaN)),
                    LogField("ratio", LogValue.Decimal(0.5)),
                    LogField(
                        "nested",
                        LogValue.Fields(
                            listOf(
                                LogField("infinite", LogValue.Decimal(Double.NEGATIVE_INFINITY)),
                                LogField("kept", LogValue.Flag(true)),
                            )
                        ),
                    ),
                )
            )
        assertEquals(
            """{"v":2,"level":"info","utcUs":1,"monoUs":2,"event":"launch","ratio":0.5,"nested":{"kept":true}}""",
            text,
        )
    }

    @Test
    fun aTornLastLineIsCutWhenTheLogIsMadeBeforeAnyCall() = runTest {
        val files = MemoryFiles()
        files.append(DiagnosticLogFiles.CURRENT, "{\"v\":2}\n{\"v\":2,\"lev".encodeToByteArray())
        DiagnosticLogFiles(files, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()
        assertEquals("{\"v\":2}\n", files.files.getValue(DiagnosticLogFiles.CURRENT).decodeToString())
    }

    private suspend fun DiagnosticLogFiles.readAll(maxBytes: Int): String {
        val text = StringBuilder()
        var after = 0L
        while (true) {
            val chunk = (read(after, maxBytes) as StoreResult.Ok<LogChunk>).value
            text.append(chunk.bytes.decodeToString())
            after = chunk.next ?: return text.toString()
        }
    }

    @Test
    fun aReadAfterATornAppendSeesOnlyWholeLines() = runTest {
        val files = MemoryFiles()
        val log = DiagnosticLogFiles(files, StandardTestDispatcher(testScheduler))
        log.append(padded(1))
        files.tearNextAppend = true
        log.append(padded(2))
        val whole = LogLines.encode(padded(1)) + "\n"
        assertEquals(whole, log.readAll(1_000))
        assertEquals(whole, files.files.getValue(DiagnosticLogFiles.CURRENT).decodeToString())
    }

    @Test
    fun offsetsRunThroughThePreviousFileThenTheCurrentOneAfterARotation() = runTest {
        val files = MemoryFiles()
        val line = LogLines.encode(padded(10)) + "\n"
        val log = DiagnosticLogFiles(files, StandardTestDispatcher(testScheduler), rotateBytes = line.length * 3)
        repeat(5) { log.append(padded(10)) }
        val previous = files.files.getValue(DiagnosticLogFiles.PREVIOUS).decodeToString()
        val current = files.files.getValue(DiagnosticLogFiles.CURRENT).decodeToString()
        assertEquals(line.repeat(3), previous)
        assertEquals(line.repeat(2), current)
        assertEquals(previous + current, log.readAll(line.length * 2))
        val fromCurrent = (log.read(previous.length.toLong(), 10_000) as StoreResult.Ok<LogChunk>).value
        assertEquals(current, fromCurrent.bytes.decodeToString())
        assertNull(fromCurrent.next)
    }

    @Test
    fun aLineLongerThanMaxBytesComesCutAtMaxBytes() = runTest {
        val files = MemoryFiles()
        val log = DiagnosticLogFiles(files, StandardTestDispatcher(testScheduler))
        log.append(padded(100))
        val chunk = (log.read(0, 40) as StoreResult.Ok<LogChunk>).value
        assertEquals(40, chunk.bytes.size)
        assertEquals(40L, chunk.next)
        assertEquals(LogLines.encode(padded(100)) + "\n", log.readAll(40))
    }
}
