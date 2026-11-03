package app.powerlog.core.telemetry

import app.powerlog.core.StreamStatus

object TelemetryTiming {
    const val telemetryTimeoutSeconds = 2.5
    const val replyWindowSeconds = 1.0
    const val silenceSeconds = 3.0
    // Android ends a link one second after its last app releases it (GATT_LINK_IDLE_TIMEOUT_WHEN_NO_APP in
    // https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/include/gatt_api.h);
    // a connection made sooner joins that link and fails with status 22.
    const val linkReleaseSeconds = 2.0
    const val discoverySeconds = 20.0
    const val attemptSeconds = 20.0
    const val cancellationGraceSeconds = 5.0
    const val displayHoldSeconds = StreamStatus.controllerStaleSeconds
    const val stableTelemetrySeconds = 30.0
    const val backgroundPreviewHz = 1.0

    fun pollIntervalSeconds(hz: Double, background: Boolean, recording: Boolean): Double =
        1 / if (background && !recording) minOf(hz, backgroundPreviewHz) else hz

    fun replyExpired(sentAt: Double, now: Double): Boolean = now - sentAt >= telemetryTimeoutSeconds

    fun silenceExpired(unansweredSince: Double, now: Double): Boolean = now - unansweredSince >= silenceSeconds

    fun responseDeadline(stage: TelemetryStage, sentAt: Double?, unansweredSince: Double?): Double? =
        when {
            sentAt == null -> null
            stage == TelemetryStage.ready ->
                minOf(sentAt + replyWindowSeconds, (unansweredSince ?: sentAt) + silenceSeconds)
            else -> sentAt + telemetryTimeoutSeconds
        }

    fun replyAdmissible(sentAt: Double, now: Double): Boolean = now >= sentAt && now - sentAt <= telemetryTimeoutSeconds

    fun writeExpired(blockedAt: Double, now: Double): Boolean = now - blockedAt >= telemetryTimeoutSeconds

    fun fragmentExpired(lastNotificationAt: Double, now: Double): Boolean =
        now - lastNotificationAt > telemetryTimeoutSeconds

    fun pollingDelay(
        now: Double,
        poweredOn: Boolean,
        verified: Boolean,
        scanning: Boolean,
        scanDeadline: Double?,
        reconnectDue: Double?,
        attemptDeadline: Double?,
        cancellationPending: Boolean,
        responseDeadline: Double?,
        writeDeadline: Double?,
        nextPoll: Double?,
        sampleDeadline: Double?,
    ): Double {
        if (!poweredOn) return 5.0
        val reconnect =
            if (cancellationPending && (reconnectDue ?: Double.POSITIVE_INFINITY) <= now) null else reconnectDue
        val deadline =
            listOfNotNull(
                    scanDeadline,
                    reconnect,
                    attemptDeadline,
                    responseDeadline,
                    writeDeadline,
                    nextPoll,
                    sampleDeadline?.takeIf { it > now },
                )
                .fold(now + if (verified || scanning) 0.25 else 5.0, ::minOf)
        return maxOf(0.005, deadline - now)
    }
}

data class TelemetryRetryPlan(
    val delaySeconds: Double,
    val adoptSystemReconnect: Boolean,
    val cancelBeforeRetry: Boolean,
)

object TelemetryReconnectPolicy {
    @Throws(IllegalArgumentException::class)
    fun plan(
        attempt: Int,
        activeRide: Boolean,
        callbackCompleted: Boolean,
        peerOrSystem: Boolean,
        stableTelemetrySeconds: Double?,
        systemIsReconnecting: Boolean,
        systemAttemptPending: Boolean,
        peripheralIsDisconnected: Boolean,
    ): TelemetryRetryPlan? {
        require(attempt >= 1)
        if (attempt > 5 && !activeRide) return null
        val immediate =
            attempt == 1 &&
                callbackCompleted &&
                peerOrSystem &&
                stableTelemetrySeconds != null &&
                stableTelemetrySeconds.isFinite() &&
                stableTelemetrySeconds >= TelemetryTiming.stableTelemetrySeconds
        val closesLink = !callbackCompleted && !peripheralIsDisconnected
        val delay =
            when {
                attempt > 5 -> 30.0
                immediate -> 0.0
                closesLink -> maxOf((1 shl (attempt - 1)).toDouble(), TelemetryTiming.linkReleaseSeconds)
                else -> (1 shl (attempt - 1)).toDouble()
            }
        val adopt = systemIsReconnecting && delay == 0.0
        val cancel = !adopt && (systemIsReconnecting || systemAttemptPending || closesLink)
        return TelemetryRetryPlan(delay, adopt, cancel)
    }

    fun restartsBackoff(telemetrySeconds: Double): Boolean = telemetrySeconds >= TelemetryTiming.silenceSeconds

    fun retriesDiscovery(platformError: Boolean, activeRide: Boolean, knownController: Boolean): Boolean =
        platformError || activeRide || knownController

    const val unverifiedIdentityAttempts = 2

    @Throws(IllegalArgumentException::class)
    fun retriesIdentity(attempt: Int, activeRide: Boolean, knownController: Boolean): Boolean {
        require(attempt >= 1)
        return activeRide || knownController || attempt < unverifiedIdentityAttempts
    }
}

data class TelemetryDiscovery(val generation: Long, val deadline: Double) {
    fun accepts(generation: Long, now: Double): Boolean = this.generation == generation && now < deadline

    fun expired(now: Double): Boolean = now >= deadline

    companion object {
        @Throws(IllegalArgumentException::class)
        fun begin(generation: Long, now: Double): TelemetryDiscovery {
            require(generation > 0 && now.isFinite())
            return TelemetryDiscovery(generation, now + TelemetryTiming.discoverySeconds)
        }
    }
}

enum class TelemetryStage {
    idle,
    connecting,
    services,
    characteristics,
    notifications,
    identity,
    ready,
}

enum class TelemetryTransportAction {
    none,
    advanced,
    beginRetry,
    resendRequest,
    responseTimeout,
    writeTimeout,
    attemptTimeout,
    cancellationTimeout,
    replyAccepted,
    replyExpired,
    replyRejected,
}

data class TelemetryTransportState(
    val generation: Long = 0,
    val stage: TelemetryStage = TelemetryStage.idle,
    val attemptDeadline: Double? = null,
    val systemInitiated: Boolean = false,
    val reconnectDue: Double? = null,
    val cancellationGeneration: Long? = null,
    val cancellationDeadline: Double? = null,
    val cancellationFailed: Boolean = false,
    val recoveryRequired: Boolean = false,
) {
    val cancellationPending: Boolean
        get() = cancellationGeneration != null
}

data class TelemetryTransportDecision(val next: TelemetryTransportState, val action: TelemetryTransportAction)

object TelemetryTransport {
    private fun successor(generation: Long): Long {
        require(generation < Long.MAX_VALUE)
        return generation + 1
    }

    @Throws(IllegalArgumentException::class)
    fun begin(state: TelemetryTransportState, now: Double, systemInitiated: Boolean = false): TelemetryTransportState? {
        require(now.isFinite())
        if (
            state.cancellationPending ||
                state.stage != TelemetryStage.idle ||
                state.cancellationFailed ||
                state.recoveryRequired ||
                (state.reconnectDue != null && now < state.reconnectDue)
        )
            return null
        return TelemetryTransportState(
            successor(state.generation),
            TelemetryStage.connecting,
            now + TelemetryTiming.attemptSeconds,
            systemInitiated,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun advance(
        state: TelemetryTransportState,
        generation: Long,
        expected: TelemetryStage,
        next: TelemetryStage,
        now: Double,
    ): TelemetryTransportDecision {
        require(now.isFinite())
        if (
            state.generation != generation ||
                state.cancellationPending ||
                state.stage != expected ||
                expected == TelemetryStage.idle ||
                expected == TelemetryStage.ready ||
                next.ordinal != expected.ordinal + 1
        )
            return TelemetryTransportDecision(state, TelemetryTransportAction.none)
        if (state.attemptDeadline != null && now >= state.attemptDeadline)
            return failed(state, TelemetryTransportAction.attemptTimeout)
        return TelemetryTransportDecision(
            state.copy(
                stage = next,
                systemInitiated = state.systemInitiated && expected != TelemetryStage.connecting,
                attemptDeadline = if (next == TelemetryStage.ready) null else state.attemptDeadline,
            ),
            TelemetryTransportAction.advanced,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun scheduleRetry(state: TelemetryTransportState, plan: TelemetryRetryPlan, now: Double): TelemetryTransportState {
        require(now.isFinite() && plan.delaySeconds.isFinite() && plan.delaySeconds >= 0)
        if (state.reconnectDue != null) return if (plan.cancelBeforeRetry) cancelDuringBackoff(state) else state
        val due = now + plan.delaySeconds
        return TelemetryTransportState(
            generation = successor(state.generation),
            reconnectDue = due,
            cancellationGeneration = state.cancellationGeneration ?: state.generation.takeIf { plan.cancelBeforeRetry },
            cancellationDeadline = due + TelemetryTiming.cancellationGraceSeconds,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun cancelDuringBackoff(state: TelemetryTransportState): TelemetryTransportState {
        require(state.reconnectDue != null)
        if (state.cancellationPending) return state
        return state.copy(
            generation = successor(state.generation),
            stage = TelemetryStage.idle,
            attemptDeadline = null,
            systemInitiated = false,
            cancellationGeneration = state.generation,
            cancellationDeadline = state.reconnectDue + TelemetryTiming.cancellationGraceSeconds,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun disconnected(state: TelemetryTransportState, generation: Long, now: Double): TelemetryTransportState {
        require(now.isFinite())
        if (state.cancellationPending) {
            if (state.cancellationGeneration != generation) return state
            val failed =
                state.cancellationFailed || (state.cancellationDeadline != null && now >= state.cancellationDeadline)
            return state.copy(
                cancellationGeneration = null,
                cancellationDeadline = null,
                cancellationFailed = failed,
                reconnectDue = if (failed) null else state.reconnectDue,
            )
        }
        if (generation != state.generation) return state
        return TelemetryTransportState(generation = successor(state.generation), reconnectDue = state.reconnectDue)
    }

    @Throws(IllegalArgumentException::class)
    fun manualDisconnect(state: TelemetryTransportState, cancellationRequired: Boolean): TelemetryTransportState =
        TelemetryTransportState(
            generation = successor(state.generation),
            cancellationGeneration = state.cancellationGeneration ?: state.generation.takeIf { cancellationRequired },
        )

    @Throws(IllegalArgumentException::class)
    fun radioUnavailable(state: TelemetryTransportState): TelemetryTransportState =
        TelemetryTransportState(generation = successor(state.generation))

    @Throws(IllegalArgumentException::class)
    fun reply(
        state: TelemetryTransportState,
        generation: Long,
        request: ControllerRequest,
        reply: ControllerReply,
        sentAt: Double,
        now: Double,
    ): TelemetryTransportDecision {
        require(now.isFinite() && sentAt.isFinite())
        val expected = if (request == ControllerRequest.identity) TelemetryStage.identity else TelemetryStage.ready
        if (
            state.generation != generation ||
                state.stage != expected ||
                state.cancellationPending ||
                state.reconnectDue != null ||
                reply.kind != request
        )
            return TelemetryTransportDecision(state, TelemetryTransportAction.none)
        if (state.attemptDeadline != null && now >= state.attemptDeadline)
            return failed(state, TelemetryTransportAction.attemptTimeout)
        if (!TelemetryTiming.replyAdmissible(sentAt, now)) return failed(state, TelemetryTransportAction.replyExpired)
        if (reply is ControllerRejectedReply) return failed(state, TelemetryTransportAction.replyRejected)
        return TelemetryTransportDecision(state, TelemetryTransportAction.replyAccepted)
    }

    @Throws(IllegalArgumentException::class)
    fun check(
        state: TelemetryTransportState,
        now: Double,
        responseSentAt: Double? = null,
        writeBlockedAt: Double? = null,
        nativeDisconnecting: Boolean = false,
        unansweredSince: Double? = null,
    ): TelemetryTransportDecision {
        require(now.isFinite())
        if (state.cancellationFailed) return TelemetryTransportDecision(state, TelemetryTransportAction.none)
        if (state.reconnectDue != null) {
            if (
                (state.cancellationPending || nativeDisconnecting) &&
                    state.cancellationDeadline != null &&
                    now >= state.cancellationDeadline
            )
                return TelemetryTransportDecision(
                    state.copy(reconnectDue = null, cancellationFailed = true),
                    TelemetryTransportAction.cancellationTimeout,
                )
            if (now >= state.reconnectDue && !state.cancellationPending && !nativeDisconnecting)
                return TelemetryTransportDecision(state, TelemetryTransportAction.beginRetry)
            return TelemetryTransportDecision(state, TelemetryTransportAction.none)
        }
        if (state.attemptDeadline != null && now >= state.attemptDeadline)
            return failed(state, TelemetryTransportAction.attemptTimeout)
        if (!state.cancellationPending && state.stage in listOf(TelemetryStage.identity, TelemetryStage.ready)) {
            if (responseSentAt != null) {
                val ready = state.stage == TelemetryStage.ready
                val silent =
                    if (ready) TelemetryTiming.silenceExpired(unansweredSince ?: responseSentAt, now)
                    else TelemetryTiming.replyExpired(responseSentAt, now)
                if (silent) return failed(state, TelemetryTransportAction.responseTimeout)
                if (ready && now - responseSentAt >= TelemetryTiming.replyWindowSeconds)
                    return TelemetryTransportDecision(state, TelemetryTransportAction.resendRequest)
            }
            if (writeBlockedAt != null && TelemetryTiming.writeExpired(writeBlockedAt, now))
                return failed(state, TelemetryTransportAction.writeTimeout)
        }
        return TelemetryTransportDecision(state, TelemetryTransportAction.none)
    }

    private fun failed(state: TelemetryTransportState, action: TelemetryTransportAction): TelemetryTransportDecision =
        TelemetryTransportDecision(
            state.copy(
                stage = TelemetryStage.idle,
                attemptDeadline = null,
                recoveryRequired = true,
            ),
            action,
        )
}
