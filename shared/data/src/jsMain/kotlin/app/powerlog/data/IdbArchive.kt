package app.powerlog.data

import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.StorageError
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ids.RideId
import kotlinx.coroutines.CompletableDeferred

internal const val STORE_CHANNEL: String = "power-log-store"

internal val ARCHIVE_STORES: List<IdbObjectStore> = IdbObjectStore.entries.filter { it != IdbObjectStore.derived }

internal class IdbArchive(private val name: String, private val cache: IdbCache) : StoreHost {
    private enum class Lifecycle {
        unopened,
        open,
        closed,
    }

    private sealed interface Opening {
        class Opened(val database: IDBDatabase) : Opening

        data object Refused : Opening

        class Failed(val error: StorageError) : Opening
    }

    private var lifecycle = Lifecycle.unopened
    private var database: IDBDatabase? = null
    private var channel: BroadcastChannel? = null
    private val queue = WriterQueue()
    val notices: Notices = Notices()

    override suspend fun open(): StoreResult<StoreOpen> = queue.exclusive {
        when (lifecycle) {
            Lifecycle.open -> StoreResult.Ok(StoreOpen.Opened(emptyList()))
            Lifecycle.closed -> closed()
            Lifecycle.unopened -> {
                lifecycle = Lifecycle.closed
                when (val opening = openDatabase()) {
                    Opening.Refused -> StoreResult.Ok(StoreOpen.Refused(RefusalDetail.unreadable))
                    is Opening.Failed -> StoreResult.Failed(opening.error)
                    is Opening.Opened -> opened(opening.database)
                }
            }
        }
    }

    override suspend fun close(): StoreResult<Unit> = queue.exclusive {
        lifecycle = Lifecycle.closed
        channel?.close()
        channel = null
        database?.close()
        database = null
        cache.close()
        StoreResult.Ok(Unit)
    }

    suspend fun <T> read(stores: List<IdbObjectStore>, start: IdbJob<T>.() -> Unit): StoreResult<T> {
        val database = live() ?: return closed()
        return IdbJob.run(database, stores, write = false, strict = false, start)
    }

    suspend fun <T> write(
        stores: List<IdbObjectStore>,
        committed: (T) -> Unit = {},
        start: IdbJob<T>.() -> Unit,
    ): StoreResult<T> {
        if (live() == null) return closed()
        return queue.run {
            val database = live() ?: return@run closed()
            val result = IdbJob.run(database, stores, write = true, strict = true, start)
            if (result is StoreResult.Ok) committed(result.value)
            result
        } ?: StoreResult.Failed(StorageError(StorageError.Kind.busy))
    }

    suspend fun <T> job(block: suspend () -> T): T? = queue.run(block)

    fun publish(notice: CommitNotice, historyMoved: Boolean) {
        notices.emit(notice, historyMoved)
        val message = record()
        message.revisions =
            notice.revisions.map { (ride, revision) -> arrayOf(ride.value, revision.js()) }.toTypedArray()
        message.historyRevision = notice.historyRevision.js()
        message.historyRides = notice.historyRides.map { it.value }.toTypedArray()
        message.historyMoved = historyMoved
        channel?.postMessage(message)
    }

    private fun received(message: dynamic) {
        val revisions =
            message.revisions.unsafeCast<Array<Array<dynamic>>>().associate {
                RideId(it[0].unsafeCast<String>()) to long(it[1])
            }
        val rides = message.historyRides.unsafeCast<Array<String>>().map(::RideId).toSet()
        notices.emit(
            CommitNotice(revisions, long(message.historyRevision), rides, otherTab = true),
            message.historyMoved.unsafeCast<Boolean>(),
        )
    }

    private fun live(): IDBDatabase? = database.takeIf { lifecycle == Lifecycle.open }

    private fun <T> closed(): StoreResult<T> = StoreResult.Failed(StorageError(StorageError.Kind.closed))

    private suspend fun opened(database: IDBDatabase): StoreResult<StoreOpen> {
        database.onversionchange = {
            database.close()
            lifecycle = Lifecycle.closed
            this.database = null
        }
        this.database = database
        channel = BroadcastChannel(STORE_CHANNEL).also { it.onmessage = { event -> received(event.data) } }
        lifecycle = Lifecycle.open
        cache.open(database)
        return StoreResult.Ok(StoreOpen.Opened(emptyList()))
    }

    private suspend fun openDatabase(): Opening {
        val outcome = CompletableDeferred<Opening>()
        val request =
            try {
                indexedDB.open(name, ARCHIVE_VERSION)
            } catch (error: Throwable) {
                return Opening.Failed(storageError(error))
            }
        var refused = false
        request.onupgradeneeded = { event ->
            if (event.oldVersion.unsafeCast<Int>() != 0) {
                refused = true
                request.transaction?.abort()
            } else {
                create(request.result.unsafeCast<IDBDatabase>(), request.transaction!!)
            }
        }
        request.onsuccess = { outcome.complete(Opening.Opened(request.result.unsafeCast<IDBDatabase>())) }
        request.onerror = {
            val error = request.error
            outcome.complete(
                if (refused || error?.name == "VersionError") Opening.Refused else Opening.Failed(storageError(error))
            )
        }
        return outcome.await()
    }

    private fun create(database: IDBDatabase, transaction: IDBTransaction) {
        for (store in IdbObjectStore.entries) {
            val options = record()
            options.keyPath = keyPath(store.keyPath)
            val created = database.createObjectStore(store.name, options)
            for (index in store.indexes) {
                val indexOptions = record()
                indexOptions.unique = index.unique
                created.createIndex(index.name, keyPath(index.keyPath), indexOptions)
            }
        }
        val app = record()
        app.k = APP
        app.historyRevision = 0.0
        transaction.objectStore(IdbObjectStore.appState.name).put(app)
        transaction.objectStore(IdbObjectStore.appState.name).put(cache.meta())
    }

    private fun keyPath(path: IdbKeyPath): dynamic =
        when (path) {
            is IdbKeyPath.Field -> path.name
            is IdbKeyPath.Compound -> path.names.toTypedArray()
        }

    companion object {
        const val APP = "app"
        const val CACHE = "cache"
    }
}
