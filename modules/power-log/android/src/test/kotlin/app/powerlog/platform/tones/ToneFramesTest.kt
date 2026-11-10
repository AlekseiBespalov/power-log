package app.powerlog.platform.tones

import app.powerlog.domain.recording.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneFramesTest {
    private val pip = Tone(100_000, floatArrayOf(1f, 1f), 1_000)
    private val sync = Tone(200_000, floatArrayOf(2f, 2f, 2f), 1_000)

    private fun ToneFrames.drain(chunk: Int): List<Float> {
        val out = FloatArray(chunk)
        val all = mutableListOf<Float>()
        while (true) {
            val count = fill(out, chunk)
            if (count == 0) return all
            all += out.take(count)
            wrote(count)
        }
    }

    @Test
    fun silenceRunsUntilTheMappingThenEachToneLandsOnItsFrame() {
        val frames = ToneFrames(listOf(pip, sync), 1_000)
        val out = FloatArray(5)
        assertEquals(5, frames.fill(out, 5))
        frames.wrote(5)
        assertFalse(frames.mapped)
        frames.map(frame = 3, presentedMonoUs = 50_000)
        assertTrue(frames.mapped)
        assertEquals(153L, frames.syncStart)
        assertEquals(156L, frames.end)
        assertEquals(200_000L, frames.presentedMonoUs(153))
        val rest = frames.drain(chunk = 7)
        val played = List(5) { 0f } + rest
        assertEquals(206, played.size)
        assertEquals(listOf(1f, 1f), played.subList(53, 55))
        assertEquals(listOf(2f, 2f, 2f), played.subList(153, 156))
        assertEquals(5, played.count { it != 0f })
    }

    @Test
    fun aToneWhoseFrameIsAlreadyWrittenPlaysAtTheNextFreeFrame() {
        val frames = ToneFrames(listOf(pip, sync), 1_000)
        val out = FloatArray(80)
        frames.wrote(frames.fill(out, 80))
        frames.map(frame = 3, presentedMonoUs = 50_000)
        assertEquals(153L, frames.syncStart)
        val played = List(80) { 0f } + frames.drain(chunk = 10)
        assertEquals(listOf(1f, 1f), played.subList(80, 82))
        assertEquals(listOf(2f, 2f, 2f), played.subList(153, 156))
    }

    @Test
    fun overlappingLateTonesFollowEachOther() {
        val frames = ToneFrames(listOf(pip, sync), 1_000)
        val out = FloatArray(160)
        frames.wrote(frames.fill(out, 160))
        frames.map(frame = 3, presentedMonoUs = 50_000)
        assertEquals(162L, frames.syncStart)
        val played = List(160) { 0f } + frames.drain(chunk = 10)
        assertEquals(listOf(1f, 1f, 2f, 2f, 2f), played.subList(160, 165))
    }
}
