package app.powerlog.bridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BluetoothPollingTest {
    @Test
    fun captureWorkDoesNotAddAnExtraDelayToEveryPoll() {
        val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val identity = CycProtocol.identity(bytes(fixture.getJSONObject("identity").getString("payloadHex")))
        val frame = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex"))
        val handler = Handler(Looper.getMainLooper())
        for (hz in listOf(2, 4, 8)) for (storageMillis in listOf(0L, 80L, 600L)) {
            val bluetooth = CycBluetooth(RuntimeEnvironment.getApplication(), handler, { _, _ -> },
                { _, _, _ -> ShadowSystemClock.advanceBy(Duration.ofMillis(storageMillis)) }, { true })
            bluetooth.setHz(hz)
            val sentAt = SystemClock.elapsedRealtime()
            fun field(name: String, value: Any) {
                CycBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)
            }
            field("pending", CycProtocol.Read.TELEMETRY)
            field("pendingSince", sentAt)
            field("nextPollAt", nextTelemetryPollMillis(0, sentAt, hz))
            field("identity", identity)
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            CycBluetooth::class.java.getDeclaredMethod("receive", ByteArray::class.java)
                .apply { isAccessible = true }.invoke(bluetooth, frame)
            assertEquals(1L, bluetooth.sampleCount)
            assertEquals(maxOf(sentAt + 1000 / hz, SystemClock.uptimeMillis()),
                shadowOf(handler.looper).nextScheduledTaskTime.toMillis())
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun shortTimerDelaysDoNotAccumulateAndLongStallsDoNotQueueCatchupRequests() {
        for (hz in listOf(2, 4, 8)) {
            val origin = 1000L
            val period = 1000L / hz
            var due = nextTelemetryPollMillis(0, origin, hz)
            repeat(60 * hz) { index ->
                val timerDelay = if (index % 7 == 0) 80L else 10L
                due = nextTelemetryPollMillis(due, due + timerDelay, hz)
            }
            assertEquals(origin + 60000 + period, due)
            val resumedAt = due + 2000
            assertEquals(resumedAt + period, nextTelemetryPollMillis(due, resumedAt, hz))
            assertEquals(resumedAt + period, nextTelemetryPollMillis(0, resumedAt, hz))
        }
    }
}
