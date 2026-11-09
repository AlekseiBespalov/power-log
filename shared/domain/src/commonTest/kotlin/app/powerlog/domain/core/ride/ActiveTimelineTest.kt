package app.powerlog.domain.core.ride

import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.time.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveTimelineTest {
    private fun event(kind: RideEventKind, elapsedUs: Long): RideEvent =
        RideEvent(EventId("e-$kind-$elapsedUs"), kind, 1_000_000_000L + elapsedUs, elapsedUs)

    @Test
    fun theFirstIntervalOpensAtTheStartAndPauseAndResumeSplitIt() {
        val timeline =
            ActiveTimeline(
                100,
                listOf(
                    event(RideEventKind.start, 0),
                    event(RideEventKind.pause, 30),
                    event(RideEventKind.resume, 50),
                    event(RideEventKind.stop, 100),
                ),
            )
        assertEquals(listOf(TimeRange(0, 30), TimeRange(50, 100)), timeline.intervals)
        assertEquals(80, timeline.timerUs)
        assertEquals(30, timeline.timerAt(40))
        assertEquals(40, timeline.timerAt(60))
        assertEquals(1, timeline.intervalAt(0))
        assertNull(timeline.intervalAt(30))
        assertNull(timeline.intervalAt(40))
        assertEquals(2, timeline.intervalAt(50))
        assertEquals(2, timeline.intervalAt(100))
        assertNull(timeline.intervalAt(101))
    }

    @Test
    fun aRideWithoutLapPressesHasOneLapAndRepeatedLapInstantsCollapse() {
        assertEquals(listOf(LapSpan(0, TimeRange(0, 100), 100)), ActiveTimeline(100, emptyList()).laps)
        val laps =
            ActiveTimeline(
                    100,
                    listOf(
                        event(RideEventKind.lap, 0),
                        event(RideEventKind.lap, 40),
                        event(RideEventKind.lap, 40),
                        event(RideEventKind.pause, 60),
                        event(RideEventKind.resume, 70),
                        event(RideEventKind.lap, 100),
                    ),
                )
                .laps
        assertEquals(
            listOf(LapSpan(0, TimeRange(0, 40), 40), LapSpan(1, TimeRange(40, 100), 50)),
            laps,
        )
    }

    @Test
    fun lapsCountFromZeroInElapsedOrder() {
        val laps =
            ActiveTimeline(
                    90,
                    listOf(event(RideEventKind.lap, 30), event(RideEventKind.lap, 60), event(RideEventKind.stop, 90)),
                )
                .laps
        assertEquals(listOf(0, 1, 2), laps.map { it.index })
        assertEquals(listOf(TimeRange(0, 30), TimeRange(30, 60), TimeRange(60, 90)), laps.map { it.range })
    }

    @Test
    fun aRidePausedAtItsEndEndsInactiveAndEventsPastTheEndAreIgnored() {
        val timeline = ActiveTimeline(60, listOf(event(RideEventKind.pause, 60), event(RideEventKind.resume, 80)))
        assertEquals(listOf(TimeRange(0, 60)), timeline.intervals)
        assertNull(timeline.intervalAt(60))
        val interrupted = ActiveTimeline(45, listOf(event(RideEventKind.pause, 10), event(RideEventKind.resume, 20)))
        assertEquals(listOf(TimeRange(0, 10), TimeRange(20, 45)), interrupted.intervals)
        assertEquals(35, interrupted.timerUs)
        assertEquals(2, interrupted.intervalAt(45))
    }
}
