package app.powerlog.runtime.web.tones

import app.powerlog.application.port.AudioRoute
import app.powerlog.application.port.Clock
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.ToneOnset
import app.powerlog.application.port.TonePlayer
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.recording.Tone
import kotlin.js.Promise
import kotlin.math.roundToLong
import org.khronos.webgl.Float32Array

internal class WebTonePlayer(
    private val open: () -> AudioContextLike?,
    private val activated: () -> Boolean,
    private val clock: Clock,
    private val post: (() -> Unit) -> Unit,
) : TonePlayer {
    private var context: AudioContextLike? = null
    private var playing: Playing? = null

    private class Playing(val reply: Reply<ToneOnset>) {
        val sources = mutableListOf<BufferSourceLike>()
    }

    private class Timeline(val contextTime: Double, val performanceMs: Double) {
        fun contextTime(monoUs: Long): Double = contextTime + (monoUs / 1_000.0 - performanceMs) / 1_000.0

        fun presentedUs(contextTime: Double): Long =
            ((performanceMs + (contextTime - this.contextTime) * 1_000.0) * 1_000.0).roundToLong()
    }

    override fun play(tones: List<Tone>, reply: Reply<ToneOnset>) {
        end(playing, PortError(PortErrorKind.cancelled))
        val sync = tones.lastOrNull()
        val audio = context ?: open()?.also { context = it }
        if (sync == null || audio == null) return post { reply.failed(PortError(PortErrorKind.unsupported)) }
        if (!activated()) return post { reply.failed(PortError(PortErrorKind.noUserActivation)) }
        val current = Playing(reply)
        playing = current
        audio
            .resume()
            .then(
                onFulfilled = { if (playing === current) schedule(audio, current, tones, sync) },
                onRejected = { error -> end(current, failure(error)) },
            )
    }

    override fun stop() {
        end(playing, PortError(PortErrorKind.cancelled))
    }

    private fun schedule(audio: AudioContextLike, current: Playing, tones: List<Tone>, sync: Tone) {
        val timeline = timeline(audio)
        for (tone in tones) {
            val buffer = audio.createBuffer(1, tone.samples.size, tone.sampleRateHz)
            buffer.copyToChannel(tone.samples.unsafeCast<Float32Array>(), 0)
            val source = audio.createBufferSource()
            source.buffer = buffer
            source.connect(audio.destination)
            val startsAt = maxOf(timeline.contextTime(tone.atMonoUs), audio.currentTime)
            if (tone === sync) source.onended = { _ -> played(audio, current, sync, startsAt, timeline) }
            source.start(startsAt)
            current.sources += source
        }
    }

    private fun played(audio: AudioContextLike, current: Playing, sync: Tone, startedAt: Double, planned: Timeline) {
        if (playing !== current) return
        val presentedUs = (outputTimeline(audio) ?: planned).presentedUs(startedAt)
        val onset = ToneOnset(presentedUs - sync.atMonoUs, AudioRoute.other)
        end(current, null)
        current.reply.ok(onset)
    }

    private fun timeline(audio: AudioContextLike): Timeline =
        outputTimeline(audio)
            ?: Timeline(
                audio.currentTime,
                clock.monoUs() / 1_000.0 + ((audio.outputLatency ?: audio.baseLatency) ?: 0.0) * 1_000.0,
            )

    private fun outputTimeline(audio: AudioContextLike): Timeline? {
        if (jsTypeOf(audio.asDynamic().getOutputTimestamp) != "function") return null
        val stamp = audio.getOutputTimestamp()
        val performanceMs = stamp.performanceTime ?: return null
        val contextTime = stamp.contextTime ?: return null
        return if (performanceMs > 0) Timeline(contextTime, performanceMs) else null
    }

    private fun end(current: Playing?, error: PortError?) {
        if (current == null || playing !== current) return
        playing = null
        for (source in current.sources) {
            source.onended = null
            source.stop()
        }
        context?.pause()
        if (error != null) post { current.reply.failed(error) }
    }

    private fun failure(error: dynamic): PortError {
        val name: dynamic = error?.name
        val code = if (jsTypeOf(name) == "string") PlatformCode("DOMException", name.unsafeCast<String>()) else null
        return PortError(PortErrorKind.failed, code)
    }

    companion object {
        fun browser(window: dynamic): AudioContextLike? {
            val type: dynamic = window.AudioContext ?: window.webkitAudioContext
            if (type == null) return null
            val options: dynamic = js("({})")
            options.latencyHint = "interactive"
            return js("Reflect").construct(type, arrayOf(options)).unsafeCast<AudioContextLike>()
        }
    }
}

internal external interface AudioContextLike {
    val currentTime: Double
    val destination: Any
    val outputLatency: Double?
    val baseLatency: Double?

    fun resume(): Promise<Unit>

    @JsName("suspend") fun pause(): Promise<Unit>

    fun createBuffer(channels: Int, length: Int, sampleRate: Int): AudioBufferLike

    fun createBufferSource(): BufferSourceLike

    fun getOutputTimestamp(): OutputTimestampLike
}

internal external interface AudioBufferLike {
    fun copyToChannel(source: Float32Array, channel: Int)
}

internal external interface BufferSourceLike {
    var buffer: AudioBufferLike?
    var onended: ((Any?) -> Unit)?

    fun connect(destination: Any)

    fun start(at: Double)

    fun stop()
}

internal external interface OutputTimestampLike {
    val contextTime: Double?
    val performanceTime: Double?
}
