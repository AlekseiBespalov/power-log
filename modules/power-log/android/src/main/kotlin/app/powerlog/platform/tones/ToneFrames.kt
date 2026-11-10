package app.powerlog.platform.tones

import app.powerlog.domain.recording.Tone

internal class ToneFrames(private val tones: List<Tone>, private val rate: Int) {
    private val starts = LongArray(tones.size)
    private var anchorFrame = 0L
    private var anchorMonoUs = 0L

    var mapped = false
        private set

    var written = 0L
        private set

    val syncStart: Long
        get() = starts.last()

    val end: Long
        get() = starts.last() + tones.last().samples.size

    fun map(frame: Long, presentedMonoUs: Long) {
        anchorFrame = frame
        anchorMonoUs = presentedMonoUs
        var free = written
        for ((index, tone) in tones.withIndex()) {
            starts[index] = maxOf(frameAt(tone.atMonoUs), free)
            free = starts[index] + tone.samples.size
        }
        mapped = true
    }

    fun presentedMonoUs(frame: Long): Long = anchorMonoUs + (frame - anchorFrame) * 1_000_000 / rate

    fun fill(out: FloatArray, count: Int): Int {
        val wanted =
            if (mapped) minOf(count.toLong(), maxOf(0L, end + rate / TAIL_PER_SECOND - written)).toInt() else count
        out.fill(0f, 0, wanted)
        if (!mapped) return wanted
        for ((index, tone) in tones.withIndex()) {
            val first = maxOf(starts[index], written)
            val last = minOf(starts[index] + tone.samples.size, written + wanted)
            for (frame in first until last) out[(frame - written).toInt()] =
                tone.samples[(frame - starts[index]).toInt()]
        }
        return wanted
    }

    fun wrote(count: Int) {
        written += count
    }

    private fun frameAt(monoUs: Long): Long = anchorFrame + (monoUs - anchorMonoUs) * rate / 1_000_000

    private companion object {
        const val TAIL_PER_SECOND = 20
    }
}
