package app.powerlog.core.lifecycle

import kotlin.test.*

class StorageReclaimTest {
    private val page = 4096L
    private val plenty = Long.MAX_VALUE / 4

    private fun next(
        autoVacuum: Long,
        pageCount: Long = 10_000,
        freePages: Long = 0,
        recording: Boolean = false,
        available: Long = plenty,
    ) = StorageReclaim.next(autoVacuum, page, pageCount, freePages, recording, available)

    @Test
    fun incrementalStoresReclaimFreePagesInBoundedStepsEvenWhileRecording() {
        for (recording in listOf(false, true)) {
            val first = next(2, freePages = 1_000, recording = recording, available = 0)
            assertEquals(StorageReclaimAction.reclaim, first.action)
            assertEquals(StorageReclaim.stepBytes / page, first.pages)
            val tail = next(2, freePages = 3, recording = recording, available = 0)
            assertEquals(StorageReclaimAction.reclaim, tail.action)
            assertEquals(3, tail.pages)
            assertEquals(StorageReclaimAction.done, next(2, recording = recording).action)
        }
        val large = StorageReclaim.next(2, 1L shl 21, 100, 50, false, 0)
        assertEquals(1, large.pages)
    }

    @Test
    fun fullStoresSwitchToIncrementalWithoutVacuum() {
        for (recording in listOf(false, true)) assertEquals(
            StorageReclaimAction.enableIncremental,
            next(1, freePages = 0, recording = recording, available = 0).action,
        )
    }

    @Test
    fun storesWithoutAutoVacuumConvertOnlyWhenIdleWithRoomForTwoLiveCopies() {
        val live = (10_000L - 4_000) * page
        val needed = 2 * live + StorageReclaim.conversionMarginBytes
        assertEquals(StorageReclaimAction.convert, next(0, freePages = 4_000, available = needed).action)
        assertEquals(StorageReclaimAction.waitForSpace, next(0, freePages = 4_000, available = needed - 1).action)
        assertEquals(
            StorageReclaimAction.waitForIdle,
            next(0, freePages = 4_000, recording = true, available = needed).action,
        )
        assertEquals(StorageReclaimAction.convert, next(0, freePages = 0).action)
    }

    @Test
    fun recordingPhasesExcludeEndedAndInterruptedRides() {
        val phases = LifecyclePhase.entries.map { it.name }
        assertTrue(phases.containsAll(StorageReclaim.recordingPhases))
        assertEquals(
            listOf("idle", "interrupted", "completed", "failed"),
            phases.filter { it !in StorageReclaim.recordingPhases },
        )
    }

    @Test
    fun rejectsImpossiblePageCounts() {
        assertFailsWith<IllegalArgumentException> { next(3) }
        assertFailsWith<IllegalArgumentException> { next(2, pageCount = 10, freePages = 11) }
        assertFailsWith<IllegalArgumentException> { StorageReclaim.next(2, 0, 10, 0, false, 0) }
    }
}
