package app.powerlog.bridge

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

// FileProvider caches its roots per authority, but every Robolectric test has its own data directory.
internal fun forgetFileProviderRoots() =
    ReflectionHelpers.getStaticField<HashMap<*, *>>(FileProvider::class.java, "sCache").clear()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSharingTest {
    private lateinit var application: Application
    private lateinit var authority: String

    @Before
    fun setUp() {
        forgetFileProviderRoots()
        application = RuntimeEnvironment.getApplication()
        val provider = "${application.packageName}.powerlog.files"
        authority = provider
        shadowOf(application.packageManager)
            .addOrUpdateProvider(
                ProviderInfo().apply {
                    this.authority = provider
                    name = FileProvider::class.java.name
                    packageName = application.packageName
                    metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.power_log_files) }
                }
            )
    }

    @After
    fun tearDown() {
        File(application.filesDir, "exports").deleteRecursively()
        File(application.cacheDir, "exports").deleteRecursively()
        File(application.filesDir, "ride.fit").delete()
    }

    private fun file(root: File, path: String) =
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeText("ride")
        }

    @Test
    fun aCommittedExportIsSharedInPlace() {
        file(application.filesDir, "exports/0f0e/PowerLog-ride.fit")
        val committed = "content://$authority/ride-exports/0f0e/PowerLog-ride.fit"
        val shared = ExportSharing.shareable(application, committed)
        assertEquals(committed, shared.toString())
        assertEquals("application/octet-stream", ExportSharing.mimeType(shared))
        assertEquals(1, File(application.filesDir, "exports").walk().count { it.isFile })
        assertFalse(File(application.cacheDir, "exports").exists())
    }

    @Test
    fun aCommittedFileGivenAsAFileUriIsSharedThroughTheProvider() {
        val csv = file(application.filesDir, "exports/0f0e/My-ride.csv")
        val shared = ExportSharing.shareable(application, Uri.fromFile(csv).toString())
        assertEquals("content://$authority/ride-exports/0f0e/My-ride.csv", shared.toString())
        assertEquals("text/csv", ExportSharing.mimeType(shared))
        assertEquals(
            "application/zip",
            ExportSharing.mimeType(Uri.parse("content://$authority/ride-exports/a/power-log-original-x.zip")),
        )
    }

    @Test
    fun filesOutsideTheExportFoldersAreRefused() {
        file(application.filesDir, "exports/0f0e/PowerLog-ride.fit")
        val outside = file(application.filesDir, "ride.fit")
        for (uri in
            listOf(
                Uri.fromFile(outside).toString(),
                Uri.fromFile(file(application.cacheDir, "exports/ride.fit")).toString(),
                "content://$authority/ride-exports/0f0e/missing.fit",
                "content://$authority/ride-exports/0f0e/..%2F..%2Fride.fit",
                "content://another.provider/ride-exports/0f0e/PowerLog-ride.fit",
                "content://$authority/exports/0f0e/PowerLog-ride.fit",
                "https://example.com/ride.fit",
            )) {
            val error = assertThrows(IllegalArgumentException::class.java) { ExportSharing.shareable(application, uri) }
            assertEquals("Export file is unavailable.", error.message)
        }
    }
}
