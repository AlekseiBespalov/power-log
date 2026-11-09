package app.powerlog.application.watch

import app.powerlog.domain.core.failure.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FakeWatchCodecTest {
    private val ack = WatchMessage.AckMessage(error = null, acknowledgedSequence = 12)

    @Test
    fun scriptedBytesDecodeToTheirResult() {
        val codec = FakeWatchCodec()
        val mismatch = codec.bytesFor(WatchDecodeResult.VersionMismatch)
        val valid = codec.bytesFor(WatchDecodeResult.Valid(ack))
        assertEquals(WatchDecodeResult.VersionMismatch, codec.decode(mismatch))
        assertEquals(WatchDecodeResult.Valid(ack), codec.decode(valid))
    }

    @Test
    fun unscriptedBytesAreInvalid() {
        assertEquals(WatchDecodeResult.Invalid, FakeWatchCodec().decode("{}".encodeToByteArray()))
    }

    @Test
    fun encodedMessagesAreRecordedInOrderAndDecodeBack() {
        val codec = FakeWatchCodec()
        val failure = WatchMessage.AckMessage(error = FailureCode.watchVersionMismatch, acknowledgedSequence = null)
        val first = codec.encode(ack)
        val second = codec.encode(failure)
        assertEquals(listOf(ack, failure), codec.encoded)
        assertEquals(WatchDecodeResult.Valid(ack), codec.decode(first))
        assertEquals(WatchDecodeResult.Valid(failure), codec.decode(second))
    }

    @Test
    fun theSameMessageEncodedTwiceGetsDistinctBytes() {
        val codec = FakeWatchCodec()
        val first = codec.encode(ack)
        val second = codec.encode(ack)
        assertNotEquals(first.decodeToString(), second.decodeToString())
        assertEquals(listOf(ack, ack), codec.encoded)
    }
}
