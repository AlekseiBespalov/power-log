package app.powerlog.core.analytics

sealed class PageAdmission {
    data object Accepted : PageAdmission()

    data object Complete : PageAdmission()

    data object Cancelled : PageAdmission()

    data class Rejected(val outcome: PageOutcome) : PageAdmission()
}

class AnalysisPageSession(
    val pin: AnalysisPin,
    val pass: AnalysisPass,
    val stream: AnalysisStream,
    val cancellationId: String,
    private val limit: Int = 256,
    private val rangeUs: AnalysisRange? = null,
    private val metrics: List<String> = emptyList(),
) {
    private var cursor: AnalysisCursor? = null
    private var cancelled = false
    private var terminal = false
    private var inFlight = false
    private var successful = false
    var pinHeld = true
        private set

    var slotHeld = true
        private set

    var uiSettled = false
        private set

    fun request(): AnalysisPageRequest? {
        if (cancelled || terminal || inFlight) return null
        inFlight = true
        return AnalysisPageRequest(pin, pass, stream, cancellationId, cursor, limit, rangeUs, metrics)
    }

    fun cancel() {
        cancelled = true
        uiSettled = true
    }

    fun admit(outcome: PageOutcome): PageAdmission {
        check(inFlight)
        inFlight = false
        if (cancelled) return PageAdmission.Cancelled
        if (outcome !is PageOutcome.Page) {
            terminal = true
            return PageAdmission.Rejected(outcome)
        }
        val request = AnalysisPageRequest(pin, pass, stream, cancellationId, cursor, limit, rangeUs, metrics)
        if (!outcome.page.validFor(request)) {
            terminal = true
            return PageAdmission.Rejected(PageOutcome.Invalid("Page does not match its request"))
        }
        cursor = outcome.page.nextCursor
        terminal = outcome.page.done
        successful = terminal
        return if (terminal) PageAdmission.Complete else PageAdmission.Accepted
    }

    fun mayPublish(current: AnalysisPin): Boolean =
        successful && !cancelled && !inFlight && pinHeld && CacheIdentity.mayPublish(pin, current)

    fun drain() {
        check(!inFlight)
        pinHeld = false
        slotHeld = false
        uiSettled = true
    }
}
