package app.powerlog.application.fakes

import app.powerlog.application.port.AudioRoute
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.ToneOnset
import app.powerlog.domain.recording.SyncBeeps
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeTonePlayerTest {
    private val queue = FakePortQueue()

    @Test
    fun aPlayAnswersItsOnsetAndANewPlayOrStopCancelsTheOneBefore() {
        val player = FakeTonePlayer(queue::post)
        val played = ReplyProbe<ToneOnset>()
        player.play(SyncBeeps.syncTones(1_000_000), played.reply)
        queue.drain()
        assertEquals(listOf(ToneOnset(0, AudioRoute.speaker)), played.values)

        player.plays.script = { Answer.Held }
        val replaced = ReplyProbe<ToneOnset>()
        val stopped = ReplyProbe<ToneOnset>()
        player.play(SyncBeeps.startTones(5_000_000), replaced.reply)
        player.play(SyncBeeps.syncTones(9_000_000), stopped.reply)
        player.stop()
        queue.drain()
        assertEquals(listOf(PortErrorKind.cancelled), replaced.kinds)
        assertEquals(listOf(PortErrorKind.cancelled), stopped.kinds)
        assertEquals(
            listOf(listOf(1_000_000L), listOf(2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L), listOf(9_000_000L)),
            player.plays.received.map { tones -> tones.map { it.atMonoUs } },
        )
        assertEquals(1, player.stops)
    }

    @Test
    fun aFailedPlayAnswersItsError() {
        val player = FakeTonePlayer(queue::post)
        player.plays.script = { Answer.Failed(PortError(PortErrorKind.busy)) }
        val failed = ReplyProbe<ToneOnset>()
        player.play(SyncBeeps.syncTones(1_000_000), failed.reply)
        queue.drain()
        assertEquals(listOf(PortErrorKind.busy), failed.kinds)
    }
}
