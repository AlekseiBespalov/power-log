package app.powerlog.data

import app.powerlog.application.store.StoreOpen
import app.powerlog.application.store.ok
import app.powerlog.domain.core.failure.RefusalDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class SchemaProbeTest {
    @Test
    fun aFileAtTheArchiveVersionWithoutItsTablesIsRefusedAndLeftByteForByte() = runTest {
        val store = SqlArchiveHarness(JvmSqlPlatform).create(this)
        JvmSqlPlatform.sqlite(store.archivePath, listOf("PRAGMA user_version = $ARCHIVE_VERSION"))
        val before = store.snapshot().mapValues { it.value.toList() }
        assertEquals(StoreOpen.Refused(RefusalDetail.unreadable), store.host.open().ok())
        assertEquals(before, store.snapshot().mapValues { it.value.toList() })
    }
}
