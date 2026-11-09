package app.powerlog.runtime.watch

import app.powerlog.application.watch.WatchDecodeResult
import app.powerlog.runtime.contractFixture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsonWatchCodecTest {
    private val codec = JsonWatchCodec()

    private fun message(name: String): ByteArray {
        val bytes = contractFixture("watch/$name.json")
        assertEquals('\n'.code.toByte(), bytes.last(), "$name.json ends with one newline after the message")
        return bytes.copyOf(bytes.size - 1)
    }

    private fun assertReencoded(name: String) {
        val bytes = message(name)
        val decoded = assertIs<WatchDecodeResult.Valid>(codec.decode(bytes), name)
        val encoded = codec.encode(decoded.message)
        assertEquals(bytes.decodeToString(), encoded.decodeToString(), name)
        assertContentEquals(bytes, encoded, name)
    }

    @Test fun startRequestIsReencodedByteForByte() = assertReencoded("start-request")

    @Test fun cancelStartIsReencodedByteForByte() = assertReencoded("cancel-start")

    @Test fun commandIsReencodedByteForByte() = assertReencoded("command")

    @Test fun healthSamplesAreReencodedByteForByte() = assertReencoded("health-samples")

    @Test fun healthOutcomeIsReencodedByteForByte() = assertReencoded("health-outcome")

    @Test fun startIsReencodedByteForByte() = assertReencoded("start")

    @Test fun aRunningRideIsReencodedByteForByte() = assertReencoded("ride-running")

    @Test fun aTerminalRideIsReencodedByteForByte() = assertReencoded("ride-terminal")

    @Test fun anAckIsReencodedByteForByte() = assertReencoded("ack")

    @Test fun anAckWithAnErrorIsReencodedByteForByte() = assertReencoded("ack-error")

    @Test
    fun anotherVersionIsAVersionMismatch() {
        assertEquals(WatchDecodeResult.VersionMismatch, codec.decode(message("other-version")))
    }

    @Test
    fun anUndecodableMessageIsInvalid() {
        assertEquals(WatchDecodeResult.Invalid, codec.decode(message("undecodable")))
    }
}
