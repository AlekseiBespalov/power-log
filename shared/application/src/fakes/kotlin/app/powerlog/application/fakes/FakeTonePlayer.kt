package app.powerlog.application.fakes

import app.powerlog.application.port.AudioRoute
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.application.port.ToneOnset
import app.powerlog.application.port.TonePlayer
import app.powerlog.domain.recording.Tone

class FakeTonePlayer(private val post: (() -> Unit) -> Unit) : TonePlayer {
    private var current: Reply<ToneOnset>? = null

    val plays: FakeRequests<List<Tone>, ToneOnset> = FakeRequests(post) { Answer.Ok(ToneOnset(0, AudioRoute.speaker)) }

    var stops: Int = 0
        private set

    override fun play(tones: List<Tone>, reply: Reply<ToneOnset>) {
        cancelCurrent()
        val playing = Reply<ToneOnset>(onOk = reply::ok, onFailed = reply::failed)
        current = playing
        plays.take(tones, playing)
    }

    override fun stop() {
        stops++
        cancelCurrent()
    }

    private fun cancelCurrent() {
        val playing = current ?: return
        current = null
        post { playing.failed(PortError(PortErrorKind.cancelled)) }
    }
}
