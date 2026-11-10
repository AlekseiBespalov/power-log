package app.powerlog.application.port

import app.powerlog.domain.recording.Tone

public interface TonePlayer {
    public fun play(tones: List<Tone>, reply: Reply<ToneOnset>)

    public fun stop()
}

public data class ToneOnset(public val lateUs: Long, public val route: AudioRoute)

public enum class AudioRoute {
    speaker,
    wired,
    bluetooth,
    other,
}
