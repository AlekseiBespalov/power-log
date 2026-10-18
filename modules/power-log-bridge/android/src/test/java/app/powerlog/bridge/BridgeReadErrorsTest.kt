package app.powerlog.bridge

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import app.powerlog.core.presentation.RideFailure
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BridgeReadErrorsTest {
    private lateinit var store: RideStore
    private lateinit var id: String

    private class ResultPromise : Promise {
        var code: String? = null
        var message: String? = null
        var cause: Throwable? = null
        var resolved = false
        var value: Any? = null

        override fun resolve(value: Any?) {
            resolved = true
            this.value = value
        }

        override fun reject(code: String?, message: String?, cause: Throwable?) {
            this.code = code
            this.message = message
            this.cause = cause
        }
    }

    @Before
    fun setup() {
        store = RideStore(RuntimeEnvironment.getApplication(), "bridge-errors-${UUID.randomUUID()}.sqlite")
        id =
            store.create(
                RideOptions(indoor = false, saveToHealth = false, recordGPS = false),
                SystemRecordingClock.read(),
            )
    }

    @After fun close() = store.close()

    @Test
    fun bluetoothPermissionDenialHasItsOwnFailureCode() {
        val promise = ResultPromise()
        submit(promise, { it.run() }, "bike") { throw SecurityException("Denied") }
        assertEquals("bluetoothDenied", promise.code)
    }

    @Test
    fun codedFailuresKeepTheirCodesThroughTheProductionSubmitJob() {
        for (code in listOf("ERR_READ_DEFERRED", "ERR_READ_CONTENTION", "ERR_STORAGE_BUSY")) {
            val failure = CodedException(code, "Localized failure", null)
            val promise = ResultPromise()
            submit(promise, { it.run() }) { throw failure }
            assertEquals(code, promise.code)
            assertEquals(RideFailure.storageBusy.text, promise.message)
            assertSame(failure, promise.cause)
            assertFalse(promise.resolved)
        }
    }

    @Test
    fun rejectedReadDispatchReportsDeferralWithoutExecutingTheBody() {
        val promise = ResultPromise()
        var executed = false
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        submit(promise, executor::execute) { executed = true }
        assertEquals("ERR_READ_DEFERRED", promise.code)
        assertEquals(RideFailure.storageBusy.text, promise.message)
        assertFalse(executed)
        assertFalse(promise.resolved)
    }

    @Test
    fun monitorRevisionContentionKeepsItsCodeThroughTheProductionSubmitJob() {
        val monitor = RideMonitor(store, RideDistance(store))
        val db = store.readableDatabase
        val factory = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
        val previous = factory.get(db)
        factory.set(
            db,
            SQLiteDatabase.CursorFactory { _, driver, table, query ->
                if (query.toString().contains("SELECT * FROM rides"))
                    db.execSQL("UPDATE rides SET revision=revision+1 WHERE id=?", arrayOf(id))
                SQLiteCursor(driver, table, query)
            },
        )
        try {
            val promise = ResultPromise()
            submit(promise, { it.run() }) {
                monitor.query(
                    id,
                    BridgeInputs.monitor(
                        MonitorOperation.Describe,
                        mapOf("source" to "workout", "id" to id, "generation" to 0),
                    ),
                )
            }
            assertEquals("ERR_READ_CONTENTION", promise.code)
            assertFalse(promise.resolved)
        } finally {
            factory.set(db, previous)
        }
    }

    @Test
    fun readsOfADeletedRideRejectAsRideDeleted() {
        store.remove(id)
        val reads: List<() -> Any?> =
            listOf(
                { store.revision(id) },
                { store.timing(id) },
                { store.metadata(id) },
                { store.available(id) },
                { store.pin(id) },
                { store.retain(id) },
            )
        for (read in reads) {
            val promise = ResultPromise()
            submit(promise, { it.run() }, body = read)
            assertEquals("rideDeleted", promise.code)
            assertEquals(RideFailure.rideDeleted.text, promise.message)
            assertFalse(promise.resolved)
        }
    }

    @Test
    fun dispatchFailuresRejectInsteadOfEscapingTheFunctionBody() {
        val promise = ResultPromise()
        submit(promise, { throw IllegalArgumentException("The module wasn't created!") }) { 7 }
        assertEquals("unknown", promise.code)
        assertEquals(RideFailure.unknown.text, promise.message)
        assertFalse(promise.resolved)
    }

    @Test
    fun lockedStorageRollsBackAndKeepsItsCodeThroughTheProductionSubmitJob() {
        val revision = store.revision(id)
        val promise = ResultPromise()
        submit(promise, { it.run() }) {
            store.transaction {
                store.insert(id, 1.0, iso(), "telemetry", true, 0, mapOf("humanPowerW" to 123.0))
                throw SQLiteDatabaseLockedException("Database is locked")
            }
        }
        assertEquals("ERR_STORAGE_BUSY", promise.code)
        assertEquals(revision, store.revision(id))
        assertTrue(store.page(id).isEmpty())
        assertFalse(promise.resolved)
    }

    @Test
    fun uncodedFailuresRejectAsTheUnknownFailureAndSuccessfulJobsResolve() {
        val failure = IllegalStateException("Check failed.")
        val rejected = ResultPromise()
        submit(rejected, { it.run() }) { throw failure }
        assertEquals("unknown", rejected.code)
        assertEquals(RideFailure.unknown.text, rejected.message)
        assertSame(failure, rejected.cause)
        val resolved = ResultPromise()
        submit(resolved, { it.run() }) { 7 }
        assertTrue(resolved.resolved)
        assertEquals(7, resolved.value)
        assertNull(resolved.code)
    }
}
