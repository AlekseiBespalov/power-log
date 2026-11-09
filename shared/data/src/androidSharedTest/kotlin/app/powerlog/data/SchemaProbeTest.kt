package app.powerlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.ok
import app.powerlog.domain.core.failure.RefusalDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaProbeTest {
    @Test
    fun aFileAtTheArchiveVersionWithoutItsTablesIsRefusedAndLeftByteForByte() = runTest {
        val store = SqlArchiveHarness(AndroidSqlPlatform).create(this)
        AndroidSqlPlatform.sqlite(store.archivePath, listOf("PRAGMA user_version = $ARCHIVE_VERSION"))
        val before = store.snapshot().mapValues { it.value.toList() }
        assertEquals(StoreOpen.Refused(RefusalDetail.unreadable), store.host.open().ok())
        assertEquals(before, store.snapshot().mapValues { it.value.toList() })
    }
}
