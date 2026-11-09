package app.powerlog.application.fakes

import app.powerlog.application.port.ScheduleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeBackgroundSchedulerTest {
    private val scheduler = FakeBackgroundScheduler()
    private val ride = "00000000-0000-4000-8000-000000000001"

    @Test
    fun keepLeavesPendingWorkUnchanged() {
        scheduler.schedule(ride, 1_000, ScheduleKind.replace)
        scheduler.schedule(ride, 2_000, ScheduleKind.keep)

        assertEquals(listOf(FakeBackgroundScheduler.Work(ride, 1_000, ScheduleKind.replace)), scheduler.pending(ride))
    }

    @Test
    fun replaceSupersedesAndAfterCurrentQueuesBehind() {
        scheduler.schedule(ride, 1_000, ScheduleKind.keep)
        scheduler.schedule(ride, null, ScheduleKind.replace)
        scheduler.schedule(ride, 3_000, ScheduleKind.afterCurrent)

        assertEquals(FakeBackgroundScheduler.Work(ride, null, ScheduleKind.replace), scheduler.run(ride))
        assertEquals(FakeBackgroundScheduler.Work(ride, 3_000, ScheduleKind.afterCurrent), scheduler.run(ride))
        assertNull(scheduler.run(ride))
    }

    @Test
    fun cancelDropsTheRidesWork() {
        scheduler.schedule(ride, 1_000, ScheduleKind.keep)

        scheduler.cancel(ride)

        assertEquals(emptyList(), scheduler.pending(ride))
        assertEquals(listOf(ride), scheduler.cancelled)
    }
}
