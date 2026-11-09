package app.powerlog.runtime.host

import app.powerlog.application.Executors
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.HostEntries
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.port.Clock
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.runtime.diagnostics.Unexpected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class RuntimeHost(
    private val executors: Executors,
    private val entries: HostEntries,
    private val clock: Clock,
    private val log: DiagnosticLogRepository,
) : HostFacade {
    private val scope = CoroutineScope(SupervisorJob())

    override fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit) {
        scope.launch(executors.ride) {
            done(
                settled(RefusalDetail.unavailable) {
                    when (val result = entries.launch(context)) {
                        LaunchResult.Ready -> null
                        is LaunchResult.Refused -> result.detail
                    }
                }
            )
        }
    }

    override fun foregroundChanged(foreground: Boolean) {
        scope.launch(executors.ride) { settled(Unit) { entries.foregroundChanged(foreground) } }
    }

    override fun didEnterBackground(done: () -> Unit) {
        scope.launch(executors.ride) {
            settled(Unit) { entries.didEnterBackground() }
            done()
        }
    }

    override fun willTerminate(timeoutUs: Long): Boolean {
        val work = scope.launch(executors.ride) { settled(Unit) { entries.willTerminate() } }
        return awaitBlocking(work, timeoutUs)
    }

    override fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit) {
        scope.launch(executors.ride) {
            done(settled(unknown) { (entries.rideCardIntent(intent) as? Outcome.Failed)?.failure })
        }
    }

    override fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit) {
        scope.launch(executors.health) {
            done(settled(HealthWorkResult.retry) { entries.runHealthWork(rideId, attempt) })
        }
    }

    private suspend fun <T> settled(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.append(Unexpected.line("host", error, clock.read()))
            fallback
        }

    private companion object {
        val unknown = ApiFailure(FailureCode.unknown, FailureCode.unknown.retryable, null, null)
    }
}
