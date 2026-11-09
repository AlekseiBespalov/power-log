package app.powerlog.data

import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.PlatformCode
import kotlinx.coroutines.CompletableDeferred

internal external val indexedDB: IDBFactory

internal external interface IDBFactory {
    fun open(name: String, version: Int): IDBOpenDBRequest

    fun deleteDatabase(name: String): IDBOpenDBRequest
}

internal external interface IDBRequest {
    val result: dynamic
    val error: dynamic
    val transaction: IDBTransaction?
    var onsuccess: ((dynamic) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
}

internal external interface IDBOpenDBRequest : IDBRequest {
    var onupgradeneeded: ((dynamic) -> Unit)?
    var onblocked: ((dynamic) -> Unit)?
}

internal external interface IDBDatabase {
    fun transaction(storeNames: Array<String>, mode: String, options: dynamic): IDBTransaction

    fun createObjectStore(name: String, options: dynamic): IDBObjectStore

    fun close()

    var onversionchange: ((dynamic) -> Unit)?
}

internal external interface IDBTransaction {
    val error: dynamic

    fun objectStore(name: String): IDBObjectStore

    fun abort()

    var oncomplete: ((dynamic) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
    var onabort: ((dynamic) -> Unit)?
}

internal external interface IDBObjectStore {
    fun get(key: dynamic): IDBRequest

    fun put(value: dynamic): IDBRequest

    fun add(value: dynamic): IDBRequest

    fun clear(): IDBRequest

    fun delete(query: dynamic): IDBRequest

    fun getAllKeys(query: dynamic, count: Int): IDBRequest

    fun count(): IDBRequest

    fun index(name: String): IDBIndex

    fun openCursor(query: dynamic, direction: String): IDBRequest

    fun createIndex(name: String, keyPath: dynamic, options: dynamic): IDBIndex
}

internal external interface IDBIndex {
    fun openCursor(query: dynamic, direction: String): IDBRequest
}

internal external interface IDBCursorWithValue {
    val value: dynamic

    fun `continue`()
}

internal external class IDBKeyRange {
    companion object {
        fun bound(lower: dynamic, upper: dynamic, lowerOpen: Boolean, upperOpen: Boolean): IDBKeyRange

        fun upperBound(upper: dynamic, open: Boolean): IDBKeyRange
    }
}

internal external class BroadcastChannel(name: String) {
    fun postMessage(message: dynamic)

    fun close()

    var onmessage: ((dynamic) -> Unit)?
}

internal fun record(): dynamic = js("({})")

private fun isDomException(error: Any?): Boolean = js("error instanceof DOMException").unsafeCast<Boolean>()

internal fun storageError(error: dynamic): StorageError {
    val name = error?.name as? String
    val kind =
        when (name) {
            "QuotaExceededError" -> StorageError.Kind.full
            "ConstraintError" -> StorageError.Kind.constraint
            "InvalidStateError" -> StorageError.Kind.closed
            else -> StorageError.Kind.io
        }
    return StorageError(kind, name?.let { PlatformCode("IndexedDB", it) })
}

// One transaction: start issues its requests, and the requests' callbacks issue the next ones, because a
// transaction commits once no request is pending. The caller resumes only on complete or abort.
internal class IdbJob<T>(private val transaction: IDBTransaction) {
    private var answered = false
    private var answer: T? = null
    private var undone: T? = null
    private var failure: StorageError? = null

    fun store(store: IdbObjectStore): IDBObjectStore = transaction.objectStore(store.name)

    fun answer(value: T) {
        answer = value
        answered = true
    }

    fun undo(value: T) {
        undone = value
        transaction.abort()
    }

    fun fail(error: StorageError) {
        failure = error
        transaction.abort()
    }

    fun request(request: IDBRequest, then: (dynamic) -> Unit) {
        request.onsuccess = { guard { then(request.result) } }
    }

    fun cursor(request: IDBRequest, each: (dynamic) -> Boolean, done: () -> Unit) {
        request.onsuccess = {
            guard {
                val cursor = request.result.unsafeCast<IDBCursorWithValue?>()
                if (cursor == null) done() else if (each(cursor.value)) cursor.`continue`() else done()
            }
        }
    }

    fun all(requests: List<IDBRequest>, done: (List<dynamic>) -> Unit) {
        if (requests.isEmpty()) return guard { done(emptyList()) }
        val results = arrayOfNulls<Any?>(requests.size)
        var left = requests.size
        requests.forEachIndexed { index, request ->
            request(request) { result ->
                results[index] = result
                if (--left == 0) done(results.toList())
            }
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            failure =
                when {
                    error is ConstraintViolation -> StorageError(StorageError.Kind.constraint)
                    isDomException(error) -> failure ?: storageError(error)
                    else -> failure ?: StorageError(StorageError.Kind.io)
                }
            transaction.abort()
        }
    }

    suspend fun finish(): StoreResult<T> {
        val outcome = CompletableDeferred<StoreResult<T>>()
        transaction.oncomplete = {
            @Suppress("UNCHECKED_CAST")
            outcome.complete(
                if (answered) StoreResult.Ok(answer as T) else StoreResult.Failed(StorageError(StorageError.Kind.io))
            )
        }
        transaction.onabort = {
            val value = undone
            @Suppress("UNCHECKED_CAST")
            outcome.complete(
                when {
                    value != null -> StoreResult.Ok(value as T)
                    else -> StoreResult.Failed(failure ?: storageError(transaction.error))
                }
            )
        }
        return outcome.await()
    }

    companion object {
        suspend fun <T> run(
            database: IDBDatabase,
            stores: List<IdbObjectStore>,
            write: Boolean,
            strict: Boolean,
            start: IdbJob<T>.() -> Unit,
        ): StoreResult<T> {
            val options = record()
            if (strict) options.durability = "strict"
            val job =
                try {
                    IdbJob<T>(
                        database.transaction(
                            stores.map { it.name }.toTypedArray(),
                            if (write) "readwrite" else "readonly",
                            options,
                        )
                    )
                } catch (error: Throwable) {
                    return StoreResult.Failed(storageError(error))
                }
            job.guard { job.start() }
            return job.finish()
        }
    }
}

internal fun Long.js(): Double = toDouble()

internal fun long(value: dynamic): Long = value.unsafeCast<Double>().toLong()

internal fun longOrNull(value: dynamic): Long? = if (value == null) null else long(value)

internal fun textOrNull(value: dynamic): String? = if (value == null) null else value.unsafeCast<String>()
