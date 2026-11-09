package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput
import app.powerlog.application.fakes.FakeBackgroundScheduler
import app.powerlog.application.fakes.FakeHealthProvider
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.store.HealthWork
import app.powerlog.application.store.HealthWorkScope
import app.powerlog.application.store.StoreResult
import app.powerlog.domain.core.health.ComponentState
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class HealthScenarioTest {
    private val readable =
        HealthMetrics.readback(HealthProviderKind.healthConnect).associate { it.wire to PermissionState.granted }

    private fun grants(write: Set<HealthComponent> = emptySet()) =
        HealthGrants(
            write =
                HealthComponent.entries.associateWith {
                    if (it in write) PermissionState.granted else PermissionState.denied
                },
            read = readable,
            route = if (HealthComponent.route in write) PermissionState.granted else PermissionState.denied,
            backgroundRead = PermissionState.granted,
            readableFromUtcUs = null,
        )

    private suspend fun Scenario.rows(rideId: RideId): List<HealthWork> =
        assertIs<StoreResult.Ok<List<HealthWork>>>(archive.stores.health.work(rideId)).value

    @Test
    fun anAndroidRideOffHealthGetsReadbackRowsAtTheFinishInstantsAndTheyMoveOnAfterEachPass() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants()
        val background = FakeBackgroundScheduler()
        scenario.health = health
        scenario.backgroundScheduler = background
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(5_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(100)
            val end =
                requireNotNull(
                    assertIs<StoreResult.Ok<*>>(scenario.archive.stores.rides.ride(rideId)).value.let {
                        (it as app.powerlog.application.store.RideRecord).ride.end
                    }
                )
            val readback = scenario.rows(rideId).filter { it.scope is HealthWorkScope.Readback }
            assertEquals(HealthMetrics.readback(HealthProviderKind.healthConnect).size, readback.size)
            val first = end.utcUs + HealthPolicy.readbackAfterFinishUs[0]
            assertTrue(readback.all { it.state == ComponentState.pending && it.dueUtcUs == first }, "$readback")
            val part = scenario.rows(rideId).single { it.scope == HealthWorkScope.Publish(HealthComponent.readback) }
            assertEquals(ComponentState.pending, part.state)
            assertEquals(
                listOf(FakeBackgroundScheduler.Work(rideId.value, null, ScheduleKind.replace)),
                background.pending(rideId.value),
            )

            delay(3_000)
            val second = end.utcUs + HealthPolicy.readbackAfterFinishUs[1]
            assertTrue(
                scenario.rows(rideId).filter { it.scope is HealthWorkScope.Readback }.all { it.dueUtcUs == second }
            )
            assertTrue(health.reader.reads.received.isNotEmpty())

            assertEquals(Outcome.Ok(Unit), scenario.api.health.refresh(RideInput(rideId)))
            val reset = scenario.rows(rideId).filter { it.scope is HealthWorkScope.Readback }
            assertTrue(reset.all { it.attempts == 0 && (it.dueUtcUs ?: Long.MAX_VALUE) <= scenario.clock.utcUs() })
        }
    }

    @Test
    fun aHealthConnectRunWithoutThePowerGrantSavesTheRestAndTheWorkout() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet() - HealthComponent.riderPower)
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val seeded =
                scenario.seedRide(
                    endUs = 60_000_000,
                    readingsUs = (0L until 60_000_000L step 250_000L).toList(),
                    fixesUs = (0L until 60_000_000L step 1_000_000L).toList(),
                    recordedBy = Device.androidPhone,
                    provider = HealthProviderKind.healthConnect,
                )
            assertEquals(LaunchResult.Ready, scenario.launch())
            val rideId = seeded.ride.id
            assertEquals(HealthWorkResult.success, scenario.application.host.runHealthWork(rideId.value, 0))
            val states =
                scenario
                    .rows(rideId)
                    .filter { it.scope is HealthWorkScope.Publish }
                    .associate { (it.scope as HealthWorkScope.Publish).component to (it.state to it.reason) }
            assertEquals(ComponentState.saved to null, states[HealthComponent.workout])
            assertEquals(ComponentState.unavailable to HealthReason.permission, states[HealthComponent.riderPower])
            assertEquals(ComponentState.saved, states[HealthComponent.cyclingCadence]?.first)
            assertEquals(ComponentState.saved, states[HealthComponent.cyclingSpeed]?.first)
            assertEquals(ComponentState.saved, states[HealthComponent.cyclingDistance]?.first)
            assertEquals(ComponentState.saved, states[HealthComponent.route]?.first)
            assertEquals(ComponentState.notRequested, states[HealthComponent.laps]?.first)
            val outcome = assertIs<StoreResult.Ok<*>>(scenario.archive.stores.health.outcome(rideId)).value
            assertEquals(HealthOutcome.saved, (outcome as app.powerlog.domain.core.health.RideHealth).outcome)
            val inserted = health.writer!!.inserts.received
            assertTrue(inserted.last().single() is HealthConnectRecordPlan.Session)
            assertTrue(
                inserted.flatten().none {
                    it is HealthConnectRecordPlan.Record &&
                        it.plan.quantity == app.powerlog.domain.core.health.HealthQuantity.riderPower
                }
            )
        }
    }

    private suspend fun Scenario.healthConnectRide(): RideId {
        val seeded =
            seedRide(
                endUs = 60_000_000,
                readingsUs = (0L until 60_000_000L step 250_000L).toList(),
                fixesUs = (0L until 60_000_000L step 1_000_000L).toList(),
                recordedBy = Device.androidPhone,
                provider = HealthProviderKind.healthConnect,
            )
        return seeded.ride.id
    }

    private suspend fun Scenario.states(rideId: RideId): Map<HealthComponent, Pair<ComponentState, HealthReason?>> =
        rows(rideId)
            .filter { it.scope is HealthWorkScope.Publish }
            .associate { (it.scope as HealthWorkScope.Publish).component to (it.state to it.reason) }

    private suspend fun Scenario.outcome(rideId: RideId): app.powerlog.domain.core.health.RideHealth =
        requireNotNull(
            assertIs<StoreResult.Ok<app.powerlog.domain.core.health.RideHealth?>>(archive.stores.health.outcome(rideId))
                .value
        )

    @Test
    fun aPublishRowFoundSubmittedAtARunsStartIsUncertainAndNeverReplayed() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = scenario.healthConnectRide()
            val cadence =
                HealthWork(
                    rideId,
                    HealthWorkScope.Publish(HealthComponent.cyclingCadence),
                    ComponentState.submitted,
                    0,
                    null,
                    null,
                    null,
                )
            scenario.archive.seed(
                app.powerlog.application.store.WriteBatch(
                    listOf(app.powerlog.application.store.Mutation.PutHealthWork(listOf(cadence)))
                )
            )
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(HealthWorkResult.success, scenario.application.host.runHealthWork(rideId.value, 0))
            assertEquals(
                ComponentState.failed to HealthReason.ambiguous,
                scenario.states(rideId)[HealthComponent.cyclingCadence],
            )
            assertEquals(HealthOutcome.unknown, scenario.outcome(rideId).outcome)
            assertTrue(health.writer!!.inserts.received.isEmpty())
            assertEquals(HealthWorkResult.success, scenario.application.host.runHealthWork(rideId.value, 1))
            assertTrue(health.writer.inserts.received.isEmpty())
        }
    }

    @Test
    fun aRateLimitedPageWaitsThenRetriesAndEndsInQuotaAfterItsAttempts() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        health.writer!!.inserts.script = {
            app.powerlog.application.fakes.Answer.Failed(
                app.powerlog.application.port.PortError(app.powerlog.application.port.PortErrorKind.rateLimited)
            )
        }
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = scenario.healthConnectRide()
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.application.host.runHealthWork(rideId.value, 0)
            assertEquals(1, health.writer.inserts.received.size)
            val waiting =
                scenario.rows(rideId).single { it.scope == HealthWorkScope.Publish(HealthComponent.cyclingCadence) }
            assertEquals(ComponentState.pending, waiting.state)
            assertEquals(
                scenario.clock.utcUs() + app.powerlog.application.health.HealthWorkPolicy.rateLimitRetryUs,
                waiting.dueUtcUs,
            )
            scenario.application.host.runHealthWork(rideId.value, 0)
            assertEquals(1, health.writer.inserts.received.size)
            repeat(2) {
                delay(app.powerlog.application.health.HealthWorkPolicy.rateLimitRetryUs / 1_000)
                scenario.application.host.runHealthWork(rideId.value, 0)
            }
            assertEquals(3, health.writer.inserts.received.size)
            assertEquals(
                ComponentState.failed to HealthReason.quota,
                scenario.states(rideId)[HealthComponent.cyclingCadence],
            )
            assertEquals(HealthOutcome.failed, scenario.outcome(rideId).outcome)
        }
    }

    @Test
    fun retryHealthSaveResetsOnlyRetryableRowsAndSchedulesTheWork() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        val background = FakeBackgroundScheduler()
        scenario.health = health
        scenario.backgroundScheduler = background
        scenario.test {
            val rideId = scenario.healthConnectRide()
            val failed =
                listOf(
                    HealthWork(
                        rideId,
                        HealthWorkScope.Publish(HealthComponent.cyclingCadence),
                        ComponentState.failed,
                        1,
                        null,
                        null,
                        HealthReason.quota,
                    ),
                    HealthWork(
                        rideId,
                        HealthWorkScope.Publish(HealthComponent.riderPower),
                        ComponentState.failed,
                        0,
                        null,
                        null,
                        HealthReason.ambiguous,
                    ),
                )
            scenario.archive.seed(
                app.powerlog.application.store.WriteBatch(
                    listOf(app.powerlog.application.store.Mutation.PutHealthWork(failed))
                )
            )
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(Outcome.Ok(Unit), scenario.api.health.retrySave(RideInput(rideId)))
            assertEquals(ComponentState.pending to null, scenario.states(rideId)[HealthComponent.cyclingCadence])
            assertEquals(
                ComponentState.failed to HealthReason.ambiguous,
                scenario.states(rideId)[HealthComponent.riderPower],
            )
            assertEquals(HealthOutcome.pending, scenario.outcome(rideId).outcome)
            assertEquals(
                listOf(FakeBackgroundScheduler.Work(rideId.value, null, ScheduleKind.replace)),
                background.pending(rideId.value),
            )
        }
    }

    @Test
    fun thePhoneWriterSendsSecondsLateInChunksOneCallAtATimeAndSavesTheWorkoutAtFinish() {
        val scenario = Scenario(Platform.ios)
        val health = FakeHealthProvider.apple(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        scenario.health = health
        scenario.test {
            with(scenario) { ready() }
            assertEquals(
                Outcome.Ok(Unit),
                scenario.api.settings.change(
                    app.powerlog.application.settings.SettingsChange.Toggle(
                        app.powerlog.application.settings.RideToggle.saveToHealth,
                        true,
                    )
                ),
            )
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(app.powerlog.application.api.Empty))
            val rideId =
                requireNotNull(scenario.recording { it.phase == app.powerlog.domain.core.ride.RidePhase.running }.ride)
                    .rideId
            val session = requireNotNull(health.session)
            assertEquals(1, session.begins.received.size)
            delay(60_000)
            val sent = session.adds.received
            assertTrue(sent.isNotEmpty())
            assertTrue(sent.all { it.size <= app.powerlog.application.health.HealthWorkPolicy.appleQuantitiesPerCall })
            val lastEnd = sent.flatten().maxOf { it.endUtcUs }
            assertTrue(lastEnd <= scenario.clock.utcUs() - HealthPolicy.bufferUs, "$lastEnd")
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(1_000)
            assertEquals(1, session.finishes.received.size)
            assertEquals(HealthOutcome.saved, scenario.outcome(rideId).outcome)
        }
    }

    @Test
    fun aSessionTheSystemEndsFailsTheRideWithBuilderLostAndFinishCallsNothingOnIt() {
        val scenario = Scenario(Platform.ios)
        val health = FakeHealthProvider.apple(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        scenario.health = health
        scenario.test {
            with(scenario) { ready() }
            scenario.api.settings.change(
                app.powerlog.application.settings.SettingsChange.Toggle(
                    app.powerlog.application.settings.RideToggle.saveToHealth,
                    true,
                )
            )
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(app.powerlog.application.api.Empty))
            val rideId =
                requireNotNull(scenario.recording { it.phase == app.powerlog.domain.core.ride.RidePhase.running }.ride)
                    .rideId
            val session = requireNotNull(health.session)
            delay(10_000)
            session.fail(app.powerlog.application.port.PortError(app.powerlog.application.port.PortErrorKind.failed))
            delay(1_000)
            assertEquals(HealthOutcome.failed, scenario.outcome(rideId).outcome)
            assertEquals(HealthReason.builderLost, scenario.outcome(rideId).reason)
            assertTrue(
                scenario
                    .states(rideId)
                    .filterKeys { it != HealthComponent.readback }
                    .values
                    .all {
                        it.first == ComponentState.failed && it.second == HealthReason.builderLost
                    }
            )
            val adds = session.adds.received.size
            delay(10_000)
            assertEquals(adds, session.adds.received.size)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(1_000)
            assertTrue(session.finishes.received.isEmpty())
        }
    }

    @Test
    fun aBackgroundRunWithoutBackgroundReadLeavesRowsWaitingUntilTheNextForeground() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet()).copy(backgroundRead = PermissionState.denied)
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = scenario.healthConnectRide()
            assertEquals(LaunchResult.Ready, scenario.launch())
            scenario.application.host.foregroundChanged(false)
            scenario.application.host.runHealthWork(rideId.value, 0)
            delay(70_000)
            scenario.application.host.runHealthWork(rideId.value, 0)
            val waiting = scenario.rows(rideId).filter { it.scope is HealthWorkScope.Readback }
            assertTrue(waiting.all { it.state == ComponentState.pending && it.dueUtcUs == null }, "$waiting")
            assertTrue(health.reader.reads.received.isEmpty())
            scenario.application.host.foregroundChanged(true)
            delay(1_000)
            assertTrue(health.reader.reads.received.isNotEmpty())
            assertTrue(
                scenario
                    .rows(rideId)
                    .filter { it.scope is HealthWorkScope.Readback }
                    .none { it.dueUtcUs == null && it.state == ComponentState.pending }
            )
        }
    }

    @Test
    fun thePhoneWriterHoldsOneSessionWriteAtATimeAndFinishWaitsForIt() {
        val scenario = Scenario(Platform.ios)
        val health = FakeHealthProvider.apple(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        scenario.health = health
        scenario.test {
            with(scenario) { ready() }
            scenario.api.settings.change(
                app.powerlog.application.settings.SettingsChange.Toggle(
                    app.powerlog.application.settings.RideToggle.saveToHealth,
                    true,
                )
            )
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(app.powerlog.application.api.Empty))
            val rideId =
                requireNotNull(scenario.recording { it.phase == app.powerlog.domain.core.ride.RidePhase.running }.ride)
                    .rideId
            val session = requireNotNull(health.session)
            session.adds.script = { app.powerlog.application.fakes.Answer.Held }
            scenario.archive.telemetryReads = 0
            delay(8_000)
            assertEquals(1, session.adds.received.size)
            assertEquals(0, scenario.archive.telemetryReads)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.lap))
            delay(1_000)
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(1_000)
            assertEquals(1, session.adds.received.size)
            assertTrue(session.laps.received.isEmpty())
            assertTrue(session.finishes.received.isEmpty())
            session.adds.script = { app.powerlog.application.fakes.Answer.Ok(Unit) }
            session.adds.complete(Unit)
            delay(1_000)
            assertTrue(session.adds.received.size > 1)
            assertEquals(1, session.laps.received.size)
            assertEquals(1, session.finishes.received.size)
            assertEquals(HealthOutcome.saved, scenario.outcome(rideId).outcome)
        }
    }

    @Test
    fun readbackDeferredBeforeTheProcessDiedIsReadAtTheNextLaunchWithAUi() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = scenario.healthConnectRide()
            val waiting =
                HealthMetrics.readback(HealthProviderKind.healthConnect).map {
                    HealthWork(rideId, HealthWorkScope.Readback(it), ComponentState.pending, 0, null, null, null)
                }
            scenario.archive.seed(
                app.powerlog.application.store.WriteBatch(
                    listOf(app.powerlog.application.store.Mutation.PutHealthWork(waiting))
                )
            )
            delay(70_000)
            assertEquals(LaunchResult.Ready, scenario.launch())
            delay(1_000)
            assertTrue(health.reader.reads.received.isNotEmpty())
            assertTrue(
                scenario
                    .rows(rideId)
                    .filter { it.scope is HealthWorkScope.Readback }
                    .none {
                        it.state == ComponentState.pending && it.dueUtcUs == null
                    }
            )
        }
    }

    @Test
    fun aHealthOffRideWithoutReadGrantsShowsTheReadbackPartUnavailableOnItsHistoryRow() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants().copy(read = emptyMap())
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = with(scenario) { riding() }
            delay(5_000)
            val revision = scenario.page().single().let { scenario.archive.stores.rides.marker() }
            assertEquals(Outcome.Ok(Unit), scenario.control(rideId, RideAction.finish))
            delay(30_000)
            val part = scenario.rows(rideId).single { it.scope == HealthWorkScope.Publish(HealthComponent.readback) }
            assertEquals(ComponentState.unavailable to HealthReason.permission, part.state to part.reason)
            val row = scenario.page().single()
            val shown = requireNotNull(row.health).components.single()
            assertEquals(HealthComponent.readback, shown.component)
            assertEquals(ComponentState.unavailable to HealthReason.permission, shown.state to shown.reason)
            assertTrue(
                assertIs<StoreResult.Ok<Long>>(scenario.archive.stores.rides.marker()).value >
                    assertIs<StoreResult.Ok<Long>>(revision).value
            )
        }
    }

    @Test
    fun aSecondHealthWorkRunForTheSameRideWaitsForTheFirst() {
        val scenario = Scenario(Platform.android)
        val health = FakeHealthProvider.healthConnect(scenario.clock, scenario.post)
        health.access.grants = grants(HealthComponent.entries.toSet())
        health.writer!!.inserts.script = { app.powerlog.application.fakes.Answer.Held }
        scenario.health = health
        scenario.backgroundScheduler = FakeBackgroundScheduler()
        scenario.test {
            val rideId = scenario.healthConnectRide()
            assertEquals(LaunchResult.Ready, scenario.launch())
            val first = async { scenario.application.host.runHealthWork(rideId.value, 0) }
            runCurrent()
            val second = async { scenario.application.host.runHealthWork(rideId.value, 0) }
            delay(1_000)
            assertEquals(1, health.writer.inserts.received.size)
            assertTrue(!first.isCompleted && !second.isCompleted)
            health.writer.inserts.script = { app.powerlog.application.fakes.Answer.Ok(listOf("record")) }
            health.writer.inserts.complete(listOf("record"))
            first.await()
            second.await()
            assertTrue(health.writer.inserts.received.size > 1)
        }
    }
}
