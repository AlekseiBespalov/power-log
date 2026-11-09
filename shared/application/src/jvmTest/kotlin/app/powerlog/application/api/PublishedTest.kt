package app.powerlog.application.api

import app.powerlog.application.port.Clock
import app.powerlog.domain.core.time.ClockReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class PublishedTest {
    @Test
    fun anUrgentVersionEndsTheRateCapsWaitAtOnce() = runTest {
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        val published = Published("first", 1_000_000, clock)
        val first = published.next(null) as StreamAnswer.Value
        published.publish("routine", urgent = false)
        val next = async { published.next(first.revision) }
        delay(200)
        published.publish("urgent", urgent = true)
        val answer = next.await() as StreamAnswer.Value
        assertEquals("urgent", answer.value)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test
    fun routineVersionsAreAnsweredAtMostOncePerInterval() = runTest {
        val clock =
            object : Clock {
                override fun monoUs(): Long = testScheduler.currentTime * 1_000

                override fun utcUs(): Long = monoUs()

                override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
            }
        val published = Published(0, 1_000_000, clock)
        var revision = (published.next(null) as StreamAnswer.Value).revision
        val answeredAt = mutableListOf<Long>()
        val reader = async {
            repeat(3) {
                val answer = published.next(revision) as StreamAnswer.Value
                revision = answer.revision
                answeredAt += testScheduler.currentTime
            }
        }
        for (value in 1..30) {
            published.publish(value, urgent = false)
            delay(125)
        }
        reader.await()
        assertEquals(listOf(1_000L, 2_000L, 3_000L), answeredAt)
    }
}
