package app.powerlog.core.telemetry

import kotlin.test.*

class TelemetryLinkLimitTest {
    private fun limit(vararg ages: Double) = TelemetryLinkLimit().apply { ages.forEach(::peerEnded) }

    @Test
    fun threeDropsAtOneAgePlanTheReconnectBeforeTheShortest() {
        assertNull(limit().reconnectAtSeconds)
        assertNull(limit(95.0, 95.6).reconnectAtSeconds)
        assertEquals(92.9, limit(95.0, 95.6, 94.9).reconnectAtSeconds!!, 1e-9)
    }

    @Test
    fun theLastThreeDropsMustAgreeWithinOneSecond() {
        val limit = limit(95.0, 96.2, 95.0)
        assertNull(limit.reconnectAtSeconds)
        limit.peerEnded(95.5)
        assertNull(limit.reconnectAtSeconds)
        limit.peerEnded(95.1)
        assertEquals(93.0, limit.reconnectAtSeconds!!, 1e-9)
    }

    @Test
    fun linksShorterThanAStableLinkNeverPlanAReconnect() {
        assertNull(limit(20.0, 20.2, 20.1).reconnectAtSeconds)
        assertNotNull(limit(30.0, 30.2, 30.1).reconnectAtSeconds)
    }

    @Test
    fun aDropBeforeThePlannedReconnectAbandonsItForTheSession() {
        val limit = limit(95.0, 95.0, 95.0)
        limit.peerEnded(2.0)
        assertNull(limit.reconnectAtSeconds)
        repeat(3) { limit.peerEnded(95.0) }
        assertNull(limit.reconnectAtSeconds)
    }

    @Test
    fun aDropAtTheLimitKeepsThePlan() {
        val limit = limit(95.0, 95.6, 94.9)
        limit.peerEnded(95.3)
        assertEquals(92.9, limit.reconnectAtSeconds!!, 1e-9)
    }

    @Test
    fun invalidAgesAreRefused() {
        for (age in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1)) assertFailsWith<IllegalArgumentException> {
            TelemetryLinkLimit().peerEnded(age)
        }
    }
}
