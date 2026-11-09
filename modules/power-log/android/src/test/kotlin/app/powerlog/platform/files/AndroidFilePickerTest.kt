package app.powerlog.platform.files

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import app.powerlog.application.port.PickedFile
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.platform.CountingExecutor
import app.powerlog.platform.R
import app.powerlog.platform.ui.AndroidUiContext
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidFilePickerTest {
    private lateinit var application: Application
    private lateinit var authority: String
    private val ui = FakeUi()

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        val registered = registerFileProvider(application)
        authority = registered
        Robolectric.buildContentProvider(FileProvider::class.java)
            .create(
                ProviderInfo().apply {
                    this.authority = registered
                    grantUriPermissions = true
                    exported = false
                    name = FileProvider::class.java.name
                    packageName = application.packageName
                    metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.power_log_files) }
                }
            )
    }

    @After
    fun tearDown() {
        File(application.filesDir, "exports").deleteRecursively()
    }

    private fun picker() = AndroidFilePicker(application, ui, Handler(Looper.getMainLooper()), CountingExecutor())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Answer<T> {
        var value: T? = null
        var error: PortError? = null
        val reply = Reply<T>({ value = it }, { error = it })
    }

    private fun picked(): Answer<PickedFile> {
        val answer = Answer<PickedFile>()
        picker().pickCsv(answer.reply)
        idle()
        return answer
    }

    private fun PickedFile.readAll(maxBytes: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val answer = Answer<ByteArray?>()
            read(maxBytes, answer.reply)
            idle()
            assertNull(answer.error)
            chunks += answer.value ?: return chunks
        }
    }

    @Test
    fun aPickedCsvGivesItsNameSizeAndChunksToTheEnd() {
        val csv = "elapsedSeconds,humanPowerW\n" + (1..500).joinToString("") { "$it,${it % 300}\n" }
        val file =
            File(application.filesDir, "exports/picked/ride.csv").apply {
                parentFile!!.mkdirs()
                writeText(csv)
            }
        ui.answer = { Activity.RESULT_OK to Intent().setData(FileProvider.getUriForFile(application, authority, file)) }
        val answer = picked()
        val chosen = ui.started.single()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, chosen.action)
        assertEquals(
            listOf("text/csv", "text/comma-separated-values"),
            chosen.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList(),
        )
        val pickedFile = requireNotNull(answer.value)
        assertEquals("ride.csv", pickedFile.name)
        assertEquals(csv.length.toLong(), pickedFile.sizeBytes)
        val chunks = pickedFile.readAll(1000)
        assertTrue(chunks.all { it.size <= 1000 })
        assertArrayEquals(csv.toByteArray(), chunks.reduce(ByteArray::plus))
        pickedFile.close()
    }

    @Test
    fun aCancelledPickIsCancelled() {
        ui.answer = { Activity.RESULT_CANCELED to null }
        assertEquals(PortErrorKind.cancelled, picked().error?.kind)
    }

    @Test
    fun aFileThatCannotBeOpenedIsUnreadable() {
        val gone = File(application.filesDir, "exports/picked/gone.csv")
        gone.parentFile!!.mkdirs()
        gone.writeText("x")
        val uri = FileProvider.getUriForFile(application, authority, gone)
        gone.delete()
        ui.answer = { Activity.RESULT_OK to Intent().setData(uri) }
        assertEquals(PortErrorKind.unreadable, picked().error?.kind)
    }

    @Test
    fun withoutAScreenThePickIsUiUnavailable() {
        ui.available = false
        assertEquals(PortErrorKind.uiUnavailable, picked().error?.kind)
    }

    private class FakeUi : AndroidUiContext {
        var available = true
        var answer: () -> Pair<Int, Intent?> = { Activity.RESULT_CANCELED to null }
        val started = mutableListOf<Intent>()

        override fun activity(): Activity? = null

        override fun requestPermissions(permissions: List<String>, done: (Map<String, Boolean>) -> Unit) = false

        override fun startForResult(intent: Intent, done: (resultCode: Int?, data: Intent?) -> Unit) {
            if (!available) return done(null, null)
            started += intent
            val (code, data) = answer()
            done(code, data)
        }
    }
}
