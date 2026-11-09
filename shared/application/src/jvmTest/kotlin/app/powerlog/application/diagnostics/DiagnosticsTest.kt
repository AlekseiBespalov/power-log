package app.powerlog.application.diagnostics

import app.powerlog.application.Deadlines
import app.powerlog.application.Executors
import app.powerlog.application.connection.ConnectionSnapshot
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.domain.connection.ConnectionState
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.diagnostics.TraceState
import app.powerlog.domain.diagnostics.TraceStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DiagnosticsTest {
    @Test
    fun aTraceStopsBeforeTheEventThatWouldPassItsEventLimit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeClock()
        val diagnostics =
            Diagnostics(
                backgroundScope,
                Executors(dispatcher, dispatcher, dispatcher, dispatcher),
                clock,
                FakeDeviceInfo(FakeDeviceInfo.snapshot(Platform.android)),
                null,
                Deadlines(clock),
            )
        diagnostics.reports = {
            Diagnostics.Reports(
                ConnectionSnapshot(ConnectionState.idle, null, null, false, 0, 0, null, null, null),
                null,
                null,
                CycDecoder.traceContract,
            )
        }
        diagnostics.start()
        repeat(4_010) {
            diagnostics.record("notification", "", byteArrayOf(1), clock.read())
        }
        val trace = diagnostics.stream.value.trace
        assertEquals(TraceState.stopped, trace.state)
        assertEquals(TraceStopReason.sizeLimit, trace.stopReason)
        assertEquals(4_000, trace.events)
    }
}
