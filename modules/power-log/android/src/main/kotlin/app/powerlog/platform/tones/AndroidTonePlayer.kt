package app.powerlog.platform.tones

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.powerlog.application.port.AudioRoute
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.ToneOnset
import app.powerlog.application.port.TonePlayer
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.recording.Tone

class AndroidTonePlayer(context: Context, private val ride: Handler) : TonePlayer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val worker: Handler by lazy { Handler(HandlerThread("PowerLogTones").apply { start() }.looper) }
    private var playing: Playing? = null

    override fun play(tones: List<Tone>, reply: Reply<ToneOnset>) {
        worker.post { start(tones, reply) }
    }

    override fun stop() {
        worker.post { playing?.end(PortError(PortErrorKind.cancelled)) }
    }

    private fun start(tones: List<Tone>, reply: Reply<ToneOnset>) {
        playing?.end(PortError(PortErrorKind.cancelled))
        val sync = tones.lastOrNull()
        if (sync == null) {
            ride.post { reply.failed(failure(PortErrorKind.unsupported)) }
            return
        }
        val rate = sync.sampleRateHz
        val current = Playing(ToneFrames(tones, rate), sync, reply)
        playing = current
        current.begin(rate)
    }

    private inner class Playing(val frames: ToneFrames, val sync: Tone, val reply: Reply<ToneOnset>) {
        private val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        private val focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(
                    { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                            end(failure(PortErrorKind.busy))
                    },
                    worker,
                )
                .build()
        private val stamp = AudioTimestamp()
        private val offsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        private var track: AudioTrack? = null
        private var chunk = FloatArray(0)
        private var answered = false
        private val startedMs = SystemClock.uptimeMillis()

        fun begin(rate: Int) {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                return end(failure(PortErrorKind.busy))
            val minimum =
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val started =
                try {
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(maxOf(minimum, rate / BUFFERS_PER_SECOND * Float.SIZE_BYTES))
                        .build()
                        .also { it.play() }
                } catch (error: RuntimeException) {
                    return end(failure(PortErrorKind.failed, error))
                }
            track = started
            chunk = FloatArray(rate / AHEAD_PER_SECOND)
            pump()
        }

        fun end(error: PortError?) {
            if (playing !== this) return
            playing = null
            worker.removeCallbacksAndMessages(this)
            track?.release()
            track = null
            audio.abandonAudioFocusRequest(focus)
            if (error != null) ride.post { reply.failed(error) }
            else if (!answered) {
                val onset = ToneOnset(frames.presentedMonoUs(frames.syncStart) - sync.atMonoUs, AudioRoute.other)
                ride.post { reply.ok(onset) }
            }
        }

        private fun pump() {
            val track = track ?: return
            val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (!frames.mapped && track.getTimestamp(stamp) && stamp.framePosition > 0)
                frames.map(stamp.framePosition, (stamp.nanoTime + offsetNs) / 1_000)
            else if (!frames.mapped && SystemClock.uptimeMillis() - startedMs > MAP_WAIT_MS)
                frames.map(head, SystemClock.elapsedRealtimeNanos() / 1_000)
            val room = (chunk.size - (frames.written - head)).toInt()
            if (room > 0) {
                val count = frames.fill(chunk, room)
                if (count > 0) {
                    val wrote = track.write(chunk, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                    if (wrote < 0) return end(failure(PortErrorKind.busy, code = "write $wrote"))
                    frames.wrote(wrote)
                }
            }
            if (frames.mapped && !answered && head > frames.syncStart && track.getTimestamp(stamp)) {
                answered = true
                val presented =
                    (stamp.nanoTime + offsetNs) / 1_000 -
                        (stamp.framePosition - frames.syncStart) * 1_000_000 / sync.sampleRateHz
                val onset = ToneOnset(presented - sync.atMonoUs, route(track))
                ride.post { reply.ok(onset) }
            }
            if (frames.mapped && head >= frames.end) return end(null)
            if (SystemClock.elapsedRealtimeNanos() / 1_000 > sync.atMonoUs + STALL_US)
                return end(if (answered) null else failure(PortErrorKind.busy, code = "stalled"))
            worker.postAtTime(::pump, this, SystemClock.uptimeMillis() + PUMP_MS)
        }
    }

    @SuppressLint("InlinedApi")
    private fun route(track: AudioTrack): AudioRoute =
        when (track.routedDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.speaker
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioRoute.wired
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioRoute.bluetooth
            else -> AudioRoute.other
        }

    private fun failure(kind: PortErrorKind, error: Throwable? = null, code: String? = null): PortError =
        PortError(kind, (code ?: error?.javaClass?.simpleName)?.let { PlatformCode("AudioTrack", it) })

    private companion object {
        const val BUFFERS_PER_SECOND = 5
        const val AHEAD_PER_SECOND = 16
        const val PUMP_MS = 10L
        const val MAP_WAIT_MS = 400L
        const val STALL_US = 3_000_000L
    }
}
