package app.powerlog.core.telemetry

class TelemetryNotificationDecision(
    val action: TelemetryTransportAction,
    replies: List<ControllerReply>,
    val discardedBytes: Long,
    val pendingBytes: Int,
) {
    private val admitted = replies.toList()
    val replies: List<ControllerReply>
        get() = admitted.toList()
}

class TelemetrySession(private val protocol: ControllerProtocol) {
    var state = TelemetryTransportState()
        private set

    var identity: ControllerIdentity? = null
        private set

    private var lastNotificationAt: Double? = null

    private data class CompletedRequest(val generation: Long, val kind: ControllerRequest, val sentAt: Double)

    private var completedRequest: CompletedRequest? = null

    var discardedBytes: Long = 0
        private set

    var pendingBytes: Int = 0
        private set

    fun request(kind: ControllerRequest): ByteArray = protocol.request(kind)

    @Throws(IllegalArgumentException::class)
    fun begin(now: Double, systemInitiated: Boolean): Boolean {
        update(TelemetryTransport.begin(state, now, systemInitiated) ?: return false)
        return true
    }

    @Throws(IllegalArgumentException::class)
    fun advance(
        generation: Long,
        expected: TelemetryStage,
        next: TelemetryStage,
        now: Double,
    ): TelemetryTransportAction {
        val decision = TelemetryTransport.advance(state, generation, expected, next, now)
        update(decision.next)
        return decision.action
    }

    @Throws(IllegalArgumentException::class)
    fun check(
        now: Double,
        responseSentAt: Double?,
        writeBlockedAt: Double?,
        nativeDisconnecting: Boolean = false,
    ): TelemetryTransportAction {
        val decision = TelemetryTransport.check(state, now, responseSentAt, writeBlockedAt, nativeDisconnecting)
        update(decision.next)
        return decision.action
    }

    @Throws(IllegalArgumentException::class)
    fun scheduleRetry(plan: TelemetryRetryPlan, now: Double) {
        update(TelemetryTransport.scheduleRetry(state, plan, now))
    }

    @Throws(IllegalArgumentException::class)
    fun cancelDuringBackoff() {
        update(TelemetryTransport.cancelDuringBackoff(state))
    }

    @Throws(IllegalArgumentException::class)
    fun disconnected(generation: Long, now: Double) {
        update(TelemetryTransport.disconnected(state, generation, now))
    }

    @Throws(IllegalArgumentException::class)
    fun manualDisconnect(cancellationRequired: Boolean) {
        update(TelemetryTransport.manualDisconnect(state, cancellationRequired))
    }

    @Throws(IllegalArgumentException::class)
    fun radioUnavailable() {
        update(TelemetryTransport.radioUnavailable(state))
    }

    @Throws(IllegalArgumentException::class)
    fun notifications(
        generation: Long,
        request: ControllerRequest,
        notification: ByteArray,
        sentAt: Double,
        now: Double,
    ): TelemetryNotificationDecision {
        require(sentAt.isFinite() && now.isFinite())
        val expected = if (request == ControllerRequest.identity) TelemetryStage.identity else TelemetryStage.ready
        if (
            state.generation != generation ||
                state.stage != expected ||
                state.cancellationPending ||
                state.reconnectDue != null
        )
            return notificationDecision(TelemetryTransportAction.none)
        if (state.attemptDeadline != null && now >= state.attemptDeadline!!)
            return notificationDecision(check(now, null, null))
        if (lastNotificationAt?.let { TelemetryTiming.fragmentExpired(it, now) } == true)
            retainDiagnostics(protocol.reset())
        lastNotificationAt = now
        val decoded = protocol.feed(notification, identity)
        retainDiagnostics(decoded)
        if (completedRequest?.let { it.generation == generation && it.kind == request && sentAt <= it.sentAt } == true)
            return notificationDecision(TelemetryTransportAction.none)
        for (reply in decoded.replies) {
            val decision = TelemetryTransport.reply(state, generation, request, reply, sentAt, now)
            if (decision.action == TelemetryTransportAction.none) continue
            completedRequest = CompletedRequest(generation, request, sentAt)
            update(decision.next)
            if (decision.action == TelemetryTransportAction.replyAccepted) {
                if (reply is ControllerIdentityReply) identity = reply.identity
            }
            val replies =
                if (
                    decision.action in
                        listOf(TelemetryTransportAction.replyAccepted, TelemetryTransportAction.replyRejected)
                )
                    listOf(reply)
                else emptyList()
            return notificationDecision(decision.action, replies)
        }
        return notificationDecision(TelemetryTransportAction.none)
    }

    private fun retainDiagnostics(result: ControllerDecodeResult) {
        discardedBytes = result.discardedBytes
        pendingBytes = result.pendingBytes
    }

    private fun notificationDecision(
        action: TelemetryTransportAction,
        replies: List<ControllerReply> = emptyList(),
    ): TelemetryNotificationDecision = TelemetryNotificationDecision(action, replies, discardedBytes, pendingBytes)

    private fun update(next: TelemetryTransportState) {
        if (next.generation != state.generation || next.stage == TelemetryStage.idle) {
            retainDiagnostics(protocol.reset())
            lastNotificationAt = null
            completedRequest = null
            identity = null
        }
        state = next
    }
}
