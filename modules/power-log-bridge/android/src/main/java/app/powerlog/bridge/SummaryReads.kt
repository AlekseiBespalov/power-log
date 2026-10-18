package app.powerlog.bridge

import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class SummaryReads : AutoCloseable {
    private val executor: ExecutorService =
        ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
                Thread(task, "PowerLogSummary")
            }
            .apply { allowCoreThreadTimeOut(true) }
    private val jobs = mutableMapOf<String, Job>()

    private class Job(val owner: String, val promise: Promise) {
        var thread: Thread? = null
        var cancelled = false
        var settled = false
    }

    fun submit(owner: String, cancellationId: String, promise: Promise, body: () -> Any?) {
        val job = Job(owner, promise)
        synchronized(jobs) {
            if (cancellationId.isBlank() || cancellationId.length > 128 || jobs.containsKey(cancellationId)) {
                promise.rejectFailure(
                    CodedException("ERR_INVALID_RECORD", "Invalid summary cancellation identity", null)
                )
                return
            }
            jobs[cancellationId] = job
        }
        try {
            executor.execute {
                try {
                    synchronized(jobs) {
                        job.thread = Thread.currentThread()
                        if (job.cancelled) throw CodedException("ERR_READ_CANCELLED", "Analysis cancelled", null)
                    }
                    val result = body()
                    synchronized(jobs) {
                        if (!job.settled) {
                            job.settled = true
                            job.promise.resolve(result)
                        }
                    }
                } catch (error: Throwable) {
                    synchronized(jobs) {
                        if (!job.settled) {
                            job.settled = true
                            job.promise.rejectFailure(error, "read")
                        }
                    }
                } finally {
                    synchronized(jobs) { jobs.remove(cancellationId) }
                    Thread.interrupted()
                }
            }
        } catch (error: RejectedExecutionException) {
            synchronized(jobs) {
                jobs.remove(cancellationId)
                if (!job.settled) {
                    job.settled = true
                    promise.rejectFailure(
                        CodedException("ERR_READ_DEFERRED", "Summary capacity is temporarily unavailable", error)
                    )
                }
            }
        }
    }

    fun cancel(cancellationId: String) =
        synchronized(jobs) {
            jobs[cancellationId]?.let { job ->
                job.cancelled = true
                job.thread?.interrupt()
                if (!job.settled) {
                    job.settled = true
                    job.promise.rejectFailure(CodedException("ERR_READ_CANCELLED", "Analysis cancelled", null))
                }
            }
            Unit
        }

    fun cancelOwner(owner: String) {
        val ids = synchronized(jobs) { jobs.filterValues { it.owner == owner }.keys.toList() }
        ids.forEach(::cancel)
    }

    override fun close() {
        val ids = synchronized(jobs) { jobs.keys.toList() }
        ids.forEach(::cancel)
        executor.shutdown()
    }
}
