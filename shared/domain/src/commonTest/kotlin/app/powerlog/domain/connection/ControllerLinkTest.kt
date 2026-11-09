package app.powerlog.domain.connection

import app.powerlog.domain.core.controller.ControllerIdentity
import app.powerlog.domain.core.controller.ControllerReply
import app.powerlog.domain.core.controller.DecodeResult
import app.powerlog.domain.core.controller.DecoderId
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.cyc.CycDecoder
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerLinkTest {
    private val identity = ControllerReply.Identity(ControllerIdentity(DecoderId("cyc"), "X6", "20250725", "5.3"))
    private val reading = ControllerReply.Reading(mapOf(MetricId.humanPowerW to 200.0))
    private val second = 1_000_000L

    private fun decoded(reply: ControllerReply) = DecodeResult(listOf(reply), 0, 0)

    private class Driver(val link: ControllerLink, var now: Long = 0) {
        var readings = 0

        fun verify(at: Long = now) {
            now = at
            link.attempt(now)
            link.linkUp(now)
            assertIs<LinkAction.Send>(link.linkReady(GattResult.ready, false, now, false))
            link.accepted(now)
        }
    }

    private fun verified(known: Boolean = false, plans: Boolean = false, rate: SampleRate = SampleRate.hz8): Driver {
        val driver = Driver(ControllerLink(CycDecoder, known, plans))
        driver.verify()
        assertEquals(listOf(identity), driver.link.replies(decoded(identity), driver.now))
        val first = driver.link.next(driver.now, rate, background = false, recording = true)
        assertIs<LinkAction.Send>(first)
        driver.link.accepted(driver.now)
        return driver
    }

    private fun Driver.poll(at: Long, recording: Boolean = true) {
        now = at
        assertIs<LinkAction.Send>(link.next(at, SampleRate.hz8, false, recording))
        link.accepted(at)
    }

    private fun Driver.reply(at: Long, recording: Boolean = true, rate: SampleRate = SampleRate.hz8): LinkAction {
        now = at
        if (link.replies(decoded(reading), now).isNotEmpty()) readings++
        return link.next(now, rate, background = false, recording = recording)
    }

    @Test
    fun theFirstTelemetryRequestGoesInTheIdentityRepliesTask() {
        val driver = Driver(ControllerLink(CycDecoder, known = false, plansLinkLimit = false))
        driver.verify()
        assertEquals(listOf(identity), driver.link.replies(decoded(identity), 10))
        val send = assertIs<LinkAction.Send>(driver.link.next(10, SampleRate.hz8, false, false))
        assertEquals(false, send.resent)
        assertEquals(
            "02053203c0fb8f5a1a03",
            send.frame.bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
    }

    @Test
    fun theNextRequestIsDueOneIntervalAfterTheLastWasSentAndAnOverduePollGoesInTheRepliesTask() {
        val driver = verified()
        assertEquals(LinkAction.Wait(125_000), driver.reply(20_000))
        assertIs<LinkAction.Send>(driver.link.next(125_000, SampleRate.hz8, false, true))
        driver.link.accepted(125_000)
        assertIs<LinkAction.Send>(driver.reply(400_000))
    }

    @Test
    fun instantRepliesNeverExceedTheRate() {
        val driver = verified()
        var wake = 0L
        while (driver.now < 60 * second) {
            when (val action = driver.reply(wake)) {
                is LinkAction.Send -> driver.link.accepted(driver.now).also { wake = driver.now }
                is LinkAction.Wait -> {
                    wake = action.untilMonoUs
                    driver.now = wake
                    assertIs<LinkAction.Send>(driver.link.next(wake, SampleRate.hz8, false, true))
                    driver.link.accepted(wake)
                }
                else -> error("unexpected $action")
            }
        }
        assertTrue(driver.readings <= 481, "${driver.readings}")
    }

    @Test
    fun delayedTimersAndRepliesStillCarryNinetyFivePercentOfEightHertz() {
        val driver = verified()
        var sentAt = 0L
        while (driver.now < 60 * second) {
            val action = driver.reply(sentAt + 20_000)
            val at =
                when (action) {
                    is LinkAction.Send -> driver.now
                    is LinkAction.Wait -> action.untilMonoUs + 4_000
                    else -> error("unexpected $action")
                }
            if (action is LinkAction.Wait) {
                driver.now = at
                assertIs<LinkAction.Send>(driver.link.next(at, SampleRate.hz8, false, true))
            }
            driver.link.accepted(at)
            sentAt = at
        }
        assertTrue(driver.readings >= 456, "${driver.readings}")
    }

    @Test
    fun rateAndBackgroundChangesRepaceFromTheNewestReading() {
        val driver = verified()
        driver.reply(20_000)
        assertEquals(LinkAction.Wait(520_000), driver.link.next(30_000, SampleRate.hz2, false, true))
        assertEquals(LinkAction.Wait(1_020_000), driver.link.next(40_000, SampleRate.hz8, true, false))
        assertEquals(LinkAction.Wait(145_000), driver.link.next(50_000, SampleRate.hz8, true, true))
        assertIs<LinkAction.Send>(driver.link.next(145_000, SampleRate.hz8, true, true))
        assertEquals(1_145_000L, driver.link.accepted(145_000))
    }

    @Test
    fun anUnansweredRequestIsSentAgainThenTheLinkEndsAfterSilence() {
        val driver = verified()
        assertEquals(LinkAction.Wait(second), driver.link.timedOut(500_000, false))
        val resend = assertIs<LinkAction.Send>(driver.link.timedOut(second, false))
        assertTrue(resend.resent)
        assertEquals(2 * second, driver.link.accepted(second))
        driver.link.timedOut(2 * second, false).let { assertIs<LinkAction.Send>(it) }
        assertEquals(3 * second, driver.link.accepted(2 * second))
        val reconnect = assertIs<LinkAction.Reconnect>(driver.link.timedOut(3 * second, false))
        assertEquals(LinkAction.Reconnect(5 * second, adopt = false), reconnect)
    }

    @Test
    fun aLateReplyAndTheReplyToItsResendRecordOneReading() {
        val driver = verified()
        assertIs<LinkAction.Send>(driver.link.timedOut(second, false))
        driver.link.accepted(second)
        assertEquals(1, driver.link.replies(DecodeResult(listOf(reading, reading), 0, 0), 1_100_000).size)
        assertEquals(emptyList(), driver.link.replies(decoded(reading), 1_150_000))
    }

    @Test
    fun aNotificationLongAfterThePreviousResetsTheFramer() {
        val link = ControllerLink(CycDecoder, known = false, plansLinkLimit = false)
        assertEquals(false, link.notified(0))
        assertEquals(false, link.notified(2_500_000))
        assertEquals(true, link.notified(5_000_001))
    }

    @Test
    fun anUnknownBikeGetsTwoIdentityAttemptsAndAKnownOneTheReconnectRule() {
        val unknown = Driver(ControllerLink(CycDecoder, known = false, plansLinkLimit = false))
        unknown.verify()
        assertIs<LinkAction.Reconnect>(unknown.link.timedOut(2_500_000, false))
        unknown.link.released(2_600_000)
        unknown.verify(5_000_000)
        assertEquals(LinkAction.GiveUp(FailureCode.controllerNoReply), unknown.link.timedOut(7_500_000, false))
        val known = Driver(ControllerLink(CycDecoder, known = true, plansLinkLimit = false))
        repeat(3) {
            known.verify(it * 30 * second)
            assertIs<LinkAction.Reconnect>(known.link.timedOut(it * 30 * second + 2_500_000, false))
            known.link.released(it * 30 * second + 2_600_000)
        }
    }

    @Test
    fun aMissingServiceIsRetriedOnlyWhenTheOsErredARideIsActiveOrTheControllerIsKnown() {
        fun ready(known: Boolean, platformError: Boolean, ride: Boolean): LinkAction {
            val link = ControllerLink(CycDecoder, known, plansLinkLimit = false)
            link.attempt(0)
            link.linkUp(0)
            return link.linkReady(GattResult.serviceMissing, platformError, 0, ride)
        }
        assertEquals(LinkAction.GiveUp(FailureCode.controllerUnsupported), ready(false, false, false))
        assertIs<LinkAction.Reconnect>(ready(true, false, false))
        assertIs<LinkAction.Reconnect>(ready(false, true, false))
        assertIs<LinkAction.Reconnect>(ready(false, false, true))
        val notify = ControllerLink(CycDecoder, known = false, plansLinkLimit = false)
        notify.attempt(0)
        assertIs<LinkAction.Reconnect>(notify.linkReady(GattResult.notifyFailed, false, 0, false))
    }

    @Test
    fun reconnectsBackOffThenGiveUpOutsideARideAndRetryEveryThirtySecondsInOne() {
        fun drops(ride: Boolean): List<LinkAction> {
            val link = ControllerLink(CycDecoder, known = true, plansLinkLimit = false)
            return (1..7).map {
                link.disconnected(
                    it * 100 * second,
                    peerOrSystem = false,
                    activeRide = ride,
                    systemReconnecting = false,
                )
            }
        }
        val outside = drops(ride = false)
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 16L).mapIndexed { index, delay ->
                LinkAction.Reconnect((index + 1) * 100 * second + delay * second, adopt = false)
            },
            outside.take(5),
        )
        assertEquals(LinkAction.GiveUp(FailureCode.bikeUnavailable), outside[5])
        assertEquals(LinkAction.Reconnect(600 * second + 30 * second, adopt = false), drops(ride = true)[5])
    }

    @Test
    fun theRememberedBikesConnectIsReconnectAttemptOne() {
        val link = ControllerLink(CycDecoder, known = true, plansLinkLimit = true, remembered = true)
        val drops =
            (1..5).map {
                link.disconnected(
                    it * 100 * second,
                    peerOrSystem = false,
                    activeRide = false,
                    systemReconnecting = false,
                )
            }
        assertEquals(
            listOf(2L, 4L, 8L, 16L).mapIndexed { index, delay ->
                LinkAction.Reconnect((index + 1) * 100 * second + delay * second, adopt = false)
            },
            drops.take(4),
        )
        assertEquals(LinkAction.GiveUp(FailureCode.bikeUnavailable), drops[4])
    }

    @Test
    fun aStableLinkTheBikeDroppedReconnectsAtOnceAndIosAdoptsIt() {
        val driver = verified(known = true)
        driver.reply(20_000)
        driver.poll(125_000)
        driver.reply(31 * second)
        assertEquals(
            LinkAction.Reconnect(32 * second, adopt = true),
            driver.link.disconnected(32 * second, peerOrSystem = true, activeRide = true, systemReconnecting = true),
        )
    }

    @Test
    fun telemetryForSilenceRestartsTheBackoff() {
        val driver = verified(known = true)
        val link = driver.link
        link.disconnected(second, peerOrSystem = false, activeRide = false, systemReconnecting = false)
        driver.verify(10 * second)
        link.replies(decoded(identity), driver.now)
        link.next(driver.now, SampleRate.hz8, false, false)
        link.accepted(driver.now)
        driver.reply(10 * second + 20_000, recording = false)
        driver.poll(10 * second + 125_000, recording = false)
        driver.reply(13 * second + 20_000, recording = false)
        val action =
            link.disconnected(14 * second, peerOrSystem = false, activeRide = false, systemReconnecting = false)
        assertEquals(LinkAction.Reconnect(15 * second, adopt = false), action)
    }

    @Test
    fun aCancelledLinkStillHeldAtTheGraceIsReleasedOrGivenUp() {
        val outside = verified(known = true)
        val reconnect = assertIs<LinkAction.Reconnect>(outside.link.timedOut(3 * second, false))
        assertEquals(LinkAction.Wait(8 * second), outside.link.timedOut(4 * second, false))
        assertEquals(LinkAction.Release, outside.link.timedOut(8 * second, false))
        assertEquals(LinkAction.GiveUp(FailureCode.bikeUnavailable), outside.link.unreleased(8 * second, false))
        val inRide = verified(known = true)
        val waiting = assertIs<LinkAction.Reconnect>(inRide.link.timedOut(3 * second, true))
        assertEquals(LinkAction.Release, inRide.link.timedOut(8 * second, true))
        assertNull(inRide.link.unreleased(8 * second, true))
        assertEquals(waiting, inRide.link.released(9 * second))
        assertEquals(reconnect.atMonoUs, waiting.atMonoUs)
    }

    @Test
    fun aHeldRequestEndsTheLinkAfterTheReplyWindow() {
        val driver = verified()
        driver.reply(20_000)
        assertIs<LinkAction.Send>(driver.link.next(125_000, SampleRate.hz8, false, true))
        assertEquals(2_625_000L, driver.link.blocked(125_000))
        assertEquals(LinkAction.Wait(2_625_000), driver.link.timedOut(1_000_000, false))
        assertIs<LinkAction.Reconnect>(driver.link.timedOut(2_625_000, false))
    }

    @Test
    fun theRadioDropsTheRequestAndOnlyAWantedBikeReconnects() {
        val driver = verified()
        assertNull(driver.link.radio(false, second))
        assertEquals(LinkAction.Wait(Long.MAX_VALUE), driver.link.disconnected(2 * second, true, false, false))
        assertEquals(LinkAction.Reconnect(3 * second, adopt = false), driver.link.radio(true, 3 * second))
        val unwanted = ControllerLink(CycDecoder, known = false, plansLinkLimit = false)
        unwanted.attempt(0)
        unwanted.linkUp(0)
        unwanted.linkReady(GattResult.characteristicMissing, false, 0, false)
        assertNull(unwanted.radio(true, second))
    }

    private fun limited(
        vararg ageSeconds: Double,
        plans: Boolean = true,
        connectWaits: List<Long> = emptyList(),
    ): Driver {
        val driver = verified(known = true, plans = plans)
        var clock = 0L
        ageSeconds.forEachIndexed { index, age ->
            val wait = connectWaits.getOrElse(index) { 0L }
            val up = clock + wait
            driver.link.attempt(clock)
            driver.link.linkUp(up)
            driver.link.linkReady(GattResult.ready, false, up, true)
            driver.link.accepted(up)
            driver.link.replies(decoded(identity), up)
            driver.link.next(up, SampleRate.hz8, false, true)
            driver.link.accepted(up)
            driver.reply(up + 20_000)
            val down = up + (age * second).toLong()
            driver.link.disconnected(down, peerOrSystem = true, activeRide = true, systemReconnecting = false)
            clock = down + 10 * second
        }
        driver.verify(clock)
        driver.link.replies(decoded(identity), clock)
        driver.link.next(clock, SampleRate.hz8, false, true)
        driver.link.accepted(clock)
        driver.now = clock
        return driver
    }

    private fun Driver.plansAt(ageUs: Long, recording: Boolean = true): Boolean {
        val up = now
        val before = reply(up + ageUs - 1, recording)
        if (before is LinkAction.Reconnect) return false
        if (before is LinkAction.Wait) poll(before.untilMonoUs, recording) else link.accepted(now)
        val at = maxOf(now, up + ageUs)
        return reply(at, recording) == LinkAction.Reconnect(at, adopt = false)
    }

    @Test
    fun threeDropsAtOneAgePlanTheReconnectBeforeTheShortest() {
        assertTrue(limited(95.0, 95.6, 94.9).plansAt(92_900_000))
        assertTrue(!limited(95.0, 95.6).plansAt(92_900_000))
        assertTrue(limited(95.0, 95.6, 94.9, connectWaits = listOf(0, 4 * second, 2 * second)).plansAt(92_900_000))
    }

    @Test
    fun theLastThreeDropsMustAgreeWithinTheSpreadAndBeStable() {
        assertTrue(!limited(95.0, 96.2, 95.0).plansAt(93_000_000))
        assertTrue(limited(95.0, 96.2, 95.0, 95.5, 95.1).plansAt(93_000_000))
        assertTrue(!limited(20.0, 20.2, 20.1).plansAt(18_000_000))
    }

    @Test
    fun aDropBeforeThePlanTurnsItOffButADropAtTheLimitKeepsIt() {
        assertTrue(!limited(95.0, 95.0, 95.0, 2.0, 95.0, 95.0, 95.0).plansAt(93_000_000))
        assertTrue(limited(95.0, 95.6, 94.9, 95.3).plansAt(92_900_000))
    }

    @Test
    fun noPlanWithoutARideOrWithoutPlanning() {
        assertTrue(!limited(95.0, 95.6, 94.9).plansAt(92_900_000, recording = false))
        assertTrue(!limited(95.0, 95.6, 94.9, plans = false).plansAt(92_900_000))
    }

    @Test
    fun aDropBeforeAnyReadingLeavesPlanningAsItWas() {
        val driver = limited(95.0, 95.6, 94.9)
        val link = driver.link
        link.attempt(driver.now)
        link.linkUp(driver.now)
        link.disconnected(driver.now + 10 * second, peerOrSystem = true, activeRide = true, systemReconnecting = false)
        driver.verify(driver.now + 20 * second)
        link.replies(decoded(identity), driver.now)
        link.next(driver.now, SampleRate.hz8, false, true)
        link.accepted(driver.now)
        assertTrue(driver.plansAt(92_900_000))
    }
}
