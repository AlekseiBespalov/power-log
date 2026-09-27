package app.powerlog.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideStoreCorruptionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertFailsClosed(name: String, originals: Map<File, ByteArray>) {
        val store = RideStore(context, name)
        fun assertRetained() = originals.forEach { (file, bytes) ->
            assertTrue(file.name, file.exists())
            assertArrayEquals(file.name, bytes, file.readBytes())
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) { store.writableDatabase }
            assertEquals("Power Log could not open your rides because the database is corrupt.", error.message)
            assertRetained()
            assertThrows(IllegalStateException::class.java) { store.list(CatalogInput()) }
            store.close()
            assertRetained()
        } finally {
            store.close()
        }
    }

    @Test
    fun unreadableDatabaseFileIsRetained() {
        val name = "corrupt-${UUID.randomUUID()}.sqlite"
        val database = context.getDatabasePath(name)
        database.parentFile!!.mkdirs()
        database.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        try {
            assertFailsClosed(name, mapOf(database to database.readBytes()))
            assertEquals(listOf(name), database.parentFile!!.list()!!.filter { it.startsWith(name) })
        } finally {
            context.deleteDatabase(name)
        }
    }
}
