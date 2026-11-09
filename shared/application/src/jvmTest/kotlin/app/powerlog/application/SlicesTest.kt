package app.powerlog.application

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class SlicesTest {
    @Test
    fun workOverManyRowsLetsOtherWorkRunEveryYieldPolicyRows() = runTest {
        val events = mutableListOf<String>()
        launch {
            repeat(4) {
                events += "other"
                yield()
            }
        }
        inSlices(700) { from, to -> events += "$from-$to" }

        assertEquals(listOf("0-256", "other", "256-512", "other", "512-700"), events)
    }

    @Test
    fun aDispatcherWithHostTurnsGetsOneBetweenEverySliceInsteadOfAYield() = runTest {
        val host =
            object : CoroutineDispatcher(), HostTurns {
                val events = mutableListOf<String>()

                override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

                override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()

                override suspend fun hostTurn() {
                    events += "host"
                }
            }
        withContext(host) { inSlices(700) { from, to -> host.events += "$from-$to" } }
        assertEquals(listOf("0-256", "host", "256-512", "host", "512-700"), host.events)
    }

    @Test
    fun noRowsIsNoWork() = runTest {
        val slices = mutableListOf<Int>()
        inSlices(0) { from, _ -> slices += from }
        assertEquals(emptyList(), slices)
    }
}
