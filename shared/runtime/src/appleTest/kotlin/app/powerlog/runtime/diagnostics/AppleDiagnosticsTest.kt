@file:OptIn(ExperimentalForeignApi::class)

package app.powerlog.runtime.diagnostics

import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.store.LogValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

class AppleDiagnosticsTest {
    @Test
    fun aMirroredLineReachesNSLogAsAnObjectEvenWithFormatCharacters() {
        AppleDiagnostics.osLog("""{"v":2,"event":"launch","note":"100% %@ %s"}""")
    }

    @Test
    fun aCrashOnAnExecutorLeavesOneLineThatTheNextLaunchTakes() {
        val directory = NSTemporaryDirectory() + "crash-" + NSUUID().UUIDString + "/diagnostics"
        try {
            val ride = dispatch_queue_create("app.powerlog.ride", null)
            dispatch_sync(ride) { AppleDiagnostics.record(directory, IllegalStateException(), FakeClock(monoUs = 7)) }
            AppleDiagnostics.record(directory, IllegalArgumentException(), FakeClock(monoUs = 8))

            val lines = AppleDiagnostics.lines(directory).take()
            assertEquals(listOf("crash", "crash"), lines.map { it.event })
            val fields = lines.map { line -> line.fields.associate { it.key to it.value } }
            assertEquals(listOf("ride", "other"), fields.map { (it.getValue("thread") as LogValue.Text).value })
            val first = fields.first()
            assertEquals(LogValue.Text("IllegalStateException"), first["exception"])
            assertTrue((first.getValue("image") as LogValue.Text).value.matches(Regex("[0-9A-F-]{36}")))
            assertTrue((first.getValue("load") as LogValue.Text).value.startsWith("0x"))
            assertTrue((first.getValue("frames") as LogValue.Texts).values.all { it.startsWith("0x") })
            assertEquals(emptyList(), AppleDiagnostics.lines(directory).take())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory.substringBefore("/diagnostics"), null)
        }
    }
}
