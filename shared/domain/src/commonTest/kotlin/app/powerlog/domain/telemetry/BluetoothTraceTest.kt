package app.powerlog.domain.telemetry

import app.powerlog.domain.protocol.*
import kotlin.test.*

class BluetoothTraceTest {
    @Test
    fun rawCaptureIsExplicitAndRetainsFragmentBoundariesAfterRejection() {
        val trace = BluetoothTrace()
        trace.record(1.0, "notification", "disabled", byteArrayOf(1))
        assertEquals(0, trace.status(1.0).eventCount)
        trace.start(10.0)
        trace.record(10.1, "notification", "identity", byteArrayOf(2, 5, 111, -128))
        trace.record(10.2, "notification", "identity", byteArrayOf(0, -1))
        trace.record(10.3, "rejected", "identity", null)
        trace.stop()
        trace.record(10.4, "notification", "disabled again", byteArrayOf(3))
        val report = JsonCodec.parse(trace.exportJson(10.4)).obj()
        val events = report.required("events").array().map { it.obj() }
        assertEquals(3, events.size)
        assertEquals("02056f80", events[0].required("hex").string())
        assertEquals("00ff", events[1].required("hex").string())
        assertEquals("rejected", events[2].required("event").string())
        assertEquals("manual", trace.status(10.4).stopReason)
        trace.clear()
        assertFalse(trace.exportJson(10.5).contains("02056f80"))
        assertNull(trace.status(10.5).lastEvent)
    }

    @Test
    fun expiresWithoutANotificationAndRequiresAnotherExplicitStart() {
        val trace = BluetoothTrace()
        trace.start(0.0)
        trace.record(119.99, "write", "identity", byteArrayOf(111))
        assertTrue(trace.status(119.99).capturing)
        assertFalse(trace.status(120.0).capturing)
        trace.record(121.0, "notification", "too late", byteArrayOf(1))
        assertEquals(1, trace.status(121.0).eventCount)
        assertEquals("timeLimit", trace.status(121.0).stopReason)
        trace.start(122.0)
        assertEquals(0, trace.status(122.0).eventCount)
        assertTrue(trace.status(122.0).capturing)
    }

    @Test
    fun boundedCapturePreservesHandshakeAndDisclosesTruncation() {
        val trace = BluetoothTrace()
        trace.start(0.0)
        val oversized = ByteArray(65536) { -1 }
        trace.record(1.0, "notification", "first \"reply\"\n", oversized)
        repeat(4001) { trace.record(1.0, "notification", "later", oversized) }
        val state = trace.status(1.0)
        assertFalse(state.capturing)
        assertEquals("sizeLimit", state.stopReason)
        assertTrue(state.byteCount <= 1024 * 1024)
        val report = JsonCodec.parse(trace.exportJson(1.0), byteLimit = 2 * 1024 * 1024).obj()
        val first = report.required("events").array().first().obj()
        assertEquals("first \"reply\"\n", first.required("detail").string())
        assertEquals(65536.0, first.required("byteLength").number())
        assertEquals(4096.0, first.required("capturedBytes").number())
        assertEquals("ff".repeat(4096), first.required("hex").string())
    }
}
