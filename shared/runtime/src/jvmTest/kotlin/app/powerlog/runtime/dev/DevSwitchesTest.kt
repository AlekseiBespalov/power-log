package app.powerlog.runtime.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevSwitchesTest {
    private val file =
        """{"bike": true, "longRide": false, "slice": true, "crashAfterSeconds": 2.5, "killAfterSeconds": 90}"""

    @Test
    fun theSwitchesFileGivesSecondsAsMicroseconds() {
        val switches = DevSwitches.parse(file)
        assertEquals(true, switches.bike)
        assertEquals(false, switches.longRide)
        assertEquals(true, switches.slice)
        assertEquals(2_500_000L, switches.crashAfterUs)
        assertEquals(90_000_000L, switches.killAfterUs)
    }

    @Test
    fun theTimersArmOnceAndARestartDoesNotReArmThem() {
        val restarted = DevSwitches.parse(DevSwitches.withoutTimers(file))
        assertEquals(true, restarted.bike)
        assertEquals(true, restarted.slice)
        assertNull(restarted.crashAfterUs)
        assertNull(restarted.killAfterUs)
    }

    @Test
    fun aMissingOrUnreadableFileTurnsEverySwitchOff() {
        for (text in listOf("", "not json", "[]", "{}")) {
            val switches = DevSwitches.parse(text)
            assertEquals(listOf(false, false, false), listOf(switches.bike, switches.longRide, switches.slice), text)
            assertNull(switches.crashAfterUs)
            assertNull(switches.killAfterUs)
        }
    }

    @Test
    fun aNegativeOrNonFiniteDelayIsNoTimer() {
        assertNull(DevSwitches.fromSeconds(false, false, false, -1.0, null).crashAfterUs)
        assertNull(DevSwitches.fromSeconds(false, false, false, null, Double.NaN).killAfterUs)
    }
}
