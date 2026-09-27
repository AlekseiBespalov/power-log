package app.powerlog.bridge

import org.junit.Assert.*
import org.junit.Test

class RecordingServiceTest {
    @Test
    fun notificationEqualityTracksIdentityPhaseAndTimerAnchors() {
        val running = RideNotificationState("ride", "running", 1000L, 2.5)
        assertEquals(running, running.copy())
        assertNotEquals(running, running.copy(id = "another"))
        assertNotEquals(running, running.copy(phase = "paused"))
        assertNotEquals(running, running.copy(activeSince = 2000L))
        assertNotEquals(running, running.copy(activeSeconds = 3.0))
        assertNotEquals(running, running.copy(id = null, phase = "idle"))
        assertEquals(4500L, running.timerMillis(3000L))
        assertEquals(8500L, running.timerMillis(7000L))
        val paused = running.copy(phase = "paused")
        assertEquals(2500L, paused.timerMillis(3000L))
        assertEquals(2500L, paused.timerMillis(7000L))
    }
}
