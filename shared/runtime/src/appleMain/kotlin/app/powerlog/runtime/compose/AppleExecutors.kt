package app.powerlog.runtime.compose

import app.powerlog.application.Executors
import app.powerlog.data.StoreExecutors
import app.powerlog.runtime.compose.AppleQueueFactory.Priority
import app.powerlog.runtime.compose.AppleQueueFactory.concurrent
import app.powerlog.runtime.compose.AppleQueueFactory.serial

internal object AppleExecutors {
    fun application(queues: AppleQueues): Executors =
        Executors(
            ride = QueueDispatcher(queues.ride),
            health = QueueDispatcher(queues.health),
            maintenance = QueueDispatcher(queues.maintenance),
            reads = QueueDispatcher(concurrent("app.powerlog.reads", Priority.userInitiated)).limitedParallelism(4),
        )

    fun stores(): StoreExecutors =
        StoreExecutors(
            archiveWriter = QueueDispatcher(serial("app.powerlog.archive", Priority.userInitiated)),
            archiveReads =
                QueueDispatcher(concurrent("app.powerlog.archive.reads", Priority.userInitiated)).limitedParallelism(4),
            cacheWriter = QueueDispatcher(serial("app.powerlog.cache", Priority.utility)),
            cacheReads =
                QueueDispatcher(concurrent("app.powerlog.cache.reads", Priority.utility)).limitedParallelism(2),
            log = QueueDispatcher(serial("app.powerlog.log", Priority.utility)),
        )
}
