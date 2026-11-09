package app.powerlog.domain.exports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentSpeedsTest {
    @Test
    fun keysInOrderDropTheirPrefixBeforeTheCutoff() {
        val speeds = RecentSpeeds()
        for (us in listOf(0L, 125L, 250L, 375L)) speeds.put(us, us / 100.0)
        speeds.dropBefore(250L)
        assertNull(speeds[0L])
        assertNull(speeds[125L])
        assertEquals(2.5, speeds[250L])
        assertEquals(3.75, speeds[375L])
    }

    @Test
    fun aKeyOutOfOrderStillDropsEveryKeyBeforeTheCutoff() {
        val speeds = RecentSpeeds()
        for (us in listOf(500L, 100L, 600L, 200L)) speeds.put(us, us / 100.0)
        speeds.dropBefore(550L)
        for (us in listOf(500L, 100L, 200L)) assertNull(speeds[us], "$us")
        assertEquals(6.0, speeds[600L])
    }
}
