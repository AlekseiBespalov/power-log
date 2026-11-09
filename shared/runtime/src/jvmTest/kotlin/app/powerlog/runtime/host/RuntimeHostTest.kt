package app.powerlog.runtime.host

import app.powerlog.application.Executors
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.HostEntries
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.recording.RideCardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RuntimeHostTest {
    private val lines = mutableListOf<LogLine>()
    private val log =
        object : DiagnosticLogRepository {
            override suspend fun append(line: LogLine) {
                lines += line
            }

            override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> = error("not read")
        }

    @Test
    fun aThrowingEntryAnswersItsFallbackAndAppendsAnUnexpectedHostLine() = runTest {
        val host = host(throwing)
        val answers = mutableListOf<Any?>()
        host.launch(LaunchContext(LaunchReason.userInterface)) { answers += it }
        host.foregroundChanged(true)
        host.didEnterBackground { answers += "background done" }
        host.rideCardIntent(RideCardIntent("ride", "token", RideCardState.running, RideAction.pause)) { answers += it }
        host.runHealthWork("ride", 0) { answers += it }
        advanceUntilIdle()

        val unknown = ApiFailure(FailureCode.unknown, FailureCode.unknown.retryable, null, null)
        assertEquals(
            listOf<Any?>(RefusalDetail.unavailable, "background done", unknown, HealthWorkResult.retry),
            answers,
        )
        assertEquals(5, lines.size)
        for (line in lines) {
            assertEquals("unexpected", line.event)
            assertEquals(LogValue.Text("host"), line.field("component"))
            assertEquals(LogValue.Text("IllegalStateException"), line.field("exception"))
        }
    }

    @Test
    fun aCallbackThatThrowsIsNotSwallowed() {
        val escaped =
            assertFailsWith<IllegalStateException> {
                runTest {
                    host(ready).launch(LaunchContext(LaunchReason.userInterface)) { error("callback") }
                    advanceUntilIdle()
                }
            }
        assertEquals("callback", escaped.message)
        assertEquals(emptyList<LogLine>(), lines)
    }

    private fun TestScope.host(entries: HostEntries): RuntimeHost {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RuntimeHost(Executors(dispatcher, dispatcher, dispatcher, dispatcher), entries, FakeClock(), log)
    }

    private fun LogLine.field(key: String): LogValue? = fields.firstOrNull { it.key == key }?.value

    private companion object {
        val throwing =
            object : HostEntries {
                override suspend fun launch(context: LaunchContext): LaunchResult = error("launch")

                override suspend fun foregroundChanged(foreground: Boolean): Unit = error("foreground")

                override suspend fun didEnterBackground(): Unit = error("background")

                override suspend fun willTerminate(): Unit = error("terminate")

                override suspend fun rideCardIntent(intent: RideCardIntent): Outcome<Unit> = error("card")

                override suspend fun runHealthWork(rideId: String, attempt: Int): HealthWorkResult = error("health")
            }

        val ready =
            object : HostEntries {
                override suspend fun launch(context: LaunchContext): LaunchResult = LaunchResult.Ready

                override suspend fun foregroundChanged(foreground: Boolean) = Unit

                override suspend fun didEnterBackground() = Unit

                override suspend fun willTerminate() = Unit

                override suspend fun rideCardIntent(intent: RideCardIntent): Outcome<Unit> = Outcome.Ok(Unit)

                override suspend fun runHealthWork(rideId: String, attempt: Int): HealthWorkResult =
                    HealthWorkResult.success
            }
    }
}
