package app.powerlog.application.fakes

import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeFilePickerTest {
    private val queue = FakePortQueue()
    private val picker = FakeFilePicker(queue::post)

    @Test
    fun aPickedFileReadsInChunksToNullAndCloses() {
        val file = FakePickedFile("ride.csv", "a,b\n1,2\n".encodeToByteArray(), queue::post)
        picker.picks.script = { Answer.Ok(file) }
        val pick = ReplyProbe<PickedFile>()

        picker.pickCsv(pick.reply)
        queue.drain()
        val picked = pick.values.single()
        val chunks = List(3) { ReplyProbe<ByteArray?>().also { picked.read(maxBytes = 5, reply = it.reply) } }
        queue.drain()
        picked.close()

        assertEquals("ride.csv", picked.name)
        assertEquals(8, picked.sizeBytes)
        assertContentEquals("a,b\n1".encodeToByteArray(), chunks[0].values.single())
        assertContentEquals(",2\n".encodeToByteArray(), chunks[1].values.single())
        assertNull(chunks[2].values.single())
        assertTrue(file.closed)
    }

    @Test
    fun cancelRevokedAndNoUiAreTheirKinds() {
        val cancelled = ReplyProbe<PickedFile>()
        picker.pickCsv(cancelled.reply)
        picker.picks.script = { Answer.Failed(PortError(PortErrorKind.uiUnavailable)) }
        val noUi = ReplyProbe<PickedFile>()
        picker.pickCsv(noUi.reply)
        val file = FakePickedFile("ride.csv", byteArrayOf(1), queue::post).apply { revoked = true }
        val revoked = ReplyProbe<ByteArray?>()
        file.read(maxBytes = 1, reply = revoked.reply)
        queue.drain()

        assertEquals(listOf(PortErrorKind.cancelled), cancelled.kinds)
        assertEquals(listOf(PortErrorKind.uiUnavailable), noUi.kinds)
        assertEquals(listOf(PortErrorKind.unreadable), revoked.kinds)
    }
}
