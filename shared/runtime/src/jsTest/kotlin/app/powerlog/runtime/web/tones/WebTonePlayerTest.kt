package app.powerlog.runtime.web.tones

import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.port.AudioRoute
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.ToneOnset
import app.powerlog.domain.recording.SyncBeeps
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class WebTonePlayerTest {
    private class Audio(var contextTime: Double, var performanceMs: Double) {
        val starts = mutableListOf<Double>()
        val sources = mutableListOf<dynamic>()
        var suspended = 0
        val context: dynamic = js("({})")

        init {
            context.currentTime = 0.5
            context.destination = js("({})")
            context.outputLatency = 0.02
            context.resume = { Promise.resolve(Unit) }
            context["suspend"] = {
                suspended++
                Promise.resolve(Unit)
            }
            context.createBuffer = { _: Int, _: Int, _: Int ->
                val buffer: dynamic = js("({})")
                buffer.copyToChannel = { _: dynamic, _: Int -> }
                buffer
            }
            context.createBufferSource = {
                val source: dynamic = js("({})")
                source.connect = { _: dynamic -> }
                source.start = { at: Double -> starts += at }
                source.stop = { -> }
                sources.add(source)
                source
            }
            context.getOutputTimestamp = {
                val stamp: dynamic = js("({})")
                stamp.contextTime = contextTime
                stamp.performanceTime = performanceMs
                stamp
            }
        }

        fun end(index: Int) {
            sources[index].onended(null)
        }
    }

    private class Answers {
        val values = mutableListOf<ToneOnset>()
        val errors = mutableListOf<PortErrorKind>()
        val reply = Reply<ToneOnset>(onOk = { values += it }, onFailed = { error: PortError -> errors += error.kind })
    }

    private fun player(audio: Audio?, activated: Boolean = true) =
        WebTonePlayer(
            { audio?.context?.unsafeCast<AudioContextLike>() },
            { activated },
            FakeClock(monoUs = 1_000_000),
            { block -> Promise.resolve(Unit).then { block() } },
        )

    private suspend fun settle() {
        Promise.resolve(Unit).await()
        Promise.resolve(Unit).await()
    }

    @Test
    fun eachToneStartsAtTheContextTimeItsInstantPresentsAt() = runTest {
        val audio = Audio(contextTime = 1.0, performanceMs = 2_000.0)
        val answers = Answers()
        player(audio).play(SyncBeeps.startTones(6_000_000), answers.reply)
        settle()
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0), audio.starts)
        audio.contextTime = 5.3
        audio.performanceMs = 6_310.0
        audio.end(3)
        assertEquals(listOf(ToneOnset(10_000, AudioRoute.other)), answers.values)
        assertEquals(1, audio.suspended)
    }

    @Test
    fun withoutAnOutputTimestampTheCurrentTimeAndLatencyPlaceTheTones() = runTest {
        val audio = Audio(contextTime = 0.0, performanceMs = 0.0)
        player(audio).play(SyncBeeps.syncTones(3_000_000), Answers().reply)
        settle()
        assertEquals(listOf(2.48), audio.starts.map { (it * 1_000).toLong() / 1_000.0 })
    }

    @Test
    fun anInstantAlreadyPassedStartsAtOnce() = runTest {
        val audio = Audio(contextTime = 1.0, performanceMs = 2_000.0)
        player(audio).play(SyncBeeps.syncTones(1_200_000), Answers().reply)
        settle()
        assertEquals(listOf(0.5), audio.starts)
    }

    @Test
    fun stopAndANewPlayCancelThePendingTones() = runTest {
        val audio = Audio(contextTime = 1.0, performanceMs = 2_000.0)
        val player = player(audio)
        val first = Answers()
        val second = Answers()
        player.play(SyncBeeps.syncTones(4_000_000), first.reply)
        settle()
        player.play(SyncBeeps.syncTones(5_000_000), second.reply)
        settle()
        player.stop()
        settle()
        assertEquals(listOf(PortErrorKind.cancelled), first.errors)
        assertEquals(listOf(PortErrorKind.cancelled), second.errors)
        assertEquals(2, audio.suspended)
    }

    @Test
    fun noAudioOrNoUserActivationRefuses() = runTest {
        val missing = Answers()
        player(null).play(SyncBeeps.syncTones(4_000_000), missing.reply)
        val inactive = Answers()
        player(Audio(1.0, 2_000.0), activated = false).play(SyncBeeps.syncTones(4_000_000), inactive.reply)
        settle()
        assertEquals(listOf(PortErrorKind.unsupported), missing.errors)
        assertEquals(listOf(PortErrorKind.noUserActivation), inactive.errors)
    }
}
