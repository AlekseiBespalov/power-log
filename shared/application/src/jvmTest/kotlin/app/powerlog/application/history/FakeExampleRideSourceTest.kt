package app.powerlog.application.history

import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.ids.RideId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeExampleRideSourceTest {
    private val first = RideId("11111111-1111-4111-8111-111111111111")
    private val second = RideId("22222222-2222-4222-8222-222222222222")
    private val opening = WriteBatch(listOf(Mutation.SelectBike(null)))
    private val closing = WriteBatch(emptyList(), closedRide = first)

    @Test
    fun ridesAreListedInTheGivenOrder() {
        val source = FakeExampleRideSource(mapOf(second to emptyList(), first to listOf(opening)))
        assertEquals(listOf(second, first), source.rideIds)
    }

    @Test
    fun batchesComeByIndexAndEndWithNull() {
        val source = FakeExampleRideSource(mapOf(first to listOf(opening, closing)))
        assertEquals(opening, source.batch(first, 0))
        assertEquals(closing, source.batch(first, 1))
        assertNull(source.batch(first, 2))
    }

    @Test
    fun anUnknownRideHasNoBatches() {
        assertNull(FakeExampleRideSource(mapOf(first to listOf(opening))).batch(second, 0))
    }

    @Test
    fun everyRequestIsRecorded() {
        val source = FakeExampleRideSource(mapOf(first to listOf(opening)))
        source.batch(first, 0)
        source.batch(second, 3)
        assertEquals(listOf(first to 0, second to 3), source.requests)
    }
}
