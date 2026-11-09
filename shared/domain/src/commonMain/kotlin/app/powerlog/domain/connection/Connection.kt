package app.powerlog.domain.connection

import app.powerlog.domain.core.controller.ControllerDecoder
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerPolicy
import app.powerlog.domain.core.controller.ControllerReply
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.DecodeResult
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ride.SampleRate
import kotlinx.serialization.Serializable

@Serializable
public enum class ConnectionState {
    unavailable,
    idle,
    searching,
    connecting,
    connected,
    reconnecting,
}

@Serializable
public enum class ConnectionDisplay {
    live,
    held,
    unavailable,
}

public class ControllerLink(
    private val decoder: ControllerDecoder,
    private val known: Boolean,
    private val plansLinkLimit: Boolean,
    remembered: Boolean = false,
) {
    private var attemptDeadlineMonoUs: Long? = null
    private var linkUpMonoUs: Long? = null
    private var verified = false
    private var unknownIdentityFailures = 0
    private var waiting: ControllerRequest? = null
    private var inFlight: ControllerRequest? = null
    private var sentMonoUs: Long? = null
    private var unansweredSinceMonoUs: Long? = null
    private var blockedSinceMonoUs: Long? = null
    private var nextPollMonoUs: Long? = null
    private var lastNotificationMonoUs: Long? = null
    private var firstReadingMonoUs: Long? = null
    private var lastReadingMonoUs: Long? = null
    private var pace: Triple<SampleRate, Boolean, Boolean>? = null
    private var retries = if (remembered) 1 else 0
    private var radioAvailable = true
    private var wanted = true
    private var cancelGraceMonoUs: Long? = null
    private var awaitingRelease: LinkAction.Reconnect? = null
    private val linkAges = ArrayDeque<Long>()
    private var planning = true

    public fun attempt(nowMonoUs: Long): Long {
        endLink()
        val deadline = nowMonoUs + ControllerPolicy.connectUs
        attemptDeadlineMonoUs = deadline
        return deadline
    }

    public fun linkUp(nowMonoUs: Long) {
        linkUpMonoUs = nowMonoUs
    }

    public fun linkReady(result: GattResult, platformError: Boolean, nowMonoUs: Long, activeRide: Boolean): LinkAction =
        when {
            result == GattResult.ready -> send(ControllerRequest.identity, resent = false)
            result == GattResult.notifyFailed || platformError || activeRide || known ->
                lost(nowMonoUs, activeRide, appEnds = true, peerOrSystem = false, systemReconnecting = false)
            else -> giveUp(FailureCode.controllerUnsupported)
        }

    public fun next(nowMonoUs: Long, rate: SampleRate, background: Boolean, recording: Boolean): LinkAction {
        val interval = pollIntervalUs(rate, background, recording)
        if (pace != null && pace != Triple(rate, background, recording) && nextPollMonoUs != null) {
            val base = sentMonoUs ?: lastReadingMonoUs ?: nowMonoUs
            nextPollMonoUs = maxOf(nowMonoUs, base + interval)
        }
        pace = Triple(rate, background, recording)
        if (!verified || awaitingRelease != null) return wait()
        val planned = plannedAgeUs()
        val linkUp = linkUpMonoUs
        if (
            recording &&
                planned != null &&
                linkUp != null &&
                lastReadingMonoUs == nowMonoUs &&
                nowMonoUs - linkUp >= planned
        )
            return awaitRelease(LinkAction.Reconnect(nowMonoUs, adopt = false), nowMonoUs)
        if (inFlight != null || waiting != null) return wait()
        val due = nextPollMonoUs ?: nowMonoUs
        return if (nowMonoUs >= due) send(ControllerRequest.telemetry, resent = false) else LinkAction.Wait(due)
    }

    public fun accepted(nowMonoUs: Long): Long {
        val request = checkNotNull(waiting) { "a request was sent before the OS accepted it" }
        waiting = null
        inFlight = request
        blockedSinceMonoUs = null
        sentMonoUs = nowMonoUs
        if (request == ControllerRequest.telemetry) {
            if (unansweredSinceMonoUs == null) unansweredSinceMonoUs = nowMonoUs
            pace?.let { (rate, background, recording) ->
                nextPollMonoUs = nowMonoUs + pollIntervalUs(rate, background, recording)
            }
        }
        return requireNotNull(replyDeadlineUs())
    }

    public fun blocked(nowMonoUs: Long): Long {
        checkNotNull(waiting) { "a request was sent before the OS held it back" }
        val since = blockedSinceMonoUs ?: nowMonoUs
        blockedSinceMonoUs = since
        return since + ControllerPolicy.replyUs
    }

    public fun notified(nowMonoUs: Long): Boolean {
        val expired = lastNotificationMonoUs?.let { nowMonoUs - it > ControllerPolicy.replyUs } == true
        lastNotificationMonoUs = nowMonoUs
        return expired
    }

    public fun replies(result: DecodeResult, nowMonoUs: Long): List<ControllerReply> {
        val request = inFlight ?: return emptyList()
        val reply =
            result.replies.firstOrNull {
                when (it) {
                    is ControllerReply.Identity -> request == ControllerRequest.identity
                    is ControllerReply.Reading -> request == ControllerRequest.telemetry && verified
                    is ControllerReply.Refused -> it.kind == request
                }
            } ?: return emptyList()
        inFlight = null
        sentMonoUs = null
        unansweredSinceMonoUs = null
        when (reply) {
            is ControllerReply.Identity -> {
                verified = true
                attemptDeadlineMonoUs = null
                unknownIdentityFailures = 0
            }
            is ControllerReply.Reading -> {
                val first = firstReadingMonoUs ?: nowMonoUs
                firstReadingMonoUs = first
                lastReadingMonoUs = nowMonoUs
                if (nowMonoUs - first >= ControllerPolicy.silenceUs) retries = 0
            }
            is ControllerReply.Refused -> Unit
        }
        return listOf(reply)
    }

    public fun timedOut(nowMonoUs: Long, activeRide: Boolean): LinkAction {
        cancelGraceMonoUs?.let {
            if (nowMonoUs >= it) {
                cancelGraceMonoUs = null
                return LinkAction.Release
            }
        }
        if (awaitingRelease != null) return wait()
        val attempt = attemptDeadlineMonoUs
        if (attempt != null && nowMonoUs >= attempt)
            return lost(nowMonoUs, activeRide, appEnds = true, peerOrSystem = false, systemReconnecting = false)
        val sent = sentMonoUs
        when (inFlight) {
            ControllerRequest.identity ->
                if (sent != null && nowMonoUs >= sent + ControllerPolicy.replyUs)
                    return identityFailed(nowMonoUs, activeRide)
            ControllerRequest.telemetry -> {
                val unanswered = requireNotNull(unansweredSinceMonoUs)
                if (nowMonoUs >= unanswered + ControllerPolicy.silenceUs)
                    return lost(nowMonoUs, activeRide, appEnds = true, peerOrSystem = false, systemReconnecting = false)
                if (sent != null && nowMonoUs >= sent + ControllerPolicy.resendUs) {
                    inFlight = null
                    sentMonoUs = null
                    return send(ControllerRequest.telemetry, resent = true)
                }
            }
            null -> Unit
        }
        val blocked = blockedSinceMonoUs
        if (blocked != null && nowMonoUs >= blocked + ControllerPolicy.replyUs)
            return lost(nowMonoUs, activeRide, appEnds = true, peerOrSystem = false, systemReconnecting = false)
        return wait()
    }

    public fun unreleased(nowMonoUs: Long, activeRide: Boolean): LinkAction? {
        if (activeRide) return null
        return giveUp(FailureCode.bikeUnavailable)
    }

    public fun disconnected(
        nowMonoUs: Long,
        peerOrSystem: Boolean,
        activeRide: Boolean,
        systemReconnecting: Boolean,
    ): LinkAction {
        val linkUp = linkUpMonoUs
        if (peerOrSystem && plansLinkLimit && linkUp != null && firstReadingMonoUs != null) {
            val age = nowMonoUs - linkUp
            plannedAgeUs()?.let { if (age < it) planning = false }
            linkAges.addLast(age)
            if (linkAges.size > ControllerPolicy.linkLimitDrops) linkAges.removeFirst()
        }
        return lost(nowMonoUs, activeRide, appEnds = false, peerOrSystem, systemReconnecting)
    }

    public fun released(nowMonoUs: Long): LinkAction? {
        cancelGraceMonoUs = null
        val reconnect = awaitingRelease
        awaitingRelease = null
        return reconnect
    }

    public fun radio(available: Boolean, nowMonoUs: Long): LinkAction? {
        radioAvailable = available
        if (!available) {
            endLink()
            return null
        }
        return if (wanted) LinkAction.Reconnect(nowMonoUs, adopt = false) else null
    }

    private fun send(request: ControllerRequest, resent: Boolean): LinkAction {
        waiting = request
        return LinkAction.Send(decoder.request(request), resent)
    }

    private fun identityFailed(nowMonoUs: Long, activeRide: Boolean): LinkAction {
        if (!known && !activeRide && ++unknownIdentityFailures >= ControllerPolicy.unknownIdentityAttempts)
            return giveUp(FailureCode.controllerNoReply)
        return lost(nowMonoUs, activeRide, appEnds = true, peerOrSystem = false, systemReconnecting = false)
    }

    private fun lost(
        nowMonoUs: Long,
        activeRide: Boolean,
        appEnds: Boolean,
        peerOrSystem: Boolean,
        systemReconnecting: Boolean,
    ): LinkAction {
        val stable = peerOrSystem && firstReadingMonoUs?.let { nowMonoUs - it >= ControllerPolicy.stableLinkUs } == true
        val pendingAttempt = attemptDeadlineMonoUs != null && linkUpMonoUs == null
        endLink()
        if (!radioAvailable) return LinkAction.Wait(Long.MAX_VALUE)
        val attempt = ++retries
        if (attempt > ControllerPolicy.reconnectBackoffUs.size && !activeRide)
            return giveUp(FailureCode.bikeUnavailable)
        val delay =
            when {
                attempt > ControllerPolicy.reconnectBackoffUs.size -> ControllerPolicy.rideReconnectUs
                attempt == 1 && stable -> 0L
                appEnds -> maxOf(ControllerPolicy.reconnectBackoffUs[attempt - 1], ControllerPolicy.linkReleaseUs)
                else -> ControllerPolicy.reconnectBackoffUs[attempt - 1]
            }
        val adopt = systemReconnecting && delay == 0L
        val reconnect = LinkAction.Reconnect(nowMonoUs + delay, adopt)
        return if (appEnds || pendingAttempt || systemReconnecting && !adopt) awaitRelease(reconnect, nowMonoUs)
        else reconnect
    }

    private fun giveUp(code: FailureCode): LinkAction {
        endLink()
        wanted = false
        awaitingRelease = null
        return LinkAction.GiveUp(code)
    }

    private fun awaitRelease(reconnect: LinkAction.Reconnect, nowMonoUs: Long): LinkAction.Reconnect {
        endLink()
        awaitingRelease = reconnect
        cancelGraceMonoUs = nowMonoUs + ControllerPolicy.cancelGraceUs
        return reconnect
    }

    private fun endLink() {
        attemptDeadlineMonoUs = null
        linkUpMonoUs = null
        verified = false
        waiting = null
        inFlight = null
        sentMonoUs = null
        unansweredSinceMonoUs = null
        blockedSinceMonoUs = null
        nextPollMonoUs = null
        lastNotificationMonoUs = null
        firstReadingMonoUs = null
        lastReadingMonoUs = null
    }

    private fun replyDeadlineUs(): Long? {
        val sent = sentMonoUs ?: return null
        return when (inFlight) {
            ControllerRequest.identity -> sent + ControllerPolicy.replyUs
            ControllerRequest.telemetry ->
                minOf(sent + ControllerPolicy.resendUs, (unansweredSinceMonoUs ?: sent) + ControllerPolicy.silenceUs)
            null -> null
        }
    }

    private fun wait(): LinkAction.Wait {
        val deadlines =
            listOfNotNull(
                cancelGraceMonoUs,
                attemptDeadlineMonoUs,
                replyDeadlineUs(),
                blockedSinceMonoUs?.plus(ControllerPolicy.replyUs),
            )
        return LinkAction.Wait(deadlines.minOrNull() ?: Long.MAX_VALUE)
    }

    private fun plannedAgeUs(): Long? {
        if (!plansLinkLimit || !planning || linkAges.size < ControllerPolicy.linkLimitDrops) return null
        val shortest = linkAges.min()
        if (shortest < ControllerPolicy.stableLinkUs || linkAges.max() - shortest > ControllerPolicy.linkLimitSpreadUs)
            return null
        return shortest - ControllerPolicy.linkLimitMarginUs
    }

    private fun pollIntervalUs(rate: SampleRate, background: Boolean, recording: Boolean): Long {
        val hz = if (background && !recording) minOf(rate.hz, ControllerPolicy.backgroundPollHz) else rate.hz
        return SECOND_US / hz
    }

    private companion object {
        const val SECOND_US = 1_000_000L
    }
}

public sealed interface LinkAction {
    public data class Send(public val frame: ControllerFrame, public val resent: Boolean) : LinkAction

    public data class Wait(public val untilMonoUs: Long) : LinkAction

    public data class Reconnect(public val atMonoUs: Long, public val adopt: Boolean) : LinkAction

    public data class GiveUp(public val code: FailureCode) : LinkAction

    public data object Release : LinkAction
}
