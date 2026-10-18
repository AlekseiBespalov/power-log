package app.powerlog.bridge

import androidx.health.connect.client.records.Record
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking

internal object HealthProviderCalls {
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "PowerLogHealthProvider").apply { isDaemon = true }
    }
    private var pending: Future<*>? = null
    private var unresolved = false

    fun <T> call(timeoutMillis: Long, body: suspend () -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val task = submit(deadline, body)
        try {
            return task.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            synchronized(this) { if (pending === task && !task.isDone) unresolved = true }
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            synchronized(this) { if (pending === task && task.isDone) pending = null }
        }
    }

    private fun <T> submit(deadline: Long, body: suspend () -> T): Future<T> {
        while (true) {
            val previous =
                synchronized(this) {
                    if (pending?.isDone == false && unresolved)
                        throw TimeoutException("Health provider operation is still unresolved.")
                    if (deadline <= System.nanoTime())
                        throw TimeoutException("Timed out waiting for the Health provider.")
                    if (pending?.isDone != false) {
                        unresolved = false
                        return executor.submit(Callable { runBlocking { body() } }).also { pending = it }
                    }
                    checkNotNull(pending)
                }
            try {
                previous.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (_: ExecutionException) {
                // The preceding caller owns its result; a failed call still releases the provider slot.
            }
        }
    }
}

internal class BoundedHealthConnectAccess(
    private val access: HealthConnectAccess,
    private val timeoutMillis: Long = 10_000,
) : HealthConnectAccess by access {
    override suspend fun granted() = HealthProviderCalls.call(timeoutMillis) { access.granted() }

    override suspend fun insert(records: List<Record>) =
        HealthProviderCalls.call(timeoutMillis) { access.insert(records) }

    override suspend fun update(records: List<Record>) =
        HealthProviderCalls.call(timeoutMillis) { access.update(records) }

    override suspend fun read(type: KClass<out Record>, start: Instant, end: Instant, page: String?, own: Boolean) =
        HealthProviderCalls.call(timeoutMillis) { access.read(type, start, end, page, own) }

    override suspend fun readById(type: KClass<out Record>, id: String) =
        HealthProviderCalls.call(timeoutMillis) { access.readById(type, id) }
}
