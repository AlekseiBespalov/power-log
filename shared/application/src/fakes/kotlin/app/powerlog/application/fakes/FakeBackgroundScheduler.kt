package app.powerlog.application.fakes

import app.powerlog.application.port.BackgroundScheduler
import app.powerlog.application.port.ScheduleKind

class FakeBackgroundScheduler : BackgroundScheduler {
    data class Work(val rideId: String, val notBeforeUtcUs: Long?, val policy: ScheduleKind)

    private val queues = mutableMapOf<String, MutableList<Work>>()
    private val cancels = mutableListOf<String>()

    val cancelled: List<String>
        get() = cancels

    fun pending(rideId: String): List<Work> = queues[rideId].orEmpty()

    override fun schedule(rideId: String, notBeforeUtcUs: Long?, policy: ScheduleKind) {
        val work = Work(rideId, notBeforeUtcUs, policy)
        val queue = queues.getOrPut(rideId) { mutableListOf() }
        when (policy) {
            ScheduleKind.keep -> if (queue.isEmpty()) queue += work
            ScheduleKind.replace -> {
                queue.clear()
                queue += work
            }
            ScheduleKind.afterCurrent -> queue += work
        }
    }

    override fun cancel(rideId: String) {
        cancels += rideId
        queues -= rideId
    }

    fun run(rideId: String): Work? {
        val queue = queues[rideId] ?: return null
        val work = queue.removeFirstOrNull()
        if (queue.isEmpty()) queues -= rideId
        return work
    }
}
