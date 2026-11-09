package app.powerlog.data

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.powerlog.application.store.ContractData
import app.powerlog.application.store.FilePragmas
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreFile
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.WriteBatch
import app.powerlog.application.store.ok
import app.powerlog.domain.core.time.ClockReading
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOpenHelperTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val pragmas =
        listOf(FilePragmas(StoreFile.archive, 2, "wal", 2, 1_000), FilePragmas(StoreFile.cache, 2, "wal", 2, 1_000))

    private fun stores(executors: TestExecutors) =
        AndroidStores.files(context, executors.store, SilentLog(), { ClockReading(0, 0) })

    @Test
    fun aStoredArchiveReopensThroughTheOpenHelperWithItsPragmas() = runTest {
        val executors = AndroidSqlPlatform.executors()
        try {
            val ride = ContractData.ride(ContractData.rideId(1))
            val first = stores(executors)
            assertEquals(StoreOpen.Opened(pragmas), first.stores.host.open().ok())
            first.stores.writer.commit(WriteBatch(listOf(Mutation.CreateRide(ride, ContractData.health)))).ok()
            first.stores.host.close().ok()
            val second = stores(executors)
            assertEquals(StoreOpen.Opened(pragmas), second.stores.host.open().ok())
            assertEquals(ride, second.stores.rides.ride(ride.id).ok()?.ride)
            second.stores.host.close().ok()
        } finally {
            executors.close()
        }
    }

    @Test
    fun theBootstrapLeavesANewArchiveInWalWithIncrementalVacuumAndTheCacheOutOfBackups() = runTest {
        val executors = AndroidSqlPlatform.executors()
        try {
            val sql = stores(executors)
            assertIs<StoreOpen.Opened>(sql.stores.host.open().ok())
            sql.stores.host.close().ok()
            val header = context.getDatabasePath(ARCHIVE_FILE).readBytes().copyOf(100)
            assertEquals(listOf<Byte>(2, 2), listOf(header[18], header[19]), "file format versions: WAL")
            assertTrue(header.int(52) > 0, "auto-vacuum keeps the largest root page")
            assertEquals(1, header.int(64), "incremental vacuum")
            assertTrue(File(context.noBackupFilesDir, CACHE_FILE).isFile)
        } finally {
            executors.close()
        }
    }

    @Test
    fun aFileOpensNoMoreReadersThanTheFrameworksWalPoolLeavesBesideItsPrimary() {
        val resources = Resources.getSystem()
        val pool = resources.getInteger(resources.getIdentifier("db_connection_pool_size", "integer", "android"))
        val opening =
            AndroidFile(context, ARCHIVE_FILE, noBackup = false, readers = pool + 4, { it }).open(ARCHIVE_SCHEMA)
        try {
            assertEquals(maxOf(2, pool) - 1, assertIs<FileOpening.Opened>(opening).readers.size)
        } finally {
            (opening as? FileOpening.Opened)?.release?.invoke()
        }
    }

    private fun ByteArray.int(offset: Int): Int =
        (0 until 4).fold(0) { value, index -> value shl 8 or (this[offset + index].toInt() and 0xff) }
}
