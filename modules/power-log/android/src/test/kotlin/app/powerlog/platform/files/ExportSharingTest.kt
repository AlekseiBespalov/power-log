package app.powerlog.platform.files

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import app.powerlog.platform.R
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

// FileProvider caches its roots per authority, but every Robolectric test has its own data directory.
internal fun forgetFileProviderRoots() =
    ReflectionHelpers.getStaticField<HashMap<*, *>>(FileProvider::class.java, "sCache").clear()

internal fun registerFileProvider(application: Application): String {
    forgetFileProviderRoots()
    val authority = "${application.packageName}.powerlog.files"
    shadowOf(application.packageManager)
        .addOrUpdateProvider(
            ProviderInfo().apply {
                this.authority = authority
                name = FileProvider::class.java.name
                packageName = application.packageName
                metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.power_log_files) }
            }
        )
    return authority
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSharingTest {
    private lateinit var application: Application
    private lateinit var authority: String

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        authority = registerFileProvider(application)
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

    private fun shared(uri: String, mimeType: String = "application/octet-stream") =
        mapOf("uri" to uri, "name" to uri.substringAfterLast('/'), "mimeType" to mimeType, "sizeBytes" to 4.0)

    @Test
    fun aCommittedExportOpensTheChooserWithItsUriAndMimeType() {
        file(application.filesDir, "exports/0f0e/My-ride.csv")
        val committed = "content://$authority/ride-exports/0f0e/My-ride.csv"
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals("shared", ExportSharing.share(activity, shared(committed, "text/csv")))
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Export ride", chooser.getStringExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        @Suppress("DEPRECATION") assertEquals(committed, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM).toString())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun withoutAScreenSharingIsUiUnavailable() {
        file(application.filesDir, "exports/0f0e/PowerLog-ride.fit")
        val error =
            assertThrows(SinkException::class.java) {
                ExportSharing.share(null, shared("content://$authority/ride-exports/0f0e/PowerLog-ride.fit"))
            }
        assertEquals("uiUnavailable", error.code)
    }

    @Test
    fun filesOutsideTheExportFoldersAreRefused() {
        file(application.filesDir, "exports/0f0e/PowerLog-ride.fit")
        val outside = file(application.filesDir, "ride.fit")
        for (uri in
            listOf(
                Uri.fromFile(outside).toString(),
                Uri.fromFile(file(application.filesDir, "exports/0f0e/PowerLog-ride.fit")).toString(),
                "content://$authority/ride-exports/0f0e/missing.fit",
                "content://$authority/ride-exports/0f0e/..%2F..%2Fride.fit",
                "content://another.provider/ride-exports/0f0e/PowerLog-ride.fit",
                "content://$authority/exports/0f0e/PowerLog-ride.fit",
                "https://example.com/ride.fit",
            )) {
            val error = assertThrows(SinkException::class.java) { ExportSharing.shareable(application, uri) }
            assertEquals(uri, "invalid", error.code)
        }
    }
}
