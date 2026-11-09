package app.powerlog.runtime.diagnostics

import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.store.LogValue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDiagnosticsTest {
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val directory = Files.createTempDirectory("crash").toFile()

    @AfterTest
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        directory.deleteRecursively()
    }

    @Test
    fun anUncaughtExceptionLeavesOneLineBeforeThePreviousHandlerRuns() {
        val handled = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handled += error }
        val pending = directory.resolve("diagnostics")
        AndroidDiagnostics.install({ pending }, FakeClock(monoUs = 9))
        val error = IllegalStateException("not logged")

        Thread({ throw error }, "PowerLogRide").apply { start() }.join()

        assertEquals(listOf<Throwable>(error), handled)
        val line = AndroidDiagnostics.lines { pending }.take().single()
        val fields = line.fields.associate { it.key to it.value }
        assertEquals("crash", line.event)
        assertEquals(LogValue.Text("ride"), fields["thread"])
        assertEquals(LogValue.Text("IllegalStateException"), fields["exception"])
        val frames = (fields.getValue("frames") as LogValue.Texts).values
        assertTrue(frames.first().matches(Regex("""[\w.$]+\.[\w$<>]+\([\w.]+:\d+\)""")), frames.first())
        assertTrue(!pending.resolve(CRASH_FILE).exists())
    }
}
