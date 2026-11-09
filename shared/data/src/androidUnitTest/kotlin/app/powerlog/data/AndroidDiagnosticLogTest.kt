package app.powerlog.data

import android.content.ContextWrapper
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidDiagnosticLogTest {
    @Test
    fun theLogWritesIntoTheNoBackupDiagnosticsFolder() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val executors = AndroidSqlPlatform.executors()
        try {
            AndroidStores.diagnosticLog(context, executors.store)
                .append(LogLine(LogLevel.info, 1, 2, "launch", emptyList()))
            assertTrue(File(context.noBackupFilesDir, "diagnostics/events.jsonl").length() > 0)
        } finally {
            executors.close()
        }
    }

    @Test
    fun theLogResolvesItsFolderOnlyWhenItWrites() = runTest {
        var resolved = 0
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getNoBackupFilesDir(): File = super.getNoBackupFilesDir().also { resolved++ }
            }
        val executors = AndroidSqlPlatform.executors()
        try {
            val log = AndroidStores.diagnosticLog(context, executors.store)
            assertEquals(0, resolved)
            log.append(LogLine(LogLevel.info, 1, 2, "launch", emptyList()))
            assertTrue(resolved > 0)
            assertTrue(File(context.noBackupFilesDir, "diagnostics/events.jsonl").isFile)
        } finally {
            executors.close()
        }
    }
}
