package app.powerlog.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationBoundaryTest {
    @Test
    fun presentationBackfilledStatus() {
        assertEquals("backfilled", StreamStatus.normalized("backfilled"))
    }
}
