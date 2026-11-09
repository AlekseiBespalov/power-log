package app.powerlog.data

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.CacheChange
import app.powerlog.application.store.CacheRepository
import app.powerlog.application.store.ConnectionRepository
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.ForeignArchive
import app.powerlog.application.store.ForeignKind
import app.powerlog.application.store.HealthRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.MaintenanceRepository
import app.powerlog.application.store.MeasurementRepository
import app.powerlog.application.store.RideRepository
import app.powerlog.application.store.SettingsRepository
import app.powerlog.application.store.Stall
import app.powerlog.application.store.StoreHost
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.StoreUnderTest
import app.powerlog.application.store.WebArchiveHarness
import app.powerlog.application.store.WebStoreUnderTest
import app.powerlog.application.store.ok
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.rules.RULES_VERSION
import app.powerlog.domain.core.time.ClockReading
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.khronos.webgl.Uint8Array

internal class IdbArchiveHarness : WebArchiveHarness {
    override val storesHealth: Boolean = false
    override val queueJobs: Int = StorePolicy.queueJobs
    override val foreignArchives: List<ForeignArchive> =
        listOf(
            object : ForeignArchive {
                override val kind: ForeignKind = ForeignKind.otherVersion
                override val name: String = "database version ${ARCHIVE_VERSION + 1}"

                override suspend fun write(store: StoreUnderTest) {
                    (store as IdbStore).session.writeNewerVersion()
                }
            }
        )

    override suspend fun create(scope: TestScope): IdbStore {
        val session = IdbSession("power-log-test-${Random.nextLong().toString(36)}", scope)
        scope.backgroundScope.coroutineContext.job.invokeOnCompletion { session.close() }
        return session.open(RULES_VERSION, CACHE_LAYOUT)
    }
}

internal class IdbSession(private val name: String, val scope: TestScope) {
    private val opened = mutableListOf<IdbStores>()
    private var restorePut: (() -> Unit)? = null

    fun open(rulesVersion: Int, layout: Int): IdbStore {
        val stores = IdbStores(name, SilentLog, { ClockReading(0, 0) }, rulesVersion, layout)
        opened += stores
        return IdbStore(this, stores, rulesVersion, layout)
    }

    fun failDerivedPuts() {
        val prototype = js("IDBObjectStore.prototype")
        val original = prototype.put
        prototype.put = refusingDerived(original)
        restorePut = { prototype.put = original }
    }

    suspend fun writeNewerVersion() {
        val written = CompletableDeferred<Unit>()
        val opening = js("indexedDB").open(name, ARCHIVE_VERSION + 1)
        opening.onupgradeneeded = {
            val options = record()
            options.keyPath = "id"
            val ride = record()
            ride.id = "kept"
            opening.result.createObjectStore("ride", options).put(ride)
        }
        opening.onerror = { written.completeExceptionally(IllegalStateException("open failed: ${opening.error}")) }
        opening.onsuccess = {
            opening.result.close()
            written.complete(Unit)
        }
        written.await()
    }

    // The database's version and every record, read on a connection of its own; nothing when no database has the name.
    suspend fun snapshot(): Map<String, ByteArray> {
        val read = CompletableDeferred<Map<String, ByteArray>>()
        val opening = js("indexedDB").open(name)
        opening.onupgradeneeded = { opening.transaction.abort() }
        opening.onerror = { read.complete(emptyMap()) }
        opening.onsuccess = {
            val database = opening.result
            val snapshot = mutableMapOf(name to "${database.version}".encodeToByteArray())
            val stores = js("Array.from")(database.objectStoreNames).unsafeCast<Array<String>>()
            if (stores.isEmpty()) {
                database.close()
                read.complete(snapshot)
            } else {
                val transaction = database.transaction(stores, "readonly")
                for (store in stores) {
                    val records = transaction.objectStore(store).getAll()
                    records.onsuccess = {
                        snapshot["$name/$store"] = JSON.stringify(records.result).encodeToByteArray()
                    }
                }
                transaction.oncomplete = {
                    database.close()
                    read.complete(snapshot)
                }
                transaction.onabort = {
                    database.close()
                    read.completeExceptionally(IllegalStateException("snapshot failed: ${transaction.error}"))
                }
            }
        }
        return read.await()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun close() {
        restorePut?.invoke()
        GlobalScope.launch {
            for (stores in opened) stores.archive.close()
            indexedDB.deleteDatabase(name)
        }
    }
}

private val refusingDerived: (dynamic) -> dynamic =
    js(
        "(function (original) { return function (value) { " +
            "if (this.name === 'derived') throw new DOMException('refused by the test', 'UnknownError'); " +
            "return original.call(this, value); }; })"
    )

internal object SilentLog : DiagnosticLogRepository {
    override suspend fun append(line: LogLine) {}

    override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
        StoreResult.Ok(LogChunk(ByteArray(0), null))
}

internal class IdbStore(
    val session: IdbSession,
    private val sql: IdbStores,
    private val rulesVersion: Int,
    private val layout: Int,
) : WebStoreUnderTest {
    override val host: StoreHost = sql.stores.host
    override val writer: ArchiveWriter = sql.stores.writer
    override val rides: RideRepository = sql.stores.rides
    override val measurements: MeasurementRepository = sql.stores.measurements
    override val connection: ConnectionRepository = sql.stores.connection
    override val maintenance: MaintenanceRepository = sql.stores.maintenance
    override val health: HealthRepository = sql.stores.health
    override val settings: SettingsRepository = sql.stores.settings
    override val cache: CacheRepository = sql.stores.cache

    override suspend fun secondConnection(): StoreUnderTest = session.open(rulesVersion, layout)

    override suspend fun reopen(): IdbStore {
        host.close().ok()
        return session.open(rulesVersion, layout)
    }

    override suspend fun reopenAfter(change: CacheChange): IdbStore {
        host.close().ok()
        return when (change) {
            CacheChange.rulesVersion -> session.open(rulesVersion + 1, layout)
            CacheChange.layout -> session.open(rulesVersion, layout + 1)
        }
    }

    override suspend fun stallWriter(): Stall {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        session.scope.backgroundScope.launch {
            sql.archive.job {
                started.complete(Unit)
                released.await()
            }
        }
        started.await()
        return object : Stall {
            override suspend fun release() {
                released.complete(Unit)
            }
        }
    }

    override suspend fun storedRows(): Long {
        val stores =
            listOf(
                IdbObjectStore.ride,
                IdbObjectStore.rideEvent,
                IdbObjectStore.controllerSession,
                IdbObjectStore.facts,
            )
        return sql.archive
            .read<Long>(stores) {
                all(stores.map { store(it).count() }) { counts -> answer(counts.sumOf { count -> long(count) }) }
            }
            .ok()
    }

    override suspend fun snapshot(): Map<String, ByteArray> = session.snapshot()

    override suspend fun writeSettingsText(text: String?) {
        sql.settings.saveSettings(text).ok()
    }

    override suspend fun writeOutOfVocabulary(rideId: RideId) {
        sql.archive
            .write<Unit>(listOf(IdbObjectStore.ride)) {
                val rides = store(IdbObjectStore.ride)
                request(rides.get(rideId.value)) { record ->
                    record.phase = "flying"
                    rides.put(record)
                    answer(Unit)
                }
            }
            .ok()
    }

    override suspend fun corruptCachedValues(rideId: RideId) {
        val range =
            IDBKeyRange.bound(
                arrayOf(rideId.value, "", "", Double.NEGATIVE_INFINITY),
                arrayOf(rideId.value, "￿", "￿", Double.POSITIVE_INFINITY),
                false,
                false,
            )
        sql.archive
            .write<Unit>(listOf(IdbObjectStore.derived)) {
                val derived = store(IdbObjectStore.derived)
                cursor(
                    derived.openCursor(range, "next"),
                    each = { entry ->
                        entry.value = Uint8Array(arrayOf<Byte>(0, -1))
                        derived.put(entry)
                        true
                    },
                    done = { answer(Unit) },
                )
            }
            .ok()
    }

    override suspend fun failCacheWrites() {
        session.failDerivedPuts()
    }
}
